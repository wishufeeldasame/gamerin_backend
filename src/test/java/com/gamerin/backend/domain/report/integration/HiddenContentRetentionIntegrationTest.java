package com.gamerin.backend.domain.report.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostCleanupService;
import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.service.ReportService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

// 숨김 콘텐츠 14일 보존, 댓글의 글 추종, 댓글 수·신고 횟수 처리(#78)
@SpringBootTest(properties = "app.post.cleanup.initial-delay-ms=3600000")
class HiddenContentRetentionIntegrationTest {

    // AUTO_HIDE_THRESHOLD 미설정 시 기본값
    private static final int AUTO_HIDE_THRESHOLD = 5;

    @Autowired
    private ReportService reportService;
    @Autowired
    private PostCleanupService postCleanupService;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostCommentRepository postCommentRepository;
    @Autowired
    private ReportCountRepository reportCountRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (UUID userId : userIds) {
            jdbcTemplate.update("delete from admin_audit_logs where admin_id = ?", userId);
            jdbcTemplate.update("delete from reports where reporter_id = ?", userId);
            jdbcTemplate.update("""
                    delete from report_counts where target_id in (
                        select id from posts where author_id = ?
                        union select id from post_comments where author_id = ?)
                    """, userId, userId);
            jdbcTemplate.update("delete from post_comments where author_id = ?", userId);
            jdbcTemplate.update("delete from posts where author_id = ?", userId);
        }
        userRepository.deleteAllById(userIds);
        userIds.clear();
    }

    @Test
    void hiddenPostIsKeptPastUserRetentionAndPurgedWithCommentsAfterHiddenRetention() {
        User author = saveUser("author");
        Post post = postRepository.saveAndFlush(Post.create(author, "숨길 글"));
        PostComment comment = postCommentRepository.saveAndFlush(PostComment.create(post, author, "댓글"));
        reportEnough(ReportTargetType.COMMENT, comment.getId());
        reportEnough(ReportTargetType.POST, post.getId());

        setDeletedAt("posts", post.getId(), OffsetDateTime.now().minusDays(2));
        postCleanupService.purgeExpiredSoftDeletedPosts();
        assertThat(postRepository.findById(post.getId())).isPresent();

        setDeletedAt("posts", post.getId(), OffsetDateTime.now().minusDays(15));
        postCleanupService.purgeExpiredSoftDeletedPosts();

        assertThat(postRepository.findById(post.getId())).isEmpty();
        assertThat(postCommentRepository.findById(comment.getId())).isEmpty();
        assertThat(reportCountRepository.existsByTargetTypeAndTargetId(ReportTargetType.POST, post.getId())).isFalse();
        assertThat(reportCountRepository.existsByTargetTypeAndTargetId(ReportTargetType.COMMENT, comment.getId()))
                .isFalse();
    }

    @Test
    void userDeletedPostIsPurgedAfterUserRetention() {
        User author = saveUser("author");
        Post post = postRepository.saveAndFlush(Post.create(author, "지울 글"));
        PostComment comment = postCommentRepository.saveAndFlush(PostComment.create(post, author, "댓글"));
        setDeletedAt("posts", post.getId(), OffsetDateTime.now().minusDays(2));

        postCleanupService.purgeExpiredSoftDeletedPosts();

        assertThat(postRepository.findById(post.getId())).isEmpty();
        assertThat(postCommentRepository.findById(comment.getId())).isEmpty();
    }

    @Test
    void hiddenCommentUpdatesCountResetsReportsOnRestoreAndIsPurgedAfterHiddenRetention() {
        User author = saveUser("author");
        User admin = saveUser("admin");
        Post post = postRepository.saveAndFlush(Post.create(author, "글"));
        PostComment comment = postCommentRepository.saveAndFlush(PostComment.create(post, author, "댓글"));
        transactionTemplate.executeWithoutResult(status -> postRepository.increaseCommentCount(post.getId()));

        reportEnough(ReportTargetType.COMMENT, comment.getId());
        assertThat(commentCount(post)).isZero();

        reportService.restoreHiddenContent(ReportTargetType.COMMENT, comment.getId(), admin.getId());
        ReportCount restored = reportCount(ReportTargetType.COMMENT, comment.getId());
        assertThat(restored.isHidden()).isFalse();
        assertThat(restored.getReportCount()).isZero();
        assertThat(commentCount(post)).isEqualTo(1);

        // 기존 누적분이 초기화돼 신고 1건으로는 다시 숨겨지지 않는다.
        report(saveUser("reporter"), ReportTargetType.COMMENT, comment.getId());
        assertThat(reportCount(ReportTargetType.COMMENT, comment.getId()).isHidden()).isFalse();

        for (int i = 1; i < AUTO_HIDE_THRESHOLD; i++) {
            report(saveUser("reporter"), ReportTargetType.COMMENT, comment.getId());
        }
        assertThat(reportCount(ReportTargetType.COMMENT, comment.getId()).isHidden()).isTrue();

        setDeletedAt("post_comments", comment.getId(), OffsetDateTime.now().minusDays(2));
        postCleanupService.purgeExpiredSoftDeletedPosts();
        assertThat(postCommentRepository.findById(comment.getId())).isPresent();

        setDeletedAt("post_comments", comment.getId(), OffsetDateTime.now().minusDays(15));
        postCleanupService.purgeExpiredSoftDeletedPosts();
        assertThat(postCommentRepository.findById(comment.getId())).isEmpty();
        assertThat(reportCountRepository.existsByTargetTypeAndTargetId(ReportTargetType.COMMENT, comment.getId()))
                .isFalse();
        assertThat(postRepository.findById(post.getId())).isPresent();
    }

    @Test
    void commentOfHiddenPostCannotBeReported() {
        User author = saveUser("author");
        Post post = postRepository.saveAndFlush(Post.create(author, "숨길 글"));
        PostComment comment = postCommentRepository.saveAndFlush(PostComment.create(post, author, "댓글"));
        reportEnough(ReportTargetType.POST, post.getId());

        assertThatThrownBy(() -> report(saveUser("reporter"), ReportTargetType.COMMENT, comment.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void reportEnough(ReportTargetType targetType, UUID targetId) {
        for (int i = 0; i < AUTO_HIDE_THRESHOLD; i++) {
            report(saveUser("reporter"), targetType, targetId);
        }
        assertThat(reportCount(targetType, targetId).isHidden()).isTrue();
    }

    private void report(User reporter, ReportTargetType targetType, UUID targetId) {
        reportService.createReport(CustomUserPrincipal.from(reporter),
                new ReportCreateRequest(targetType, targetId, ReportReasonCode.SPAM, null));
    }

    private ReportCount reportCount(ReportTargetType targetType, UUID targetId) {
        return reportCountRepository.findAll().stream()
                .filter(count -> count.getTargetType() == targetType && count.getTargetId().equals(targetId))
                .findFirst()
                .orElseThrow();
    }

    private long commentCount(Post post) {
        return postRepository.findById(post.getId()).orElseThrow().getCommentCount();
    }

    private void setDeletedAt(String table, UUID id, OffsetDateTime deletedAt) {
        jdbcTemplate.update("update " + table + " set deleted_at = ? where id = ?", deletedAt, id);
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal(prefix + "+" + suffix + "@example.com", prefix + suffix, prefix, "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        User saved = userRepository.saveAndFlush(user);
        userIds.add(saved.getId());
        return saved;
    }
}
