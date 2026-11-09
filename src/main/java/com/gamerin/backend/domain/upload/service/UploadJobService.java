package com.gamerin.backend.domain.upload.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.message.dto.request.SendMultipartMessageRequest;
import com.gamerin.backend.domain.message.dto.response.MessageResponse;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner;
import com.gamerin.backend.domain.post.service.PostResponseAssembler;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.post.service.VideoTemporaryStorageGuard;
import com.gamerin.backend.domain.upload.dto.response.UploadJobResponse;
import com.gamerin.backend.domain.upload.entity.UploadJob;
import com.gamerin.backend.domain.upload.repository.UploadJobRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
public class UploadJobService {
    private static final Logger log = LoggerFactory.getLogger(UploadJobService.class);
    private static final List<PostVideoJobStatus> TERMINAL = List.of(PostVideoJobStatus.COMPLETED, PostVideoJobStatus.FAILED);
    private final UploadJobRepository jobs;
    private final UserRepository users;
    private final PostRepository posts;
    private final DirectMessageRepository messages;
    private final PostService postService;
    private final PostResponseAssembler postAssembler;
    private final MessageService messageService;
    private final VideoTemporaryStorageGuard storageGuard;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Path directory;

    public UploadJobService(UploadJobRepository jobs, UserRepository users, PostRepository posts,
            DirectMessageRepository messages, PostService postService, PostResponseAssembler postAssembler,
            MessageService messageService, VideoTemporaryStorageGuard storageGuard, TransactionTemplate transactions,
            ObjectMapper json,
            @Value("${app.media.video.processing.job-dir:${java.io.tmpdir}/gamerin-video-jobs}") String directory) {
        this.jobs = jobs;
        this.users = users;
        this.posts = posts;
        this.messages = messages;
        this.postService = postService;
        this.postAssembler = postAssembler;
        this.messageService = messageService;
        this.storageGuard = storageGuard;
        this.transactions = transactions;
        this.json = json;
        this.directory = Path.of(directory).toAbsolutePath().normalize().resolve("uploads");
    }

    public UploadJobResponse enqueuePost(CustomUserPrincipal principal, CreateMultipartPostRequest request) {
        return enqueue(principal, UploadJob.Kind.POST, request.getClientRequestId(), request.getContent(), null, null,
                request.getMediaFiles(), request.getThumbnailFile(),
                () -> postService.validateUploadAdmission(principal, request));
    }

    public UploadJobResponse enqueueMessage(CustomUserPrincipal principal, UUID conversationId, SendMultipartMessageRequest request) {
        return enqueue(principal, UploadJob.Kind.MESSAGE, request.getClientMessageId(), request.getContent(),
                conversationId, request.getSharedPostId(), request.getAttachments(), null,
                () -> messageService.validateUploadAdmission(principal, conversationId, request));
    }

    private UploadJobResponse enqueue(CustomUserPrincipal principal, UploadJob.Kind kind, UUID key, String content,
            UUID conversationId, UUID sharedPostId, List<MultipartFile> files, MultipartFile thumbnail, Runnable validate) {
        if (key == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Async upload requires a client UUID.");
        User user = transactions.execute(status -> currentUser(principal, false));
        // 접수 검증은 DB 행 잠금 밖에서 실행하며, 완료 직전에도 도메인 서비스가 권한을 다시 확인한다.
        boolean alreadyAccepted = transactions.execute(status -> jobs.existsByUserIdAndKindAndClientKey(user.getId(), kind, key));
        if (!alreadyAccepted) validate.run();
        UUID id = UUID.randomUUID();
        Path path = directory.resolve(id.toString());
        boolean[] retained = {false};
        try {
            List<MultipartFile> nonempty = files == null ? List.of()
                    : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
            long total = nonempty.stream().mapToLong(MultipartFile::getSize).sum()
                    + (thumbnail == null ? 0 : thumbnail.getSize());
            storageGuard.ensureCapacity(total);
            // shortcut: 파일과 DB 사이 강제 종료 시 고아가 남을 수 있으므로, 자동 정리가 필요해지면 작업 대조 정리를 추가한다.
            Files.createDirectories(path);
            List<UploadPayload.Part> parts = new ArrayList<>();
            for (int i = 0; i < nonempty.size(); i++) parts.add(store(path, Integer.toString(i), nonempty.get(i)));
            UploadPayload.Part cover = thumbnail == null || thumbnail.isEmpty() ? null : store(path, "thumbnail", thumbnail);
            String payload = json.writeValueAsString(new UploadPayload(content, conversationId, sharedPostId, parts, cover));
            String fingerprint = HexFormat.of().formatHex(digest().digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return transactions.execute(status -> {
                currentUser(principal, true);
                Optional<UploadJob> existing = jobs.findByUserIdAndKindAndClientKey(user.getId(), kind, key);
                if (existing.isPresent()) {
                    if (!existing.get().getFingerprint().equals(fingerprint)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Client upload id is already used for a different request.");
                    }
                    return response(existing.get());
                }
                if (kind == UploadJob.Kind.MESSAGE && messages.findBySenderIdAndClientMessageId(user.getId(), key).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Client message id is already used.");
                }
                UploadJob saved = jobs.saveAndFlush(UploadJob.queue(id, user.getId(), kind, key, fingerprint, payload));
                // UNKNOWN일 때는 커밋됐을 수 있어 입력을 보존한다. 명시적 롤백만 지운다.
                retained[0] = true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) deleteDirectory(path);
                    }
                });
                return response(saved);
            });
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store upload.", ex);
        } finally {
            if (!retained[0]) deleteDirectory(path);
        }
    }

    public UploadJobResponse get(CustomUserPrincipal principal, UploadJob.Kind kind, UUID id, boolean byClientKey) {
        return transactions.execute(status -> {
            User user = currentUser(principal, false);
            UploadJob job = (byClientKey ? jobs.findByUserIdAndKindAndClientKey(user.getId(), kind, id) : jobs.findById(id))
                    .filter(found -> found.getKind() == kind && found.getUserId().equals(user.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload job not found."));
            return response(job);
        });
    }

    private UploadJobResponse response(UploadJob job) {
        PostDetailResponse post = null;
        MessageResponse message = null;
        if (job.getStatus() == PostVideoJobStatus.COMPLETED && job.getResultId() != null) {
            if (job.getKind() == UploadJob.Kind.POST) {
                post = posts.findAccessibleById(job.getResultId())
                        .map(found -> postAssembler.toPostDetail(found, job.getUserId())).orElse(null);
            } else {
                message = messageService.getUploadMessage(job.getUserId(), job.getResultId());
            }
        }
        return new UploadJobResponse(job.getId(), job.getStatus().name(), job.getErrorCode(), job.getErrorMessage(), post, message);
    }

    public boolean processNextJob() {
        UploadJob job = transactions.execute(status -> {
            List<UploadJob> queued = jobs.findOldestForUpdate(PostVideoJobStatus.QUEUED, PageRequest.of(0, 1));
            if (queued.isEmpty()) return null;
            UploadJob next = queued.getFirst();
            next.start();
            return next;
        });
        if (job == null) return false;
        process(job);
        return true;
    }

    private void process(UploadJob job) {
        try {
            UploadPayload payload = json.readValue(job.getPayload(), UploadPayload.class);
            Path path = directory.resolve(job.getId().toString());
            boolean missing = payload.files().stream().anyMatch(part -> !Files.isRegularFile(path.resolve(part.storedName())))
                    || payload.thumbnail() != null && !Files.isRegularFile(path.resolve(payload.thumbnail().storedName()));
            if (missing) { fail(job, "INPUT_MISSING", "업로드 파일을 찾을 수 없습니다. 다시 선택해주세요."); return; }
            CustomUserPrincipal principal = transactions.execute(status -> {
                User user = users.findByIdAndDeletedAtIsNull(job.getUserId())
                        .filter(User::isActive).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
                return CustomUserPrincipal.from(user);
            });
            if (job.getKind() == UploadJob.Kind.POST) {
                CreateMultipartPostRequest request = new CreateMultipartPostRequest();
                request.setContent(payload.content());
                request.setMediaFiles(payload.files().stream().map(part -> part.open(path)).toList());
                request.setThumbnailFile(payload.thumbnail() == null ? null : payload.thumbnail().open(path));
                postService.createProcessedUpload(principal, request, result -> complete(job.getId(), result));
            } else {
                SendMultipartMessageRequest request = new SendMultipartMessageRequest();
                request.setContent(payload.content());
                request.setSharedPostId(payload.sharedPostId());
                request.setClientMessageId(job.getClientKey());
                request.setAttachments(payload.files().stream().map(part -> part.open(path)).toList());
                messageService.sendProcessedUpload(principal, payload.conversationId(), request, result -> complete(job.getId(), result));
            }
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted() || isInterrupted(ex)) {
                Thread.currentThread().interrupt();
                return;
            }
            if (ex instanceof ResponseStatusException status && status.getStatusCode().value() == 422) {
                fail(job, "MODERATION_REJECTED", "콘텐츠 검열 정책에 따라 업로드가 거부됐습니다.");
            } else if (ex instanceof ResponseStatusException status && List.of(401, 403, 404).contains(status.getStatusCode().value())) {
                fail(job, "PERMISSION_DENIED", "계정 또는 대화 접근권한이 변경되어 전송할 수 없습니다.");
            } else if (ex instanceof ResponseStatusException status && status.getStatusCode().value() == 400) {
                fail(job, "INVALID_UPLOAD", "파일을 처리할 수 없습니다. 형식과 길이를 확인해주세요.");
            } else {
                log.error("Upload job {} failed ({})", job.getId(), ex.getClass().getSimpleName());
                fail(job, "PROCESSING_FAILED", "업로드 처리 중 오류가 발생했습니다. 다시 시도해주세요.");
            }
        } finally {
            cleanTerminal(job.getId());
        }
    }

    private void complete(UUID id, UUID result) {
        // 도메인의 생성 트랜잭션에 참여하므로 콘텐츠·알림·완료 상태가 함께 커밋된다.
        UploadJob job = jobs.findForUpdate(id).orElseThrow();
        if (job.getStatus() != PostVideoJobStatus.PROCESSING) throw new IllegalStateException("Upload is no longer processing.");
        job.complete(result);
    }

    private void fail(UploadJob job, String code, String message) {
        transactions.executeWithoutResult(status -> jobs.findForUpdate(job.getId()).ifPresent(found -> {
            if (found.getStatus() == PostVideoJobStatus.PROCESSING) found.fail(code, message);
        }));
    }

    /** 단일 백엔드 인스턴스에서 첫 워커 실행 전에 한 번 복구한다. */
    public void recoverInterruptedJobs() {
        transactions.executeWithoutResult(status -> {
            for (UploadJob job : jobs.findByStatus(PostVideoJobStatus.PROCESSING)) {
                if (job.getAttemptCount() >= 2) job.fail("PROCESSING_FAILED", "처리가 반복 중단됐습니다. 다시 업로드해주세요.");
                else job.requeue();
            }
        });
        cleanTerminalFiles();
    }

    public void cleanTerminalFiles() {
        List<UUID> ids = transactions.execute(status -> jobs.findByStatusInAndPayloadIsNotNull(TERMINAL, PageRequest.of(0, 100))
                .stream().map(UploadJob::getId).toList());
        if (ids != null) ids.forEach(this::cleanTerminal);
    }

    private void cleanTerminal(UUID id) {
        try {
            transactions.executeWithoutResult(status -> jobs.findForUpdate(id).ifPresent(job -> {
                if (TERMINAL.contains(job.getStatus()) && job.getPayload() != null && deleteDirectory(directory.resolve(id.toString()))) {
                    job.clearPayload();
                }
            }));
        } catch (RuntimeException ex) {
            // 최종 상태가 커밋되지 않았으면 입력을 남겨 다음 복구 때 사용한다.
            log.warn("Could not clean upload job {}", id);
        }
    }

    private User currentUser(CustomUserPrincipal principal, boolean lock) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        User user = (lock ? users.findActiveByIdForUpdate(principal.getUserId()) : users.findByIdAndDeletedAtIsNull(principal.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
        if (!user.isActive()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not active.");
        return user;
    }

    private UploadPayload.Part store(Path path, String name, MultipartFile file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream stream = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(stream, path.resolve(name));
        }
        return new UploadPayload.Part(name, file.getOriginalFilename(), file.getContentType(), file.getSize(),
                HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static boolean isInterrupted(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return true;
            if (cause instanceof FfmpegProcessRunner.FfmpegProcessException ffmpeg
                    && ffmpeg.failureType() == FfmpegProcessRunner.FailureType.INTERRUPTED) return true;
        }
        return false;
    }

    private boolean deleteDirectory(Path path) {
        if (!Files.exists(path)) return true;
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            return true;
        } catch (IOException ex) {
            log.warn("Could not remove files for upload {}", path.getFileName());
            return false;
        }
    }
}
