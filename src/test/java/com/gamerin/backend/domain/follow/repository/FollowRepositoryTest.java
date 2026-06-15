package com.gamerin.backend.domain.follow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FollowRepositoryTest {

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void countActiveFollowersAndFollowingExcludesDeletedUsers() {
        User target = saveUser("target");
        User activeFollower = saveUser("activefollower");
        User deletedFollower = saveUser("deletedfollower");
        User activeFollowee = saveUser("activefollowee");
        User deletedFollowee = saveUser("deletedfollowee");

        saveFollow(activeFollower, target);
        saveFollow(deletedFollower, target);
        saveFollow(target, activeFollowee);
        saveFollow(target, deletedFollowee);
        markUserDeleted(deletedFollower);
        markUserDeleted(deletedFollowee);
        flushAndClear();

        assertThat(followRepository.countActiveFollowersByFolloweeId(target.getId())).isEqualTo(1);
        assertThat(followRepository.countActiveFollowingByFollowerId(target.getId())).isEqualTo(1);
    }

    @Test
    void followerPageQueriesExcludeDeletedUsersAndApplyCursor() {
        User target = saveUser("followertarget");
        User firstFollower = saveUser("firstfollower");
        User secondFollower = saveUser("secondfollower");
        User deletedFollower = saveUser("removedfollower");
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-06-11T10:00:00+09:00");

        Follow firstFollow = saveFollow(firstFollower, target);
        Follow secondFollow = saveFollow(secondFollower, target);
        Follow deletedFollow = saveFollow(deletedFollower, target);
        setFollowCreatedAt(firstFollow, createdAt);
        setFollowCreatedAt(secondFollow, createdAt);
        setFollowCreatedAt(deletedFollow, createdAt);
        markUserDeleted(deletedFollower);
        flushAndClear();

        List<String> activeFollowIds = followRepository.findFollowerPageIds(target.getId(), 10);

        assertThat(activeFollowIds)
                .hasSize(2)
                .contains(firstFollow.getId().toString(), secondFollow.getId().toString())
                .doesNotContain(deletedFollow.getId().toString());

        String cursorId = followRepository.findFollowerPageIds(target.getId(), 1).get(0);
        List<String> nextPageIds = followRepository.findFollowerPageIdsBefore(
                target.getId(),
                createdAt,
                UUID.fromString(cursorId),
                10
        );

        assertThat(nextPageIds)
                .containsExactlyElementsOf(activeFollowIds.stream()
                        .filter(id -> !id.equals(cursorId))
                        .toList());
    }

    @Test
    void followingPageQueriesExcludeDeletedUsersAndApplyCursor() {
        User target = saveUser("followingtarget");
        User firstFollowee = saveUser("firstfollowee");
        User secondFollowee = saveUser("secondfollowee");
        User deletedFollowee = saveUser("removedfollowee");
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-06-11T10:00:00+09:00");

        Follow firstFollow = saveFollow(target, firstFollowee);
        Follow secondFollow = saveFollow(target, secondFollowee);
        Follow deletedFollow = saveFollow(target, deletedFollowee);
        setFollowCreatedAt(firstFollow, createdAt);
        setFollowCreatedAt(secondFollow, createdAt);
        setFollowCreatedAt(deletedFollow, createdAt);
        markUserDeleted(deletedFollowee);
        flushAndClear();

        List<String> activeFollowIds = followRepository.findFollowingPageIds(target.getId(), 10);

        assertThat(activeFollowIds)
                .hasSize(2)
                .contains(firstFollow.getId().toString(), secondFollow.getId().toString())
                .doesNotContain(deletedFollow.getId().toString());

        String cursorId = followRepository.findFollowingPageIds(target.getId(), 1).get(0);
        List<String> nextPageIds = followRepository.findFollowingPageIdsBefore(
                target.getId(),
                createdAt,
                UUID.fromString(cursorId),
                10
        );

        assertThat(nextPageIds)
                .containsExactlyElementsOf(activeFollowIds.stream()
                        .filter(id -> !id.equals(cursorId))
                        .toList());
    }

    private User saveUser(String handle) {
        return userRepository.saveAndFlush(User.createLocal(
                handle + "@example.com",
                handle,
                handle,
                "encoded-password"
        ));
    }

    private Follow saveFollow(User follower, User followee) {
        return followRepository.saveAndFlush(Follow.create(follower, followee));
    }

    private void setFollowCreatedAt(Follow follow, OffsetDateTime createdAt) {
        entityManager.createNativeQuery("UPDATE follows SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", follow.getId())
                .executeUpdate();
    }

    private void markUserDeleted(User user) {
        entityManager.createNativeQuery("UPDATE users SET deleted_at = :deletedAt WHERE id = :id")
                .setParameter("deletedAt", OffsetDateTime.parse("2026-06-12T10:00:00+09:00"))
                .setParameter("id", user.getId())
                .executeUpdate();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
