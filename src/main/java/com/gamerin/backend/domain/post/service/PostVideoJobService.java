package com.gamerin.backend.domain.post.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
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

/**
 * 게시물 영상 비동기 처리. 업로드 요청에서는 작업만 저장하고(enqueue),
 * 워커가 재인코딩을 끝낸 뒤 게시물·해시태그·멘션을 만든다(processNextJob).
 */
@Service
public class PostVideoJobService {

    static final int MAX_ATTEMPTS = 2;
    private static final String JOB_NOT_FOUND_MESSAGE = "Video upload not found.";
    private static final String FAILED_MESSAGE = "영상을 처리할 수 없습니다. 다른 파일로 다시 시도해주세요.";
    private static final String TIMEOUT_MESSAGE = "영상 처리 시간이 초과됐습니다. 더 짧은 영상으로 다시 시도해주세요.";
    private static final String UNAVAILABLE_MESSAGE = "영상 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
    private static final String INPUT_MISSING_MESSAGE = "업로드한 영상 파일을 찾을 수 없습니다. 다시 업로드해주세요.";
    private static final String AUTHOR_MISSING_MESSAGE = "작성자 계정을 찾을 수 없습니다.";
    private static final Logger log = LoggerFactory.getLogger(PostVideoJobService.class);

    private final PostVideoJobRepository postVideoJobRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final HashtagService hashtagService;
    private final MentionService mentionService;
    private final MediaStorageService mediaStorageService;
    private final MediaUploadSecurityService mediaUploadSecurityService;
    private final VideoOptimizationService videoOptimizationService;
    private final VideoTemporaryStorageGuard temporaryStorageGuard;
    private final MembershipService membershipService;
    private final PostResponseAssembler postResponseAssembler;
    private final TransactionTemplate transactionTemplate;
    private final Path jobDirectory;

    public PostVideoJobService(
            PostVideoJobRepository postVideoJobRepository,
            UserRepository userRepository,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            HashtagService hashtagService,
            MentionService mentionService,
            MediaStorageService mediaStorageService,
            MediaUploadSecurityService mediaUploadSecurityService,
            VideoOptimizationService videoOptimizationService,
            VideoTemporaryStorageGuard temporaryStorageGuard,
            MembershipService membershipService,
            PostResponseAssembler postResponseAssembler,
            TransactionTemplate transactionTemplate,
            @Value("${app.media.video.processing.job-dir:${java.io.tmpdir}/gamerin-video-jobs}") String jobDirectory
    ) {
        this.postVideoJobRepository = postVideoJobRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.hashtagService = hashtagService;
        this.mentionService = mentionService;
        this.mediaStorageService = mediaStorageService;
        this.mediaUploadSecurityService = mediaUploadSecurityService;
        this.videoOptimizationService = videoOptimizationService;
        this.temporaryStorageGuard = temporaryStorageGuard;
        this.membershipService = membershipService;
        this.postResponseAssembler = postResponseAssembler;
        this.transactionTemplate = transactionTemplate;
        this.jobDirectory = Path.of(jobDirectory).toAbsolutePath().normalize();
    }

    /**
     * 검증·검열을 통과한 영상 업로드를 작업으로 저장한다. 호출한 트랜잭션이 롤백되면 저장한 파일도 지운다.
     */
    public PostVideoJobResponse enqueue(User author, String content, MultipartFile videoFile, MultipartFile thumbnailFile) {
        UUID jobId = UUID.randomUUID();
        VideoQualityTier tier = membershipService != null && membershipService.isActive(author.getId())
                ? VideoQualityTier.FHD
                : VideoQualityTier.HD;
        String extension = extractExtension(videoFile.getOriginalFilename());
        Path inputPath = jobDirectory.resolve(jobId + extension).normalize();
        MediaStorageService.StoredFile storedThumbnail = null;

        try {
            temporaryStorageGuard.ensureCapacity(videoFile.getSize());
            Files.createDirectories(jobDirectory);
            try (InputStream inputStream = videoFile.getInputStream()) {
                Files.copy(inputStream, inputPath, StandardCopyOption.REPLACE_EXISTING);
            }
            if (thumbnailFile != null) {
                storedThumbnail = mediaStorageService.storePostMedia(mediaUploadSecurityService.prepareImage(thumbnailFile));
            }

            PostVideoJob job = postVideoJobRepository.save(PostVideoJob.queue(
                    jobId,
                    author.getId(),
                    content,
                    tier,
                    inputPath.toString(),
                    extension,
                    storedThumbnail != null ? storedThumbnail.publicUrl() : null,
                    OffsetDateTime.now()));
            deleteFilesOnRollback(inputPath, storedThumbnail);
            return toResponse(job, null);
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(inputPath);
            mediaStorageService.deleteQuietly(storedThumbnail);
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload media files.", ex);
        }
    }

    public PostVideoJobResponse getJob(UUID userId, UUID jobId) {
        return transactionTemplate.execute(status -> {
            PostVideoJob job = postVideoJobRepository.findById(jobId)
                    .filter(found -> found.getUserId().equals(userId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, JOB_NOT_FOUND_MESSAGE));
            PostDetailResponse post = job.getStatus() == PostVideoJobStatus.COMPLETED && job.getPostId() != null
                    ? postRepository.findAccessibleById(job.getPostId())
                            .map(found -> postResponseAssembler.toPostDetail(found, userId))
                            .orElse(null)
                    : null;
            return toResponse(job, post);
        });
    }

    /**
     * 가장 오래된 대기 작업 하나를 처리한다. 처리할 작업이 없으면 false.
     */
    public boolean processNextJob() {
        Optional<PostVideoJob> claimed = Optional.ofNullable(transactionTemplate.execute(status -> {
            List<PostVideoJob> jobs = postVideoJobRepository.findOldestForUpdate(
                    PostVideoJobStatus.QUEUED, PageRequest.of(0, 1));
            if (jobs.isEmpty()) {
                return null;
            }
            PostVideoJob job = jobs.getFirst();
            job.start(OffsetDateTime.now());
            return job;
        }));
        claimed.ifPresent(this::process);
        return claimed.isPresent();
    }

    /**
     * 서버가 처리 도중 멈춰 PROCESSING으로 남은 작업을 다시 대기열에 넣는다. 이미 여러 번 시도했으면 실패 처리한다.
     */
    public void recoverInterruptedJobs() {
        List<PostVideoJob> abandoned = transactionTemplate.execute(status -> {
            List<PostVideoJob> failed = new java.util.ArrayList<>();
            for (PostVideoJob job : postVideoJobRepository.findByStatus(PostVideoJobStatus.PROCESSING)) {
                if (job.getAttemptCount() >= MAX_ATTEMPTS) {
                    job.fail(UNAVAILABLE_MESSAGE, OffsetDateTime.now());
                    failed.add(job);
                } else {
                    job.requeue();
                }
            }
            return failed;
        });
        if (abandoned != null) {
            abandoned.forEach(this::deleteJobFiles);
        }
    }

    private void process(PostVideoJob job) {
        Path inputPath = Path.of(job.getInputPath());
        Path outputPath = null;
        MediaStorageService.StoredFile storedVideo = null;
        // 완료·실패 상태가 커밋됐을 때만 원본을 지운다. 그 전에 끊기면 재시작 복구 때 다시 처리한다.
        boolean finalized = false;

        try {
            if (!Files.exists(inputPath)) {
                finalized = markFailed(job, INPUT_MISSING_MESSAGE);
                return;
            }

            MediaStorageService.PreparedMediaPath prepared;
            if (videoOptimizationService.isEnabled()) {
                Files.createDirectories(jobDirectory);
                outputPath = Files.createTempFile(jobDirectory, "output-", ".mp4");
                videoOptimizationService.transcodeForPost(inputPath, outputPath, job.getQualityTier());
                prepared = new MediaStorageService.PreparedMediaPath(outputPath, ".mp4");
            } else {
                prepared = new MediaStorageService.PreparedMediaPath(inputPath, job.getInputExtension());
            }
            storedVideo = mediaStorageService.storePostMedia(prepared);

            MediaStorageService.StoredFile video = storedVideo;
            boolean created = Boolean.TRUE.equals(transactionTemplate.execute(status -> createPost(job.getId(), video)));
            finalized = true;
            if (!created) {
                mediaStorageService.deleteQuietly(storedVideo);
            }
        } catch (FfmpegProcessException ex) {
            if (ex.failureType() == FailureType.INTERRUPTED) {
                // 서버 종료로 끊긴 작업은 입력을 남겨 두고 재시작 후 다시 처리한다.
                log.warn("Post video job {} was interrupted; it will be retried after restart", job.getId());
                return;
            }
            log.warn("Post video job {} failed ({}): {}", job.getId(), ex.failureType(), ex.output());
            mediaStorageService.deleteQuietly(storedVideo);
            finalized = markFailed(job, ex.failureType() == FailureType.TIMEOUT
                    ? TIMEOUT_MESSAGE
                    : ex.failureType() == FailureType.COMMAND_FAILED ? FAILED_MESSAGE : UNAVAILABLE_MESSAGE);
        } catch (IOException | RuntimeException ex) {
            log.error("Post video job {} failed", job.getId(), ex);
            mediaStorageService.deleteQuietly(storedVideo);
            finalized = markFailed(job, ex instanceof ResponseStatusException ? FAILED_MESSAGE : UNAVAILABLE_MESSAGE);
        } finally {
            deleteQuietly(outputPath);
            if (finalized) {
                deleteQuietly(inputPath);
            }
        }
    }

    private boolean createPost(UUID jobId, MediaStorageService.StoredFile storedVideo) {
        PostVideoJob job = postVideoJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != PostVideoJobStatus.PROCESSING) {
            return false;
        }
        User author = userRepository.findByIdAndDeletedAtIsNull(job.getUserId()).orElse(null);
        if (author == null) {
            job.fail(AUTHOR_MISSING_MESSAGE, OffsetDateTime.now());
            mediaStorageService.deletePublicUrlQuietly(job.getThumbnailUrl());
            return false;
        }

        Post post = postRepository.save(Post.create(author, job.getContent()));
        hashtagService.attachToPost(post);
        mentionService.attachToPost(post);
        postMediaRepository.save(PostMedia.create(
                post,
                PostMediaType.VIDEO,
                storedVideo.publicUrl(),
                job.getThumbnailUrl(),
                0));
        job.complete(post.getId(), OffsetDateTime.now());
        return true;
    }

    /** 실패 상태를 저장한다. DB 오류로 저장하지 못하면 false를 돌려 원본·썸네일을 남긴다. */
    private boolean markFailed(PostVideoJob job, String message) {
        try {
            transactionTemplate.executeWithoutResult(status -> postVideoJobRepository.findById(job.getId())
                    .ifPresent(found -> found.fail(message, OffsetDateTime.now())));
        } catch (RuntimeException ex) {
            log.error("Could not mark post video job {} as failed; it will be retried after restart", job.getId(), ex);
            return false;
        }
        mediaStorageService.deletePublicUrlQuietly(job.getThumbnailUrl());
        return true;
    }

    private void deleteJobFiles(PostVideoJob job) {
        deleteQuietly(Path.of(job.getInputPath()));
        mediaStorageService.deletePublicUrlQuietly(job.getThumbnailUrl());
    }

    private void deleteFilesOnRollback(Path inputPath, MediaStorageService.StoredFile storedThumbnail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // STATUS_UNKNOWN은 커밋됐을 수 있으므로 파일을 유지한다.
                if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(inputPath);
                    mediaStorageService.deleteQuietly(storedThumbnail);
                }
            }
        });
    }

    private PostVideoJobResponse toResponse(PostVideoJob job, PostDetailResponse post) {
        return new PostVideoJobResponse(job.getId(), job.getStatus().name(), job.getErrorMessage(), post);
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return ".video";
        }
        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == originalFilename.length() - 1) {
            return ".video";
        }
        String extension = originalFilename.substring(extensionIndex).toLowerCase(Locale.ROOT);
        // 확장자는 검증 단계에서 mp4·mov·m4v로 제한되지만, 경로에 쓰므로 한 번 더 좁힌다.
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : ".video";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 작업 임시 파일 정리는 최선 노력으로 한다.
        }
    }
}
