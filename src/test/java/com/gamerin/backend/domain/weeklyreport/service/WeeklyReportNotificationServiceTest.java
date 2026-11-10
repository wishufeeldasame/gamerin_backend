package com.gamerin.backend.domain.weeklyreport.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import org.junit.jupiter.api.Test;

class WeeklyReportNotificationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final WeeklyReportNotificationService service = new WeeklyReportNotificationService(users, memberships,
            notifications);

    @Test
    void onlyNotifiesActiveMembersCurrentlyLinkedToTheCompletedAccount() {
        UUID member = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        WeeklyGameReport report = report(WeeklyReportStatus.READY);
        when(users.findActiveUserIdsByConnectedValorantPuuid("puuid")).thenReturn(List.of(member, expired));
        when(memberships.findActiveUserIds(List.of(member, expired))).thenReturn(Set.of(member));

        service.notifyReady(report);

        verify(notifications).insertWeeklyReportReady(any(), eq(member), eq(report.getId()), any());
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void noDataAndFailureDoNotCreateCompletionNotifications() {
        service.notifyReady(report(WeeklyReportStatus.NO_DATA));
        service.notifyReady(report(WeeklyReportStatus.FAILED));
        verifyNoInteractions(users, memberships, notifications);
    }

    @Test
    void unlinkedAccountDoesNotNotifyAnyone() {
        when(users.findActiveUserIdsByConnectedValorantPuuid("puuid")).thenReturn(List.of());
        when(memberships.findActiveUserIds(List.of())).thenReturn(Set.of());
        service.notifyReady(report(WeeklyReportStatus.READY));
        verifyNoInteractions(notifications);
    }

    private WeeklyGameReport report(WeeklyReportStatus status) {
        WeeklyGameReport report = WeeklyGameReport.create("puuid", "VALORANT", LocalDate.parse("2026-11-02"));
        report.update(status, null, null, null);
        return report;
    }
}
