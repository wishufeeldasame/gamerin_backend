package com.gamerin.backend.domain.upload;

import com.gamerin.backend.domain.upload.entity.UploadJob;
import com.gamerin.backend.domain.upload.repository.UploadJobRepository;
import com.gamerin.backend.domain.upload.dto.response.UploadJobResponse;
import com.gamerin.backend.domain.upload.service.UploadJobService;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.message.dto.request.*;
import com.gamerin.backend.domain.message.entity.*;
import com.gamerin.backend.domain.message.repository.*;
import com.gamerin.backend.domain.message.service.*;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;
import com.gamerin.backend.domain.post.moderation.ContentModerationService;
import com.gamerin.backend.domain.post.repository.*;
import com.gamerin.backend.domain.post.service.*;
import com.gamerin.backend.domain.user.entity.*;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

/** 테스트 전용 PostgreSQL 스키마 + 임시 파일만 사용하며 검열 API/ffprobe/SSE는 호출하지 않는다. */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "UPLOAD_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class UploadJobPostgresIntegrationTest {
    private static final String SCHEMA = "upload_" + UUID.randomUUID().toString().replace("-", "");
    private static Path root;
    @Autowired UploadJobService uploads;
    @Autowired UploadJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired PostRepository posts;
    @Autowired DirectMessageRepository messages;
    @Autowired MessageParticipantRepository participants;
    @Autowired MessageService messageService;
    @Autowired NotificationRepository notifications;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ContentModerationService moderation;
    @MockitoBean VideoMetadataService metadata;
    @MockitoBean MessageRealtimeService realtime;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PostResponseAssembler postAssembler;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MessageResponseAssembler messageAssembler;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("gamerin-upload-test-");
        sql("create schema " + SCHEMA);
        registry.add("spring.datasource.url", () -> System.getenv("UPLOAD_POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "upload-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
        registry.add("app.media.upload-dir", () -> root.resolve("media").toString());
        registry.add("app.media.video.processing.job-dir", () -> root.resolve("jobs").toString());
        registry.add("app.media.video.temp-storage-reserve-bytes", () -> "0");
    }

    @AfterAll static void cleanup() throws Exception {
        sql("drop schema if exists " + SCHEMA + " cascade");
        if (root != null) try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    private static void sql(String command) throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("UPLOAD_POSTGRES_TEST_URL"), "postgres", "upload-test");
                Statement statement = connection.createStatement()) { statement.execute(command); }
    }
    @BeforeEach void clearJobs() {
        // 실패 테스트에서 의도적으로 남긴 입력도 실제 서비스의 정리 경로를 거친다.
        transactions.executeWithoutResult(status -> {
            for (UploadJob job : jobs.findAll()) if (job.getStatus() == PostVideoJobStatus.QUEUED
                    || job.getStatus() == PostVideoJobStatus.PROCESSING) job.fail("PROCESSING_FAILED", "test cleanup");
        });
        uploads.cleanTerminalFiles();
        jobs.deleteAll();
    }

    @Test void concurrentReplayAndLostResponseCreateOnePostWithAtomicCompletion() throws Exception {
        CustomUserPrincipal owner = owner();
        CreateMultipartPostRequest request = post(UUID.randomUUID());
        long before = posts.count();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<UploadJobResponse> first = executor.submit(() -> { start.await(); return uploads.enqueuePost(owner, request); });
            Future<UploadJobResponse> second = executor.submit(() -> { start.await(); return uploads.enqueuePost(owner, request); });
            start.countDown();
            UploadJobResponse accepted = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS).jobId()).isEqualTo(accepted.jobId());
            assertThat(jobs.count()).isOne();
            assertThat(posts.count()).isEqualTo(before);
            assertThat(uploads.get(owner, UploadJob.Kind.POST, request.getClientRequestId(), true).jobId()).isEqualTo(accepted.jobId());
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return null;
            }).when(moderation).assertPostAllowed(any(), anyList());
            assertThat(uploads.processNextJob()).isTrue();
            UploadJobResponse done = uploads.get(owner, UploadJob.Kind.POST, accepted.jobId(), false);
            assertThat(done.status()).isEqualTo("COMPLETED");
            assertThat(done.post()).isNotNull();
            assertThat(done.post().media()).hasSize(1);
            assertThat(done.message()).isNull();
            assertThat(posts.count()).isEqualTo(before + 1);
            assertThat(uploads.enqueuePost(owner, request).jobId()).isEqualTo(accepted.jobId());
            assertThat(uploads.processNextJob()).isFalse();
            assertThat(jobs.findById(accepted.jobId()).orElseThrow().getPayload()).isNull();
            assertThat(Files.exists(jobPath(accepted.jobId()))).isFalse();
            verify(moderation, times(1)).assertPostAllowed(any(), anyList());
        }
    }

    @Test void differentPayloadSameKeyConflictsAndOtherOwnerOrKindCannotRead() throws Exception {
        CustomUserPrincipal owner = owner();
        CreateMultipartPostRequest request = post(UUID.randomUUID());
        UploadJobResponse accepted = uploads.enqueuePost(owner, request);
        request.setContent("changed");
        assertStatus(409, () -> uploads.enqueuePost(owner, request));
        assertStatus(404, () -> uploads.get(owner(), UploadJob.Kind.POST, accepted.jobId(), false));
        assertStatus(404, () -> uploads.get(owner, UploadJob.Kind.MESSAGE, accepted.jobId(), false));
        assertStatus(404, () -> uploads.get(owner, UploadJob.Kind.POST, UUID.randomUUID(), true));
        try (var children = Files.list(root.resolve("jobs/uploads"))) { assertThat(children.count()).isOne(); }
    }

    @Test void moderationRejectionAndSystemFailureHaveDifferentCodesAndNoVisibleContent() throws Exception {
        CustomUserPrincipal owner = owner();
        long before = posts.count();
        CreateMultipartPostRequest rejected = post(UUID.randomUUID());
        UploadJobResponse accepted = uploads.enqueuePost(owner, rejected);
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "policy"))
                .when(moderation).assertPostAllowed(any(), anyList());
        uploads.processNextJob();
        assertFailure(owner, accepted.jobId(), "MODERATION_REJECTED");
        assertThat(uploads.enqueuePost(owner, rejected).jobId()).isEqualTo(accepted.jobId());
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upstream"))
                .when(moderation).assertPostAllowed(any(), anyList());
        UploadJobResponse unavailable = uploads.enqueuePost(owner, post(UUID.randomUUID()));
        uploads.processNextJob();
        assertFailure(owner, unavailable.jobId(), "PROCESSING_FAILED");
        assertThat(posts.count()).isEqualTo(before);
    }

    @Test void recoverInterruptedInputThenFailAfterTwoAttemptsAndCleanFiles() throws Exception {
        CustomUserPrincipal owner = owner();
        UploadJobResponse accepted = uploads.enqueuePost(owner, post(UUID.randomUUID()));
        transactions.executeWithoutResult(status -> jobs.findForUpdate(accepted.jobId()).orElseThrow().start());
        uploads.recoverInterruptedJobs();
        assertThat(uploads.get(owner, UploadJob.Kind.POST, accepted.jobId(), false).status()).isEqualTo("QUEUED");
        assertThat(Files.exists(jobPath(accepted.jobId()))).isTrue();
        transactions.executeWithoutResult(status -> jobs.findForUpdate(accepted.jobId()).orElseThrow().start());
        uploads.recoverInterruptedJobs();
        assertFailure(owner, accepted.jobId(), "PROCESSING_FAILED");
        assertThat(Files.exists(jobPath(accepted.jobId()))).isFalse();
        UploadJobResponse missing = uploads.enqueuePost(owner, post(UUID.randomUUID()));
        Files.delete(jobPath(missing.jobId()).resolve("0"));
        uploads.processNextJob();
        assertFailure(owner, missing.jobId(), "INPUT_MISSING");
    }

    @Test void skipLockedClaimsDifferentJobsAndUniqueConstraintPreventsDuplicateKeys() throws Exception {
        CustomUserPrincipal owner = owner();
        UploadJobResponse first = uploads.enqueuePost(owner, post(UUID.randomUUID()));
        UploadJobResponse second = uploads.enqueuePost(owner, post(UUID.randomUUID()));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<UUID> holder = executor.submit(() -> transactions.execute(status -> {
                UUID id = jobs.findOldestForUpdate(PostVideoJobStatus.QUEUED, PageRequest.of(0, 1)).getFirst().getId();
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return id;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                UUID next = transactions.execute(status -> jobs.findOldestForUpdate(PostVideoJobStatus.QUEUED, PageRequest.of(0, 1)).getFirst().getId());
                assertThat(next).isEqualTo(second.jobId());
            } finally { release.countDown(); }
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo(first.jobId());
        }
        UploadJob job = jobs.findById(first.jobId()).orElseThrow();
        assertThatThrownBy(() -> jobs.saveAndFlush(UploadJob.queue(UUID.randomUUID(), owner.getUserId(), UploadJob.Kind.POST,
                job.getClientKey(), job.getFingerprint(), job.getPayload()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void dmAttachmentIsPrivateAndPublishesOnlyOnceAfterCompletion() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        SendMultipartMessageRequest request = message(UUID.randomUUID());
        long before = messages.count(), notices = notifications.count();
        UploadJobResponse accepted = uploads.enqueueMessage(sender, conversation, request);
        assertThat(messages.count()).isEqualTo(before);
        assertThat(notifications.count()).isEqualTo(notices);
        verifyNoInteractions(realtime);
        assertStatus(409, () -> messageService.sendMultipartMessage(sender, conversation, request));
        assertStatus(409, () -> messageService.sendMessage(sender, conversation, new SendMessageRequest("other", null, request.getClientMessageId())));
        uploads.processNextJob();
        UploadJobResponse done = uploads.get(sender, UploadJob.Kind.MESSAGE, accepted.jobId(), false);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.message()).isNotNull();
        assertThat(done.message().clientMessageId()).isEqualTo(request.getClientMessageId());
        assertThat(done.message().attachments()).hasSize(1);
        assertThat(done.message().attachments().getFirst().url()).startsWith("/api/v1/messages/attachments/");
        long published = mockingDetails(realtime).getInvocations().size();
        uploads.enqueueMessage(sender, conversation, request);
        uploads.processNextJob();
        assertThat(messages.count()).isEqualTo(before + 1);
        assertThat(notifications.count()).isEqualTo(notices + 1);
        assertThat(mockingDetails(realtime).getInvocations()).hasSize((int) published);
        assertStatus(404, () -> uploads.get(recipient, UploadJob.Kind.MESSAGE, accepted.jobId(), false));
        SendMultipartMessageRequest changed = message(request.getClientMessageId()); changed.setContent("changed");
        assertStatus(409, () -> uploads.enqueueMessage(sender, conversation, changed));
        UUID otherConversation = conversation(sender, owner());
        assertStatus(409, () -> uploads.enqueueMessage(sender, otherConversation, request));
    }

    @Test void dmCompletionRechecksSenderLeavingAndRecipientWithdrawal() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        UploadJobResponse leaving = uploads.enqueueMessage(sender, conversation, message(UUID.randomUUID()));
        messageService.leaveConversation(sender, conversation);
        uploads.processNextJob();
        assertThat(uploads.get(sender, UploadJob.Kind.MESSAGE, leaving.jobId(), false).errorCode()).isEqualTo("PERMISSION_DENIED");
        sender = owner(); recipient = owner(); conversation = conversation(sender, recipient);
        UploadJobResponse withdrawn = uploads.enqueueMessage(sender, conversation, message(UUID.randomUUID()));
        UUID recipientId = recipient.getUserId();
        transactions.executeWithoutResult(status -> users.findById(recipientId).orElseThrow().withdraw());
        uploads.processNextJob();
        assertThat(uploads.get(sender, UploadJob.Kind.MESSAGE, withdrawn.jobId(), false).errorCode()).isEqualTo("PERMISSION_DENIED");
        verifyNoInteractions(realtime);
    }

    @Test void recipientLeavingReactivatesOnCompletionAndSenderSuspensionPreventsPost() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        UploadJobResponse accepted = uploads.enqueueMessage(sender, conversation, message(UUID.randomUUID()));
        messageService.leaveConversation(recipient, conversation);
        uploads.processNextJob();
        assertThat(uploads.get(sender, UploadJob.Kind.MESSAGE, accepted.jobId(), false).status()).isEqualTo("COMPLETED");
        assertThat(participants.findByConversationIdAndUserIdAndDeletedAtIsNull(conversation, recipient.getUserId())).isPresent();
        UploadJobResponse post = uploads.enqueuePost(sender, post(UUID.randomUUID()));
        transactions.executeWithoutResult(status -> users.findById(sender.getUserId()).orElseThrow().suspend());
        uploads.processNextJob();
        assertThat(jobs.findById(post.jobId()).orElseThrow().getErrorCode()).isEqualTo("PERMISSION_DENIED");
    }

    @Test void controllersKeepLegacy200AndAcceptOptIn202AndOwnerOnlyPolling() throws Exception {
        CustomUserPrincipal owner = owner();
        UUID key = UUID.randomUUID();
        String response = mvc.perform(multipart("/api/v1/posts").file(image("mediaFiles"))
                .param("asyncUpload", "true").param("clientRequestId", key.toString()).with(user(owner)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("jobId").asText();
        mvc.perform(get("/api/v1/posts/upload-jobs/" + id).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value(id));
        mvc.perform(get("/api/v1/posts/upload-jobs/by-client-id/" + key).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value(id));
        mvc.perform(get("/api/v1/posts/upload-jobs/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/posts/upload-jobs/" + id).with(user(owner()))).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/posts").file(image("mediaFiles")).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.postId").exists());
        mvc.perform(multipart("/api/v1/posts").file(image("mediaFiles")).param("asyncUpload", "true").with(user(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/posts").file(image("mediaFiles")).param("asyncUpload", "true")
                .param("clientRequestId", "invalid").with(user(owner))).andExpect(status().isBadRequest());
    }

    @Test void postVideoAndThumbnailAreNotPublishedUntilWorkerAndMetadataIsDeferred() throws Exception {
        CustomUserPrincipal owner = owner();
        CreateMultipartPostRequest request = post(UUID.randomUUID());
        request.setMediaFiles(List.of(video("mediaFiles")));
        request.setThumbnailFile(image("thumbnailFile"));
        long before = countFiles(root.resolve("media"));
        UploadJobResponse accepted = uploads.enqueuePost(owner, request);
        verifyNoInteractions(metadata, moderation);
        assertThat(countFiles(root.resolve("media"))).isEqualTo(before);
        when(metadata.readDurationSeconds(any())).thenReturn(120.0);
        uploads.processNextJob();
        UploadJobResponse done = uploads.get(owner, UploadJob.Kind.POST, accepted.jobId(), false);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.post().media()).hasSize(1);
        assertThat(done.post().media().getFirst().thumbnailUrl()).isNotNull();
        assertThat(countFiles(root.resolve("media"))).isEqualTo(before + 2);
        verify(metadata).readDurationSeconds(any());
        verify(moderation).assertPostAllowed(any(), argThat(files -> files.size() == 2));
        UploadJobResponse tooLong = uploads.enqueuePost(owner, requestWithNewKey(request));
        when(metadata.readDurationSeconds(any())).thenReturn(120.1);
        uploads.processNextJob();
        assertFailure(owner, tooLong.jobId(), "INVALID_UPLOAD");
    }

    @Test void dmVideoMetadataAndModerationRunOutsideRequestAndTransaction() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        SendMultipartMessageRequest request = message(UUID.randomUUID());
        request.setAttachments(List.of(video("attachments")));
        UploadJobResponse accepted = uploads.enqueueMessage(sender, conversation, request);
        verifyNoInteractions(metadata, moderation, realtime);
        when(metadata.readDurationSeconds(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return 10.0;
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return null;
        }).when(moderation).assertMessageAllowed(any(), anyList());
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            assertThat(worker.submit(uploads::processNextJob).get(10, TimeUnit.SECONDS)).isTrue();
        }
        UploadJobResponse done = uploads.get(sender, UploadJob.Kind.MESSAGE, accepted.jobId(), false);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.message().attachments()).hasSize(1);
        var attachment = done.message().attachments().getFirst();
        byte[] expectedVideo = request.getAttachments().getFirst().getBytes();
        mvc.perform(get(attachment.url()).with(user(recipient)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(expectedVideo));

        jdbc.update("UPDATE direct_message_attachments SET file_url = 'https://example.com' || file_url WHERE id = ?",
                attachment.id());
        mvc.perform(get(attachment.url()).with(user(recipient)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(expectedVideo));
    }

    @Test void rollbackRemovesPostOrMessageAndFilesAndDoesNotPublishEvents() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        long postCount = posts.count(), messageCount = messages.count(), notificationCount = notifications.count();
        long fileCount = countFiles(root.resolve("media"));
        UploadJobResponse post = uploads.enqueuePost(sender, post(UUID.randomUUID()));
        doThrow(new IllegalStateException("assemble failed")).when(postAssembler).toPostDetail(any(), any());
        uploads.processNextJob();
        assertFailure(sender, post.jobId(), "PROCESSING_FAILED");
        assertThat(posts.count()).isEqualTo(postCount);
        assertThat(countFiles(root.resolve("media"))).isEqualTo(fileCount);
        UUID conversation = conversation(sender, recipient);
        UploadJobResponse message = uploads.enqueueMessage(sender, conversation, message(UUID.randomUUID()));
        doThrow(new IllegalStateException("assemble failed")).when(messageAssembler).toMessage(any(), any(), anyList(), anyList());
        uploads.processNextJob();
        assertThat(uploads.get(sender, UploadJob.Kind.MESSAGE, message.jobId(), false).errorCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(messages.count()).isEqualTo(messageCount);
        assertThat(notifications.count()).isEqualTo(notificationCount);
        assertThat(countFiles(root.resolve("media"))).isEqualTo(fileCount);
        verifyNoInteractions(realtime);
    }

    @Test void afterCommitSseFailureKeepsCompletedMessageAndAttachment() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        UploadJobResponse accepted = uploads.enqueueMessage(sender, conversation, message(UUID.randomUUID()));
        long before = countFiles(root.resolve("media"));
        doThrow(new IllegalStateException("SSE unavailable")).when(realtime).publish(any(), any());
        uploads.processNextJob();
        UploadJobResponse done = uploads.get(sender, UploadJob.Kind.MESSAGE, accepted.jobId(), false);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.message()).isNotNull();
        assertThat(countFiles(root.resolve("media"))).isEqualTo(before + 1);
    }

    @Test void legacyDmKeyCannotBeReusedByAsyncAndDmControllersReturn202() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        UUID key = UUID.randomUUID();
        messageService.sendMessage(sender, conversation, new SendMessageRequest("legacy", null, key));
        assertStatus(409, () -> uploads.enqueueMessage(sender, conversation, message(key)));
        UUID asyncKey = UUID.randomUUID();
        String response = mvc.perform(multipart("/api/v1/messages/conversations/" + conversation + "/messages")
                .file(image("attachments")).param("asyncUpload", "true").param("clientMessageId", asyncKey.toString()).with(user(sender)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("jobId").asText();
        mvc.perform(get("/api/v1/messages/upload-jobs/by-client-id/" + asyncKey).with(user(sender)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value(id));
        mvc.perform(get("/api/v1/messages/upload-jobs/" + id).with(user(recipient))).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/messages/conversations/" + conversation + "/messages")
                .file(image("attachments")).with(user(sender))).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").exists());
    }

    @Test void completedDmDoesNotRestoreHistoryAfterSenderLeavesAndRejoins() throws Exception {
        CustomUserPrincipal sender = owner(), recipient = owner();
        UUID conversation = conversation(sender, recipient);
        SendMultipartMessageRequest request = message(UUID.randomUUID());
        UploadJobResponse accepted = uploads.enqueueMessage(sender, conversation, request);
        uploads.processNextJob();
        assertThat(uploads.get(sender, UploadJob.Kind.MESSAGE, accepted.jobId(), false).message()).isNotNull();
        messageService.leaveConversation(sender, conversation);
        assertThat(uploads.get(sender, UploadJob.Kind.MESSAGE, accepted.jobId(), false).message()).isNull();
        assertThat(conversation(sender, recipient)).isEqualTo(conversation);
        UploadJobResponse replayed = uploads.enqueueMessage(sender, conversation, request);
        assertThat(replayed.status()).isEqualTo("COMPLETED");
        assertThat(replayed.message()).isNull();
        assertThat(uploads.processNextJob()).isFalse();
    }

    @Test void interruptionPreservesInputAndRestartFinishesWithoutDuplicatePost() throws Exception {
        CustomUserPrincipal owner = owner();
        UploadJobResponse accepted = uploads.enqueuePost(owner, post(UUID.randomUUID()));
        long before = posts.count();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", new InterruptedException()))
                .when(moderation).assertPostAllowed(any(), anyList());
        try {
            uploads.processNextJob();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(uploads.get(owner, UploadJob.Kind.POST, accepted.jobId(), false).status()).isEqualTo("PROCESSING");
        assertThat(Files.exists(jobPath(accepted.jobId()).resolve("0"))).isTrue();
        uploads.recoverInterruptedJobs();
        doNothing().when(moderation).assertPostAllowed(any(), anyList());
        uploads.processNextJob();
        assertThat(uploads.get(owner, UploadJob.Kind.POST, accepted.jobId(), false).status()).isEqualTo("COMPLETED");
        assertThat(posts.count()).isEqualTo(before + 1);
        assertThat(Files.exists(jobPath(accepted.jobId()))).isFalse();
    }

    @Test void deletedCompletedPostReplayKeepsKeyAndDoesNotRecreateContent() throws Exception {
        CustomUserPrincipal owner = owner();
        CreateMultipartPostRequest request = post(UUID.randomUUID());
        UploadJobResponse accepted = uploads.enqueuePost(owner, request);
        uploads.processNextJob();
        UUID postId = uploads.get(owner, UploadJob.Kind.POST, accepted.jobId(), false).post().postId();
        transactions.executeWithoutResult(status -> posts.findById(postId).orElseThrow().softDelete());
        UploadJobResponse replayed = uploads.enqueuePost(owner, request);
        assertThat(replayed.jobId()).isEqualTo(accepted.jobId());
        assertThat(replayed.status()).isEqualTo("COMPLETED");
        assertThat(replayed.post()).isNull();
        assertThat(uploads.processNextJob()).isFalse();
    }

    private CreateMultipartPostRequest requestWithNewKey(CreateMultipartPostRequest request) {
        request.setClientRequestId(UUID.randomUUID()); return request;
    }
    private MockMultipartFile video(String field) {
        return new MockMultipartFile(field, "clip.mp4", "video/mp4", new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'});
    }
    private long countFiles(Path path) throws Exception {
        if (!Files.exists(path)) return 0;
        try (var files = Files.walk(path)) { return files.filter(Files::isRegularFile).count(); }
    }

    private CustomUserPrincipal owner() {
        String handle = "up" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = User.createLocal(handle + "@example.com", handle, "Upload", "hash");
        user.setProfile(UserProfile.createDefault(user));
        return CustomUserPrincipal.from(users.saveAndFlush(user));
    }
    private UUID conversation(CustomUserPrincipal sender, CustomUserPrincipal recipient) {
        return messageService.createConversation(sender, new CreateConversationRequest(null, recipient.getUserId())).id();
    }
    private CreateMultipartPostRequest post(UUID key) throws Exception {
        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setClientRequestId(key); request.setAsyncUpload(true); request.setContent("clip");
        request.setMediaFiles(List.of(image("mediaFiles"))); return request;
    }
    private SendMultipartMessageRequest message(UUID key) throws Exception {
        SendMultipartMessageRequest request = new SendMultipartMessageRequest();
        request.setClientMessageId(key); request.setAsyncUpload(true); request.setContent("image");
        request.setAttachments(List.of(image("attachments"))); return request;
    }
    private MockMultipartFile image(String field) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile(field, "image.png", "image/png", bytes.toByteArray());
    }
    private Path jobPath(UUID id) { return root.resolve("jobs/uploads").resolve(id.toString()); }
    private void assertFailure(CustomUserPrincipal owner, UUID id, String code) {
        UploadJobResponse failed = uploads.get(owner, UploadJob.Kind.POST, id, false);
        assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.errorCode()).isEqualTo(code);
        assertThat(failed.post()).isNull(); assertThat(failed.message()).isNull();
        assertThat(failed.errorMessage()).isNotBlank();
    }
    private void assertStatus(int code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(code));
    }
}
