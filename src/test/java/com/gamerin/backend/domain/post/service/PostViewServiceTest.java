package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.repository.PostViewCommandRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostViewCommandRepository.class)
class PostViewServiceTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostViewCommandRepository postViewRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private MutableClock clock;
    private PostViewService service;
    private User author;
    private User reader;
    private Post post;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-06T00:00:00Z"));
        service = new PostViewService(postRepository, postViewRepository, userRepository, clock);
        author = userRepository.saveAndFlush(User.createLocal("a@example.com", "author", "author", "pw"));
        reader = userRepository.saveAndFlush(User.createLocal("r@example.com", "reader", "reader", "pw"));
        post = postRepository.saveAndFlush(Post.create(author, "hello"));
    }

    @Test
    void countsFirstViewAndExposesItOnEntity() {
        assertThat(service.record(reader.getId(), List.of(post.getId()))).isEqualTo(1);

        assertThat(viewCount(post)).isEqualTo(1);
        entityManager.clear();
        assertThat(postRepository.findById(post.getId()).orElseThrow().getViewCount()).isEqualTo(1);
    }

    @Test
    void sameUserWithin24HoursCountsOnce() {
        service.record(reader.getId(), List.of(post.getId()));
        clock.advance(Duration.ofHours(23).plusMinutes(59));

        assertThat(service.record(reader.getId(), List.of(post.getId()))).isZero();
        assertThat(viewCount(post)).isEqualTo(1);
    }

    @Test
    void sameUserAfter24HoursCountsAgainAndWindowRestartsFromThen() {
        service.record(reader.getId(), List.of(post.getId()));
        clock.advance(Duration.ofHours(24));

        assertThat(service.record(reader.getId(), List.of(post.getId()))).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(service.record(reader.getId(), List.of(post.getId()))).isZero();
        assertThat(viewCount(post)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from post_views", Integer.class)).isEqualTo(1);
    }

    @Test
    void authorViewIsCountedAndDifferentUsersCountSeparately() {
        service.record(author.getId(), List.of(post.getId()));
        service.record(reader.getId(), List.of(post.getId()));

        assertThat(viewCount(post)).isEqualTo(2);
    }

    @Test
    void deletedPostIsNotCountedAndLeavesNoViewRow() {
        post.softDelete();
        postRepository.saveAndFlush(post);

        assertThat(service.record(reader.getId(), List.of(post.getId()))).isZero();
        assertThat(viewCount(post)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from post_views", Integer.class)).isZero();
    }

    @Test
    void batchIgnoresDuplicateIdsUnknownIdsAndDeletedPosts() {
        Post other = postRepository.saveAndFlush(Post.create(author, "other"));
        Post gone = postRepository.saveAndFlush(Post.create(author, "gone"));
        gone.softDelete();
        postRepository.saveAndFlush(gone);

        int counted = service.record(reader.getId(),
                List.of(post.getId(), post.getId(), other.getId(), gone.getId(), UUID.randomUUID()));

        assertThat(counted).isEqualTo(2);
        assertThat(viewCount(post)).isEqualTo(1);
        assertThat(viewCount(other)).isEqualTo(1);
        assertThat(viewCount(gone)).isZero();
    }

    @Test
    void emptyInputCountsNothing() {
        assertThat(service.record(reader.getId(), List.of())).isZero();
        assertThat(service.record(reader.getId(), null)).isZero();
    }

    @Test
    void entityUpdateDoesNotOverwriteConcurrentViewCount() {
        Post loaded = postRepository.findById(post.getId()).orElseThrow();
        service.record(reader.getId(), List.of(post.getId())); // 별도 UPDATE로 view_count=1
        loaded.increaseLikeCount();
        postRepository.saveAndFlush(loaded);

        assertThat(viewCount(post)).isEqualTo(1);
    }

    private long viewCount(Post target) {
        return jdbcTemplate.queryForObject("select view_count from posts where id = ?", Long.class, target.getId());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
