package com.gamerin.backend.domain.notification.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.notification.dto.response.NotificationActorResponse;
import com.gamerin.backend.domain.notification.dto.response.NotificationResponse;
import com.gamerin.backend.domain.notification.dto.response.UnreadNotificationCountResponse;
import com.gamerin.backend.domain.notification.entity.Notification;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional(readOnly = true)
public class NotificationQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    public NotificationQueryService(
            UserRepository userRepository,
            NotificationRepository notificationRepository
    ) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
    }

    public CursorPageResponse<NotificationResponse> getNotifications(
            CustomUserPrincipal principal,
            String cursor,
            int size
    ) {
        UUID recipientId = getCurrentUserId(principal);
        int pageSize = clampSize(size);
        NotificationCursor cursorValue = parseCursor(cursor);

        List<UUID> loadedIds = cursorValue == null
                ? toUuidList(notificationRepository.findValidPageIds(recipientId, pageSize + 1))
                : toUuidList(notificationRepository.findValidPageIdsBefore(
                        recipientId,
                        cursorValue.createdAt(),
                        cursorValue.notificationId(),
                        pageSize + 1
                ));

        if (loadedIds.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }

        boolean hasNext = loadedIds.size() > pageSize;
        List<Notification> loadedNotifications = findInOrder(loadedIds);
        List<Notification> notifications = loadedNotifications.size() > pageSize
                ? new ArrayList<>(loadedNotifications.subList(0, pageSize))
                : loadedNotifications;
        if (notifications.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }
        List<NotificationResponse> items = notifications.stream()
                .map(this::toResponse)
                .toList();
        String nextCursor = hasNext && !notifications.isEmpty()
                ? buildCursor(notifications.get(notifications.size() - 1))
                : null;

        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    public UnreadNotificationCountResponse getUnreadCount(CustomUserPrincipal principal) {
        UUID recipientId = getCurrentUserId(principal);
        return new UnreadNotificationCountResponse(
                notificationRepository.countValidUnreadByRecipientId(recipientId)
        );
    }

    @Transactional
    public void markRead(CustomUserPrincipal principal, UUID notificationId) {
        UUID recipientId = getCurrentUserId(principal);
        Notification notification = notificationRepository
                .findValidByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found."));
        notification.markRead(now());
    }

    @Transactional
    public void markAllRead(CustomUserPrincipal principal) {
        UUID recipientId = getCurrentUserId(principal);
        OffsetDateTime cutoff = now();
        notificationRepository.markAllReadBefore(recipientId, cutoff, cutoff);
    }

    private List<Notification> findInOrder(List<UUID> ids) {
        Map<UUID, Notification> notificationsById = notificationRepository.findAllWithDetailsByIdIn(ids)
                .stream()
                .collect(Collectors.toMap(Notification::getId, Function.identity()));

        return ids.stream()
                .map(notificationsById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private NotificationResponse toResponse(Notification notification) {
        User actor = notification.getActor();
        UserProfile profile = actor != null ? actor.getProfile() : null;
        return new NotificationResponse(
                notification.getId(),
                notification.getType().apiValue(),
                actor != null
                        ? new NotificationActorResponse(
                                actor.getId(),
                                actor.getHandle(),
                                actor.getNickname(),
                                profile != null ? profile.getProfileImageUrl() : null,
                                profile != null && profile.isVerifiedBadge()
                        )
                        : null,
                notification.getPost() != null ? notification.getPost().getId() : null,
                resolveCommentId(notification),
                notification.getConversation() != null ? notification.getConversation().getId() : null,
                notification.getMessage() != null ? notification.getMessage().getId() : null,
                notification.getMentoringApplication() != null
                        ? notification.getMentoringApplication().getId()
                        : null,
                notification.getMentoringReview() != null ? notification.getMentoringReview().getId() : null,
                notification.getReadAt() != null,
                notification.getEventAt()
        );
    }

    private UUID resolveCommentId(Notification notification) {
        if (notification.getComment() != null) {
            return notification.getComment().getId();
        }
        if (notification.getMention() != null && notification.getMention().getComment() != null) {
            return notification.getMention().getComment().getId();
        }
        return null;
    }

    private UUID getCurrentUserId(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ));
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private List<UUID> toUuidList(List<String> ids) {
        return ids.stream().map(UUID::fromString).toList();
    }

    private NotificationCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor payload.");
            }
            return new NotificationCursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
        }
    }

    private String buildCursor(Notification notification) {
        String payload = notification.getEventAt() + "|" + notification.getId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private record NotificationCursor(OffsetDateTime createdAt, UUID notificationId) {
    }
}
