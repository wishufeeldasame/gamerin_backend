package com.gamerin.backend.domain.weeklyreport.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyReportNotificationService {

    private final UserRepository userRepository;
    private final MembershipService membershipService;
    private final NotificationRepository notificationRepository;

    public WeeklyReportNotificationService(UserRepository userRepository, MembershipService membershipService,
            NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.membershipService = membershipService;
        this.notificationRepository = notificationRepository;
    }

    /** 리포트와 알림은 함께 커밋한다. 외부 API를 호출하는 트랜잭션은 포함하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyReady(WeeklyGameReport report) {
        if (report.getStatus() != WeeklyReportStatus.READY) {
            return;
        }
        List<UUID> connected = userRepository.findActiveUserIdsByConnectedValorantPuuid(report.getPuuid());
        for (UUID recipientId : membershipService.findActiveUserIds(connected)) {
            notificationRepository.insertWeeklyReportReady(UUID.randomUUID(), recipientId, report.getId(),
                    OffsetDateTime.now());
        }
    }
}
