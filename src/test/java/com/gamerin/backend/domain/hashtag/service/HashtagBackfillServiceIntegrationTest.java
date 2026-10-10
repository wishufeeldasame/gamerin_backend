package com.gamerin.backend.domain.hashtag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagBackfillResult;
import com.gamerin.backend.domain.hashtag.entity.Hashtag;
import com.gamerin.backend.domain.hashtag.repository.HashtagRepository;
import com.gamerin.backend.domain.hashtag.repository.PostHashtagRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@SpringBootTest
class HashtagBackfillServiceIntegrationTest {

    @Autowired
    private HashtagBackfillService hashtagBackfillService;
    @Autowired
    private PostHashtagRepository postHashtagRepository;
    @Autowired
    private HashtagRepository hashtagRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        postHashtagRepository.deleteAllInBatch();
        hashtagRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void backfillRunsInBatchesAndIsSafeToRepeat() {
        User user = saveUser("backfill");
        savePost(user, "첫 글 #PUBG #pubg");
        savePost(user, "둘째 글 #배그");
        savePost(user, "해시는 있지만 태그 아님 C#");
        Post deleted = savePost(user, "삭제 글 #hidden");
        deleted.softDelete();
        postRepository.saveAndFlush(deleted);

        HashtagBackfillResult first = hashtagBackfillService.backfill(2);
        long firstRelationCount = postHashtagRepository.count();
        HashtagBackfillResult second = hashtagBackfillService.backfill(2);

        assertThat(first.processedPosts()).isEqualTo(3);
        assertThat(first.batches()).isEqualTo(2);
        assertThat(second.processedPosts()).isEqualTo(3);
        assertThat(postHashtagRepository.count()).isEqualTo(firstRelationCount).isEqualTo(3);
        assertThat(hashtagRepository.findAll())
                .extracting(Hashtag::getNormalizedName)
                .containsExactlyInAnyOrder("PUBG", "pubg", "배그");
        assertThat(hashtagRepository.findByNormalizedName("hidden")).isEmpty();
    }

    @Test
    void backfillRejectsUnsafeBatchSizes() {
        assertThatThrownBy(() -> hashtagBackfillService.backfill(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hashtagBackfillService.backfill(1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal(
                prefix + "+" + suffix + "@example.com",
                prefix + suffix,
                prefix,
                "encoded-password"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private Post savePost(User author, String content) {
        return postRepository.saveAndFlush(Post.create(author, content));
    }
}
