package com.gamerin.backend.domain.membership.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.dto.response.MyMembershipResponse;
import com.gamerin.backend.domain.membership.entity.MembershipGrant;
import com.gamerin.backend.domain.membership.entity.MembershipGrantStatus;
import com.gamerin.backend.domain.membership.entity.UserMembership;
import com.gamerin.backend.domain.membership.repository.MembershipGrantRepository;
import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentPurpose;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Service
public class MembershipService {

    private final UserMembershipRepository membershipRepository;
    private final MembershipGrantRepository grantRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public MembershipService(UserMembershipRepository membershipRepository, MembershipGrantRepository grantRepository,
            PaymentRepository paymentRepository, UserRepository userRepository) {
        this(membershipRepository, grantRepository, paymentRepository, userRepository, Clock.systemUTC());
    }

    MembershipService(UserMembershipRepository membershipRepository, MembershipGrantRepository grantRepository,
            PaymentRepository paymentRepository, UserRepository userRepository, Clock clock) {
        this.membershipRepository = membershipRepository;
        this.grantRepository = grantRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        return userId != null && !membershipRepository.findActiveUserIds(List.of(userId), now()).isEmpty();
    }

    @Transactional(readOnly = true)
    public Set<UUID> findActiveUserIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(membershipRepository.findActiveUserIds(userIds, now()));
    }

    private OffsetDateTime now() {
        // Match PostgreSQL's timestamp precision for both lookup paths.
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional(readOnly = true)
    public MyMembershipResponse getMyMembership(UUID userId) {
        if (userId == null || userRepository.findByIdAndDeletedAtIsNull(userId).filter(User::isActive).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found.");
        }
        OffsetDateTime expiresAt = membershipRepository.findById(userId)
                .map(UserMembership::getExpiresAt).orElse(null);
        return MyMembershipResponse.from(expiresAt, now());
    }

    @Transactional
    public void grant(UUID userId, UUID paymentId) {
        // Use the same user -> membership order as revoke, including when the membership already exists.
        // This also serializes the first insert, which an absent membership row cannot lock.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found."));
        if (!payment.isOwnedBy(userId) || payment.getPurpose() != PaymentPurpose.MEMBERSHIP_PASS
                || payment.getStatus() != PaymentStatus.APPROVED) {
            throw conflict("승인된 본인 멤버십 결제만 기간을 부여할 수 있습니다.");
        }
        var existingGrant = grantRepository.findByPayment_Id(paymentId);
        if (existingGrant.isPresent()) {
            if (existingGrant.get().getStatus() == MembershipGrantStatus.REVOKED) {
                throw conflict("회수된 결제는 자동으로 다시 부여할 수 없습니다.");
            }
            return;
        }

        var existingMembership = membershipRepository.findByUserIdForUpdate(userId);
        OffsetDateTime now = now();
        UserMembership membership = existingMembership.orElseGet(() -> UserMembership.create(user, now));
        OffsetDateTime grantedFrom = membership.getExpiresAt().isAfter(now) ? membership.getExpiresAt() : now;
        membership.applyMembershipPassFrom(grantedFrom);
        if (existingMembership.isEmpty()) {
            membershipRepository.saveAndFlush(membership);
        }
        OffsetDateTime createdAt = nextGrantCreatedAt(userId, now);
        grantRepository.save(MembershipGrant.create(user, payment, grantedFrom, membership.getExpiresAt(), createdAt));
    }

    @Transactional
    public void revoke(UUID paymentId) {
        // Select only the ID before locking: an entity loaded here could remain stale while waiting for the user lock.
        UUID userId = grantRepository.findUserIdByPaymentId(paymentId)
                .orElseThrow(() -> conflict("회수할 멤버십 부여 이력이 없습니다."));
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
        UserMembership membership = membershipRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> conflict("멤버십 이용 기간이 존재하지 않습니다."));
        MembershipGrant grant = grantRepository.findByPayment_Id(paymentId)
                .orElseThrow(() -> conflict("회수할 멤버십 부여 이력이 없습니다."));
        MembershipGrant latest = grantRepository.findFirstByUser_IdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> conflict("회수할 멤버십 부여 이력이 없습니다."));
        MembershipGrant latestGranted = grantRepository
                .findFirstByUser_IdAndStatusOrderByCreatedAtDesc(userId, MembershipGrantStatus.GRANTED)
                .orElseThrow(() -> conflict("회수할 유효 부여 이력이 없습니다."));
        if (grant.getStatus() != MembershipGrantStatus.GRANTED || !grant.getId().equals(latest.getId())
                || !grant.getId().equals(latestGranted.getId())) {
            throw conflict("후속 구매가 있거나 마지막 GRANTED 이력이 아니므로 회수할 수 없습니다.");
        }
        if (!membership.getExpiresAt().isEqual(grant.getGrantedUntil())) {
            throw conflict("현재 이용 기간과 부여 이력이 일치하지 않아 회수할 수 없습니다.");
        }
        membership.setExpiresAt(grant.getGrantedFrom());
        grant.revoke();
    }

    private OffsetDateTime nextGrantCreatedAt(UUID userId, OffsetDateTime now) {
        // Serialize equal-microsecond purchases deterministically without relying on random UUID ordering.
        return grantRepository.findFirstByUser_IdOrderByCreatedAtDesc(userId)
                .map(previous -> previous.getCreatedAt().isBefore(now)
                        ? now : previous.getCreatedAt().plusNanos(1000))
                .orElse(now);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
