package com.gamerin.backend.domain.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostNumberRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;

    @Test
    void assignsIncreasingPostNumberOnInsertAndLooksUpOnlyActivePosts() {
        User user = userRepository.saveAndFlush(User.createLocal("n@example.com", "n", "n", "pw"));
        Post first = postRepository.saveAndFlush(Post.create(user, "first"));
        Post second = postRepository.saveAndFlush(Post.create(user, "second"));

        assertThat(first.getPostNumber()).isNotNull();
        assertThat(second.getPostNumber()).isGreaterThan(first.getPostNumber());
        assertThat(postRepository.findByPostNumberAndDeletedAtIsNull(first.getPostNumber()))
                .get().extracting(Post::getId).isEqualTo(first.getId());

        second.softDelete();
        postRepository.saveAndFlush(second);
        assertThat(postRepository.findByPostNumberAndDeletedAtIsNull(second.getPostNumber())).isEmpty();
        assertThat(postRepository.findByPostNumberAndDeletedAtIsNull(999_999L)).isEmpty();
    }
}
