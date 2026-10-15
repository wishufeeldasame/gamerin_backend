package com.gamerin.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.notification.entity.Notification;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private MembershipService membershipService;

    private NotificationQueryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationQueryService(userRepository, notificationRepository, membershipService);
    }

    @Test
    void returnsStableCursorPageAndMapsActorContract() {
        User recipient = savedUser("recipient", "Recipient");
        User actor = savedUser("actor", "Actor");
        Post post = savedPost(recipient);
        UUID notificationId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-12T10:00:00+09:00");
        Notification notification = savedLikeNotification(notificationId, createdAt, recipient, actor, post);

        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidPageIds(recipient.getId(), 2))
                .thenReturn(List.of(notificationId.toString(), nextId.toString()));
        when(notificationRepository.findAllWithDetailsByIdIn(List.of(notificationId, nextId)))
                .thenReturn(List.of(notification));
        when(membershipService.findActiveUserIds(Set.of(actor.getId()))).thenReturn(Set.of(actor.getId()));

        var firstPage = service.getNotifications(CustomUserPrincipal.from(recipient), null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(firstPage.items().getFirst().type()).isEqualTo("like");
        assertThat(firstPage.items().getFirst().actor().handle()).isEqualTo("actor");
        assertThat(firstPage.items().getFirst().actor().membershipBadge()).isTrue();
        assertThat(firstPage.items().getFirst().postId()).isEqualTo(post.getId());
        assertThat(firstPage.items().getFirst().read()).isFalse();

        when(notificationRepository.findValidPageIdsBefore(
                recipient.getId(),
                createdAt,
                notificationId,
                2
        )).thenReturn(List.of());

        var secondPage = service.getNotifications(
                CustomUserPrincipal.from(recipient),
                firstPage.nextCursor(),
                1
        );

        assertThat(secondPage.items()).isEmpty();
        verify(membershipService).findActiveUserIds(Set.of(actor.getId()));
        verify(membershipService, never()).isActive(any());
        verify(notificationRepository).findValidPageIdsBefore(
                recipient.getId(),
                createdAt,
                notificationId,
                2
        );
    }

    @Test
    void invalidCursorReturnsBadRequest() {
        User recipient = savedUser("recipient", "Recipient");
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));

        assertThatThrownBy(() -> service.getNotifications(
                CustomUserPrincipal.from(recipient),
                "not-a-cursor",
                20
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void pageSizeUsesDefaultForNonPositiveValuesAndCapsOversizedValues() {
        User recipient = savedUser("recipient", "Recipient");
        CustomUserPrincipal principal = CustomUserPrincipal.from(recipient);
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidPageIds(recipient.getId(), 21)).thenReturn(List.of());
        when(notificationRepository.findValidPageIds(recipient.getId(), 51)).thenReturn(List.of());

        service.getNotifications(principal, null, 0);
        service.getNotifications(principal, null, -10);
        service.getNotifications(principal, null, 1_000);

        verify(notificationRepository, org.mockito.Mockito.times(2))
                .findValidPageIds(recipient.getId(), 21);
        verify(notificationRepository).findValidPageIds(recipient.getId(), 51);
        verifyNoInteractions(membershipService);
    }

    @Test
    void actorBadgesUseOneBatchWithDuplicateAndMissingActors() {
        User recipient = savedUser("recipient", "Recipient");
        User member = savedUser("member", "Member");
        User nonMember = savedUser("other", "Other");
        ReflectionTestUtils.setField(nonMember.getProfile(), "verifiedBadge", true);
        Post post = savedPost(recipient);
        OffsetDateTime time = OffsetDateTime.parse("2026-10-15T00:00:00Z");
        Notification first = savedLikeNotification(UUID.randomUUID(), time, recipient, member, post);
        Notification duplicate = savedLikeNotification(UUID.randomUUID(), time.minusSeconds(1), recipient, member, post);
        Notification inactive = savedLikeNotification(UUID.randomUUID(), time.minusSeconds(2), recipient, nonMember, post);
        Notification missing = savedLikeNotification(UUID.randomUUID(), time.minusSeconds(3), recipient, member, post);
        ReflectionTestUtils.setField(missing, "actor", null);
        List<Notification> notifications = List.of(first, duplicate, inactive, missing);
        List<UUID> ids = notifications.stream().map(Notification::getId).toList();
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidPageIds(recipient.getId(), 21))
                .thenReturn(ids.stream().map(UUID::toString).toList());
        when(notificationRepository.findAllWithDetailsByIdIn(ids)).thenReturn(notifications);
        when(membershipService.findActiveUserIds(Set.of(member.getId(), nonMember.getId())))
                .thenReturn(Set.of(member.getId()));

        var response = service.getNotifications(CustomUserPrincipal.from(recipient), null, 20);

        assertThat(response.items().get(0).actor().membershipBadge()).isTrue();
        assertThat(response.items().get(1).actor().membershipBadge()).isTrue();
        assertThat(response.items().get(2).actor().membershipBadge()).isFalse();
        assertThat(response.items().get(2).actor().verifiedBadge()).isTrue();
        assertThat(response.items().get(3).actor()).isNull();
        verify(membershipService).findActiveUserIds(Set.of(member.getId(), nonMember.getId()));
        verify(membershipService, never()).isActive(any());
    }

    @Test
    void actorlessNotificationsDoNotQueryMembership() {
        User recipient = savedUser("recipient", "Recipient");
        Notification notification = savedLikeNotification(
                UUID.randomUUID(), OffsetDateTime.parse("2026-10-15T00:00:00Z"),
                recipient, recipient, savedPost(recipient)
        );
        ReflectionTestUtils.setField(notification, "actor", null);
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidPageIds(recipient.getId(), 21))
                .thenReturn(List.of(notification.getId().toString()));
        when(notificationRepository.findAllWithDetailsByIdIn(List.of(notification.getId())))
                .thenReturn(List.of(notification));

        var response = service.getNotifications(CustomUserPrincipal.from(recipient), null, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().actor()).isNull();
        verifyNoInteractions(membershipService);
    }

    @Test
    void cancellationBetweenIdAndDetailQueriesDoesNotBreakCursorProgress() {
        User recipient = savedUser("recipient", "Recipient");
        User actor = savedUser("actor", "Actor");
        Post post = savedPost(recipient);
        UUID removedId = UUID.randomUUID();
        UUID availableId = UUID.randomUUID();
        Notification available = savedLikeNotification(
                availableId,
                OffsetDateTime.parse("2026-08-12T09:59:00+09:00"),
                recipient,
                actor,
                post
        );
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidPageIds(recipient.getId(), 2))
                .thenReturn(List.of(removedId.toString(), availableId.toString()));
        when(notificationRepository.findAllWithDetailsByIdIn(List.of(removedId, availableId)))
                .thenReturn(List.of(available));

        var page = service.getNotifications(CustomUserPrincipal.from(recipient), null, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().notificationId()).isEqualTo(availableId);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void unreadCountUsesRepositoryValidityFilter() {
        User recipient = savedUser("recipient", "Recipient");
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.countValidUnreadByRecipientId(recipient.getId())).thenReturn(3L);

        var response = service.getUnreadCount(CustomUserPrincipal.from(recipient));

        assertThat(response.unreadCount()).isEqualTo(3L);
    }

    @Test
    void markReadAcknowledgesObservedEventWithoutMutatingManagedEntityOrRetrying() {
        User recipient = savedUser("recipient", "Recipient");
        User actor = savedUser("actor", "Actor");
        Post post = savedPost(recipient);
        UUID notificationId = UUID.randomUUID();
        Notification notification = savedLikeNotification(
                notificationId,
                OffsetDateTime.parse("2026-08-12T10:00:00+09:00"),
                recipient,
                actor,
                post
        );
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidByIdAndRecipientId(notificationId, recipient.getId()))
                .thenReturn(Optional.of(notification));

        service.markRead(CustomUserPrincipal.from(recipient), notificationId);

        // A zero-row update (the mock default) must not retry against a newer event.
        verify(notificationRepository).findValidByIdAndRecipientId(notificationId, recipient.getId());
        verify(notificationRepository).markReadIfUnchanged(
                eq(notificationId), eq(recipient.getId()), eq(notification.getEventAt()), isNull(), any()
        );
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    void markReadRequiresAuthentication() {
        assertThatThrownBy(() -> service.markRead(null, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void markReadReturnsNotFoundForUnknownOrOtherUsersNotification() {
        User recipient = savedUser("recipient", "Recipient");
        UUID notificationId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(notificationRepository.findValidByIdAndRecipientId(notificationId, recipient.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(CustomUserPrincipal.from(recipient), notificationId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void markAllReadUsesOneRequestCutoff() {
        User recipient = savedUser("recipient", "Recipient");
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));

        service.markAllRead(CustomUserPrincipal.from(recipient));

        verify(notificationRepository).markAllReadBefore(eq(recipient.getId()), any(), any());
    }

    private Notification savedLikeNotification(
            UUID notificationId,
            OffsetDateTime createdAt,
            User recipient,
            User actor,
            Post post
    ) {
        Notification notification = Notification.like(recipient, actor, post, PostLike.create(post, actor));
        ReflectionTestUtils.setField(notification, "id", notificationId);
        ReflectionTestUtils.setField(notification, "createdAt", createdAt);
        ReflectionTestUtils.setField(notification, "eventAt", createdAt);
        return notification;
    }

    private User savedUser(String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setProfile(UserProfile.createDefault(user));
        return user;
    }

    private Post savedPost(User author) {
        Post post = Post.create(author, "post");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        return post;
    }
}
