package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;
import com.gamerin.backend.domain.repost.model.PostTimelineItem;
import com.gamerin.backend.domain.repost.repository.RepostQueryRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;

@ExtendWith(MockitoExtension.class)
class PostResponseAssemblerTest {
    @Mock private PostMediaRepository mediaRepository;
    @Mock private PostLikeRepository likeRepository;
    @Mock private PostBookmarkRepository bookmarkRepository;
    @Mock private RepostQueryRepository repostRepository;
    @Mock private MembershipService membershipService;
    @InjectMocks private PostResponseAssembler assembler;

    @Test
    void timelineBatchesAuthorsAndRepostersAndKeepsVerifiedBadgeIndependent() {
        User author = user("author", true);
        User inactive = user("inactive", false);
        UUID reposterId = UUID.randomUUID();
        Post first = post(author);
        Post second = post(inactive);
        OffsetDateTime repostedAt = OffsetDateTime.parse("2026-10-15T00:00:00Z");
        when(membershipService.findActiveUserIds(anyCollection())).thenReturn(Set.of(reposterId));

        var responses = assembler.toTimelinePostCards(List.of(
                new PostTimelineItem(first, new ReposterInfoResponse(reposterId, "Reposter", repostedAt, false), repostedAt),
                new PostTimelineItem(second, null, repostedAt)), author.getId());

        assertThat(responses).extracting(response -> response.authorMembershipBadge()).containsExactly(false, false);
        assertThat(responses.getFirst().authorVerifiedBadge()).isTrue();
        assertThat(responses.getFirst().reposterInfo().membershipBadge()).isTrue();
        assertThat(responses.getFirst().reposterInfo().repostedAt()).isEqualTo(repostedAt);
        assertThat(responses.getLast().reposterInfo()).isNull();
        verify(membershipService).findActiveUserIds(Set.of(author.getId(), inactive.getId(), reposterId));
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void postCardsApplyActiveAuthorsFromTheBulkResult() {
        User active = user("active", false);
        User inactive = user("inactive", false);
        when(membershipService.findActiveUserIds(anyCollection())).thenReturn(Set.of(active.getId()));

        var responses = assembler.toPostCards(List.of(post(active), post(inactive), post(active)), null);

        assertThat(responses).extracting(response -> response.authorMembershipBadge()).containsExactly(true, false, true);
        verify(membershipService).findActiveUserIds(Set.of(active.getId(), inactive.getId()));
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void commentListBatchesRepeatedAuthorsAndRetainsMineFlags() {
        User active = user("active", false);
        User inactive = user("inactive", true);
        Post post = post(active);
        when(membershipService.findActiveUserIds(anyCollection())).thenReturn(Set.of(active.getId()));

        var responses = assembler.toCommentResponses(List.of(
                PostComment.create(post, active, "first"), PostComment.create(post, inactive, "second"),
                PostComment.create(post, active, "third")), active.getId());

        assertThat(responses).extracting(response -> response.authorMembershipBadge()).containsExactly(true, false, true);
        assertThat(responses).extracting(response -> response.mine()).containsExactly(true, false, true);
        assertThat(responses.get(1).authorVerifiedBadge()).isTrue();
        verify(membershipService).findActiveUserIds(Set.of(active.getId(), inactive.getId()));
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void detailAndNewCommentUseTheActualAuthorMembership() {
        User author = user("author", false);
        Post post = post(author);
        when(membershipService.isActive(author.getId())).thenReturn(true);

        assertThat(assembler.toPostDetail(post, null).authorMembershipBadge()).isTrue();
        assertThat(assembler.toCommentResponse(PostComment.create(post, author, "comment"), null)
                .authorMembershipBadge()).isTrue();
    }

    @Test
    void falseBadgeIsPresentInJson() {
        User author = user("author", false);
        var response = assembler.toCommentResponse(PostComment.create(post(author), author, "comment"), null);

        var json = new ObjectMapper().findAndRegisterModules().valueToTree(response);

        assertThat(json.has("authorMembershipBadge")).isTrue();
        assertThat(json.get("authorMembershipBadge").booleanValue()).isFalse();
    }

    @Test
    void emptyPostAndCommentListsDoNotQueryMembership() {
        assertThat(assembler.toPostCards(List.of(), null)).isEmpty();
        assertThat(assembler.toTimelinePostCards(List.of(), null)).isEmpty();
        assertThat(assembler.toCommentResponses(List.of(), null)).isEmpty();
        verifyNoInteractions(membershipService);
    }

    private User user(String handle, boolean verified) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "encoded");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        UserProfile profile = UserProfile.createDefault(user);
        ReflectionTestUtils.setField(profile, "verifiedBadge", verified);
        user.setProfile(profile);
        return user;
    }

    private Post post(User author) {
        Post post = Post.create(author, "content");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        return post;
    }
}
