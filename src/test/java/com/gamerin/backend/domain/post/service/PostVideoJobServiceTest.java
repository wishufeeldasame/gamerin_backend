package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.hashtag.service.HashtagService;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.mention.service.MentionService;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.dto.response.PostVideoJobResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.entity.PostVideoJob;
import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;
import com.gamerin.backend.domain.post.entity.VideoQualityTier;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.repository.PostVideoJobRepository;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FailureType;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FfmpegProcessException;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class PostVideoJobServiceTest {

    private static final String THUMBNAIL_URL = "/uploads/post-media/thumb.jpg";

    @Mock private PostVideoJobRepository jobRepository;
    @Mock private UserRepository userRepository;
    @Mock private PostRepository postRepository;
    @Mock private PostMediaRepository postMediaRepository;
    @Mock private HashtagService hashtagService;
    @Mock private MentionService mentionService;
    @Mock private MediaStorageService mediaStorageService;
    @Mock private MediaUploadSecurityService mediaUploadSecurityService;
    @Mock private VideoOptimizationService videoOptimizationService;
    @Mock private VideoTemporaryStorageGuard storageGuard;
    @Mock private MembershipService membershipService;
    @Mock private PostResponseAssembler postResponseAssembler;

    @TempDir
    Path jobDir;

    private PostVideoJobService service;
    private User author;

    @BeforeEach
    void setUp() {
        service = new PostVideoJobService(
                jobRepository, userRepository, postRepository, postMediaRepository, hashtagService, mentionService,
                mediaStorageService, mediaUploadSecurityService, videoOptimizationService, storageGuard,
                membershipService, postResponseAssembler, new TransactionTemplate(new NoOpTransactionManager()),
                jobDir.toString());
        author = User.createLocal("tester@example.com", "tester", "Tester", "hash");
        ReflectionTestUtils.setField(author, "id", UUID.randomUUID());
    }

    @Test
    void enqueueStoresInputAndThumbnailWithMembershipTier() throws Exception {
        MockMultipartFile video = new MockMultipartFile("mediaFiles", "clip.MOV", "video/quicktime", "video".getBytes());
        MockMultipartFile thumbnail = new MockMultipartFile("thumbnailFile", "t.jpg", "image/jpeg", "img".getBytes());
        MediaStorageService.PreparedMediaFile preparedThumbnail =
                new MediaStorageService.PreparedMediaFile("img".getBytes(), ".jpg");
        when(membershipService.isActive(author.getId())).thenReturn(true);
        when(mediaUploadSecurityService.prepareImage(thumbnail)).thenReturn(preparedThumbnail);
        when(mediaStorageService.storePostMedia(preparedThumbnail))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("thumb.jpg"), THUMBNAIL_URL));
        when(jobRepository.save(any(PostVideoJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PostVideoJobResponse response = service.enqueue(author, "hello", video, thumbnail);

        ArgumentCaptor<PostVideoJob> saved = ArgumentCaptor.forClass(PostVideoJob.class);
        verify(jobRepository).save(saved.capture());
        PostVideoJob job = saved.getValue();
        assertThat(response.jobId()).isEqualTo(job.getId());
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(job.getQualityTier()).isEqualTo(VideoQualityTier.FHD);
        assertThat(job.getThumbnailUrl()).isEqualTo(THUMBNAIL_URL);
        assertThat(job.getInputExtension()).isEqualTo(".mov");
        assertThat(Path.of(job.getInputPath())).hasParent(jobDir).hasContent("video");
        verify(storageGuard).ensureCapacity(video.getSize());
    }

    @Test
    void enqueueUsesHdForNonMembersAndCleansUpWhenSaveFails() {
        MockMultipartFile video = new MockMultipartFile("mediaFiles", "clip.mp4", "video/mp4", "video".getBytes());
        when(membershipService.isActive(author.getId())).thenReturn(false);
        when(jobRepository.save(any(PostVideoJob.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.enqueue(author, null, video, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(jobDir).isEmptyDirectory();
    }

    @Test
    void processNextJobTranscodesThenCreatesPostWithMedia() throws Exception {
        PostVideoJob job = queuedJob(VideoQualityTier.HD);
        Path input = Path.of(job.getInputPath());
        UUID postId = UUID.randomUUID();
        when(jobRepository.findOldestForUpdate(eq(PostVideoJobStatus.QUEUED), any())).thenReturn(List.of(job));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoOptimizationService.isEnabled()).thenReturn(true);
        when(mediaStorageService.storePostMedia(any(MediaStorageService.PreparedMediaPath.class)))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("v.mp4"), "/uploads/post-media/v.mp4"));
        when(userRepository.findByIdAndDeletedAtIsNull(author.getId())).thenReturn(Optional.of(author));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            ReflectionTestUtils.setField(post, "id", postId);
            return post;
        });

        assertThat(service.processNextJob()).isTrue();

        verify(videoOptimizationService).transcodeForPost(eq(input), any(Path.class), eq(VideoQualityTier.HD));
        ArgumentCaptor<PostMedia> media = ArgumentCaptor.forClass(PostMedia.class);
        verify(postMediaRepository).save(media.capture());
        assertThat(media.getValue().getMediaType()).isEqualTo(PostMediaType.VIDEO);
        assertThat(media.getValue().getMediaUrl()).isEqualTo("/uploads/post-media/v.mp4");
        assertThat(media.getValue().getThumbnailUrl()).isEqualTo(THUMBNAIL_URL);
        verify(hashtagService).attachToPost(any(Post.class));
        verify(mentionService).attachToPost(any(Post.class));
        assertThat(job.getStatus()).isEqualTo(PostVideoJobStatus.COMPLETED);
        assertThat(job.getPostId()).isEqualTo(postId);
        verify(postRepository).save(org.mockito.ArgumentMatchers.argThat(
                (Post post) -> "hello #tag".equals(post.getContent())));
        assertThat(job.getContent()).isNull();
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(input).doesNotExist();
        try (var files = Files.list(jobDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void processNextJobMarksFailureAndCleansUpWhenTranscodeFails() throws Exception {
        PostVideoJob job = queuedJob(VideoQualityTier.HD);
        when(jobRepository.findOldestForUpdate(eq(PostVideoJobStatus.QUEUED), any())).thenReturn(List.of(job));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoOptimizationService.isEnabled()).thenReturn(true);
        doThrow(new FfmpegProcessException(FailureType.COMMAND_FAILED, 1, "bad codec", null))
                .when(videoOptimizationService).transcodeForPost(any(), any(), any());

        assertThat(service.processNextJob()).isTrue();

        assertThat(job.getStatus()).isEqualTo(PostVideoJobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("영상을 처리할 수 없습니다");
        assertThat(job.getContent()).isNull();
        assertThat(Path.of(job.getInputPath())).doesNotExist();
        verify(mediaStorageService).deletePublicUrlQuietly(THUMBNAIL_URL);
        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void processNextJobKeepsInputWhenInterruptedByShutdown() throws Exception {
        PostVideoJob job = queuedJob(VideoQualityTier.FHD);
        when(jobRepository.findOldestForUpdate(eq(PostVideoJobStatus.QUEUED), any())).thenReturn(List.of(job));
        when(videoOptimizationService.isEnabled()).thenReturn(true);
        doThrow(new FfmpegProcessException(FailureType.INTERRUPTED, -1, "", null))
                .when(videoOptimizationService).transcodeForPost(any(), any(), any());

        service.processNextJob();

        assertThat(job.getStatus()).isEqualTo(PostVideoJobStatus.PROCESSING);
        assertThat(Path.of(job.getInputPath())).exists();
        verify(mediaStorageService, never()).deletePublicUrlQuietly(any());
    }

    @Test
    void processNextJobKeepsInputWhenFinalStateCannotBeSaved() throws Exception {
        PostVideoJob job = queuedJob(VideoQualityTier.HD);
        when(jobRepository.findOldestForUpdate(eq(PostVideoJobStatus.QUEUED), any())).thenReturn(List.of(job));
        when(videoOptimizationService.isEnabled()).thenReturn(true);
        when(mediaStorageService.storePostMedia(any(MediaStorageService.PreparedMediaPath.class)))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("v.mp4"), "/uploads/post-media/v.mp4"));
        // 게시물 생성과 실패 기록 모두 DB 오류로 저장하지 못한다.
        when(jobRepository.findById(job.getId())).thenThrow(new IllegalStateException("db down"));

        service.processNextJob();

        assertThat(Path.of(job.getInputPath())).exists();
        verify(mediaStorageService).deleteQuietly(any(MediaStorageService.StoredFile.class));
        verify(mediaStorageService, never()).deletePublicUrlQuietly(any());
    }

    @Test
    void processNextJobReturnsFalseWhenQueueIsEmpty() {
        when(jobRepository.findOldestForUpdate(eq(PostVideoJobStatus.QUEUED), any())).thenReturn(List.of());

        assertThat(service.processNextJob()).isFalse();
    }

    @Test
    void recoverRequeuesInterruptedJobsAndFailsRepeatedOnes() throws Exception {
        PostVideoJob retryable = queuedJob(VideoQualityTier.HD);
        retryable.start(OffsetDateTime.now());
        PostVideoJob exhausted = queuedJob(VideoQualityTier.HD);
        exhausted.start(OffsetDateTime.now());
        exhausted.start(OffsetDateTime.now());
        when(jobRepository.findByStatus(PostVideoJobStatus.PROCESSING)).thenReturn(List.of(retryable, exhausted));

        service.recoverInterruptedJobs();

        assertThat(retryable.getStatus()).isEqualTo(PostVideoJobStatus.QUEUED);
        assertThat(Path.of(retryable.getInputPath())).exists();
        assertThat(exhausted.getStatus()).isEqualTo(PostVideoJobStatus.FAILED);
        assertThat(Path.of(exhausted.getInputPath())).doesNotExist();
    }

    @Test
    void getJobHidesOtherUsersJobsAndReturnsCreatedPost() throws Exception {
        PostVideoJob job = queuedJob(VideoQualityTier.HD);
        UUID postId = UUID.randomUUID();
        job.start(OffsetDateTime.now());
        job.complete(postId, OffsetDateTime.now());
        Post post = Post.create(author, "hello");
        PostDetailResponse detail = org.mockito.Mockito.mock(PostDetailResponse.class);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(postRepository.findAccessibleById(postId)).thenReturn(Optional.of(post));
        when(postResponseAssembler.toPostDetail(post, author.getId())).thenReturn(detail);

        assertThat(service.getJob(author.getId(), job.getId()).post()).isSameAs(detail);
        assertThatThrownBy(() -> service.getJob(UUID.randomUUID(), job.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private PostVideoJob queuedJob(VideoQualityTier tier) throws Exception {
        UUID id = UUID.randomUUID();
        Path input = Files.writeString(jobDir.resolve(id + ".mp4"), "video");
        return PostVideoJob.queue(id, author.getId(), "hello #tag", tier, input.toString(), ".mp4",
                THUMBNAIL_URL, OffsetDateTime.now());
    }

    /** 실제 트랜잭션 없이 TransactionTemplate 콜백만 실행한다. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
