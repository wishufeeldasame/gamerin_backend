package com.gamerin.backend.domain.payment.service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.payment.client.KakaoPayClient;
import com.gamerin.backend.domain.payment.client.KakaoPayException;
import com.gamerin.backend.domain.payment.dto.request.PaymentApproveRequest;
import com.gamerin.backend.domain.payment.dto.request.PaymentReadyRequest;
import com.gamerin.backend.domain.payment.dto.response.PaymentProductResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentReadyResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentResponse;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentPurpose;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.model.PaymentCursor;
import com.gamerin.backend.domain.payment.model.PaymentProduct;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;

/**
 * 카카오페이 결제 API (04 문서 6-1·6-2·6-6절).
 * 외부 호출은 트랜잭션 밖에서 하고, 처리권 확보와 결과 반영은 PaymentStateService의 조건부 전이로 한다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String RESULT_PATH = "/payments/result";
    private static final String NOT_CONFIGURED_MESSAGE = "결제 기능을 사용할 수 없습니다. 잠시 후 다시 시도해주세요.";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    // 결제 목록 대상: 실제 결제가 일어났거나 확인 중인 주문 (6-6절 결제 목록)
    private static final List<PaymentStatus> LISTED_STATUSES = List.of(
            PaymentStatus.APPROVING, PaymentStatus.APPROVED, PaymentStatus.CANCELING,
            PaymentStatus.CANCELED, PaymentStatus.NEEDS_REVIEW);

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PaymentStateService stateService;
    private final PaymentRecoveryService recoveryService;
    private final KakaoPayClient kakaoPayClient;
    private final TransactionTemplate transactionTemplate;
    private final String frontendBaseUrl;
    private final boolean cancelEnabled;

    public PaymentService(PaymentRepository paymentRepository,
                          UserRepository userRepository,
                          PaymentStateService stateService,
                          PaymentRecoveryService recoveryService,
                          KakaoPayClient kakaoPayClient,
                          TransactionTemplate transactionTemplate,
                          @Value("${app.frontend.base-url}") String frontendBaseUrl,
                          @Value("${kakaopay.cancel-enabled:false}") boolean cancelEnabled) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.stateService = stateService;
        this.recoveryService = recoveryService;
        this.kakaoPayClient = kakaoPayClient;
        this.transactionTemplate = transactionTemplate;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
        this.cancelEnabled = cancelEnabled;
    }

    public List<PaymentProductResponse> getProducts() {
        return Arrays.stream(PaymentProduct.values())
                .map(PaymentProductResponse::from)
                .toList();
    }

    // 결과 불명·만료 후보면 조회 시점 복구 후 결과를 반환한다(6-6절 복구 실행).
    public PaymentResponse getPayment(UUID userId, UUID orderId) {
        Payment payment = findOwned(userId, orderId);
        return PaymentResponse.from(configGuarded(() -> recoveryService.recoverIfNeeded(payment)));
    }

    // 본인 결제만, 결제가 일어났거나 확인 중인 상태만 (createdAt, id) 내림차순 커서로 반환한다.
    public CursorPageResponse<PaymentResponse> getMyPayments(UUID userId, String cursor, int size) {
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        PaymentCursor parsedCursor = PaymentCursor.parse(cursor);
        PageRequest limit = PageRequest.of(0, pageSize + 1);
        List<Payment> loaded = parsedCursor == null
                ? paymentRepository.findFirstPage(userId, LISTED_STATUSES, limit)
                : paymentRepository.findPageBefore(userId, LISTED_STATUSES,
                        parsedCursor.createdAt(), parsedCursor.paymentId(), limit);
        boolean hasNext = loaded.size() > pageSize;
        List<Payment> page = hasNext ? loaded.subList(0, pageSize) : loaded;
        String nextCursor = hasNext
                ? PaymentCursor.encode(page.getLast().getCreatedAt(), page.getLast().getId())
                : null;
        return new CursorPageResponse<>(page.stream().map(PaymentResponse::from).toList(), nextCursor, hasNext);
    }

    public PaymentReadyResponse ready(UUID userId, PaymentReadyRequest request) {
        PaymentProduct product = PaymentProduct.fromCode(request.productCode());
        if (!kakaoPayClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
        }

        Optional<Payment> existing = paymentRepository.findByBuyerIdAndRequestId(userId, request.requestId());
        if (existing.isPresent()) {
            return replayReady(existing.get(), product);
        }

        Payment payment;
        try {
            payment = transactionTemplate.execute(status -> {
                if (product.getPurpose() == PaymentPurpose.MEMBERSHIP_PASS) {
                    rejectWhileMembershipCanceling(userId);
                }
                return paymentRepository.saveAndFlush(Payment.create(
                        userRepository.getReferenceById(userId),
                        request.requestId(),
                        product,
                        kakaoPayClient.cid(),
                        PaymentStateService.now()
                ));
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 requestId의 동시 요청: 먼저 저장된 주문으로 재응답한다.
            return paymentRepository.findByBuyerIdAndRequestId(userId, request.requestId())
                    .map(found -> replayReady(found, product))
                    .orElseThrow(() -> e);
        }

        KakaoPayClient.ReadyResponse response;
        try {
            response = kakaoPayClient.ready(readyRequest(payment, userId));
        } catch (KakaoPayException e) {
            // TID를 받지 못했으면 결제가 시작되지 않은 것이므로 FAILED로 닫는다(6-2절 9·12번).
            stateService.transition(payment.getId(), PaymentStatus.CREATED, payment.getStatusChangedAt(),
                    PaymentStatus.FAILED);
            if (e.getKind() == KakaoPayException.Kind.CONFIGURATION) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
            }
            return PaymentReadyResponse.from(stateService.reload(payment.getId()), PaymentStateService.now());
        }

        OffsetDateTime readyAt = PaymentStateService.now();
        transactionTemplate.executeWithoutResult(status -> {
            if (paymentRepository.transition(payment.getId(), PaymentStatus.CREATED, payment.getStatusChangedAt(),
                    PaymentStatus.READY, readyAt) == 1) {
                stateService.reload(payment.getId()).recordReady(
                        response.tid(),
                        response.nextRedirectPcUrl(),
                        response.nextRedirectMobileUrl(),
                        readyAt,
                        KakaoPayClient.toOffsetDateTime(response.createdAt())
                );
            }
        });
        return PaymentReadyResponse.from(stateService.reload(payment.getId()), PaymentStateService.now());
    }

    public PaymentResponse approve(UUID userId, PaymentApproveRequest request) {
        Payment owned = findOwned(userId, request.orderId());
        Payment payment = configGuarded(() -> recoveryService.recoverIfNeeded(owned));
        switch (payment.getStatus()) {
            case READY -> {
            }
            // 재호출: 외부 호출 없이 현재 상태 (6-6절 재호출 응답)
            case APPROVING, APPROVED, CANCELING, CANCELED, NEEDS_REVIEW -> {
                return PaymentResponse.from(payment);
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "승인할 수 없는 결제 주문입니다.");
        }
        if (payment.isReadyExpired(PaymentStateService.now())) {
            // 만료 후보지만 조회 실패 등으로 아직 READY인 경우에도 승인하지 않는다.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 요청 시간이 만료되었습니다. 다시 결제해주세요.");
        }
        requireConfiguredCid(payment);

        OffsetDateTime claimedAt = PaymentStateService.now();
        if (!stateService.transition(payment.getId(), PaymentStatus.READY, payment.getStatusChangedAt(),
                PaymentStatus.APPROVING, claimedAt)) {
            // 다른 요청이 처리권을 가져갔다.
            return PaymentResponse.from(stateService.reload(payment.getId()));
        }

        KakaoPayClient.ApproveResponse response;
        try {
            response = kakaoPayClient.approve(new KakaoPayClient.ApproveRequest(
                    payment.getCid(),
                    payment.getTid(),
                    payment.getId().toString(),
                    userId.toString(),
                    request.pgToken(),
                    payment.getAmount()
            ));
        } catch (KakaoPayException e) {
            // approve 4xx는 실패로 단정하지 않고 TID 주문 조회로 판정한다. 타임아웃·5xx는 APPROVING으로 둔다(6-2절 12번).
            switch (e.getKind()) {
                case CONFIGURATION -> throw notConfigured();
                case REJECTED -> configGuarded(() -> {
                    recoveryService.resolveApproveRejection(payment, claimedAt);
                    return null;
                });
                case UNKNOWN -> {
                }
                default -> throw new IllegalStateException("Unhandled KakaoPay failure kind: " + e.getKind());
            }
            return PaymentResponse.from(stateService.reload(payment.getId()));
        }
        stateService.completeApproval(payment, claimedAt, new PaymentStateService.ApprovalResult(
                response.aid(),
                response.tid(),
                response.cid(),
                response.partnerOrderId(),
                response.partnerUserId(),
                response.amount() == null ? null : response.amount().total(),
                response.paymentMethodType(),
                KakaoPayClient.toOffsetDateTime(response.approvedAt())
        ));
        return PaymentResponse.from(stateService.reload(payment.getId()));
    }

    /**
     * 전액 결제 취소 (6-6절 결제 취소). 소유자 + kakaopay.cancel-enabled + 주문 CID와 설정 CID 일치일 때만 동작한다.
     * 내부 회수를 CANCELING 전환과 함께 먼저 반영한 뒤 외부 취소를 호출한다.
     */
    public PaymentResponse cancel(UUID userId, UUID orderId) {
        Payment owned = findOwned(userId, orderId);
        if (!cancelEnabled || !owned.getCid().equals(kakaoPayClient.cid())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "결제를 취소할 수 없습니다.");
        }
        Payment payment = configGuarded(() -> recoveryService.recoverIfNeeded(owned));
        switch (payment.getStatus()) {
            case APPROVED -> {
            }
            // 재호출: 외부 호출 없이 현재 상태 (6-6절 재호출 응답)
            case CANCELING, CANCELED -> {
                return PaymentResponse.from(payment);
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "취소할 수 없는 결제 주문입니다.");
        }

        OffsetDateTime claimedAt = PaymentStateService.now();
        if (!stateService.beginCancel(payment, claimedAt)) {
            return PaymentResponse.from(stateService.reload(payment.getId()));
        }
        configGuarded(() -> {
            recoveryService.requestCancel(payment, claimedAt);
            return null;
        });
        return PaymentResponse.from(stateService.reload(payment.getId()));
    }

    // 이용권 취소 시작과 같은 User 행 잠금으로 직렬화해 취소 진행 중에는 새 이용권 주문을 만들지 않는다(6-6절 결제 취소 2번).
    private void rejectWhileMembershipCanceling(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        if (paymentRepository.existsByBuyerIdAndPurposeAndStatusIn(userId, PaymentPurpose.MEMBERSHIP_PASS,
                List.of(PaymentStatus.CANCELING))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소 중인 이용권 결제가 있어 새로 결제할 수 없습니다.");
        }
    }

    private PaymentReadyResponse replayReady(Payment payment, PaymentProduct product) {
        if (payment.getProductCode() != product) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 요청 ID로 다른 상품을 결제할 수 없습니다.");
        }
        return PaymentReadyResponse.from(payment, PaymentStateService.now());
    }

    private KakaoPayClient.ReadyRequest readyRequest(Payment payment, UUID userId) {
        String orderId = payment.getId().toString();
        return new KakaoPayClient.ReadyRequest(
                payment.getCid(),
                orderId,
                // 개인정보 대신 내부 user UUID만 보낸다.
                userId.toString(),
                payment.getItemName(),
                payment.getProductCode().name(),
                1,
                payment.getAmount(),
                0,
                resultUrl(orderId, "approval"),
                resultUrl(orderId, "cancel"),
                resultUrl(orderId, "fail")
        );
    }

    private String resultUrl(String orderId, String result) {
        return frontendBaseUrl + RESULT_PATH + "?orderId=" + orderId + "&result=" + result;
    }

    private void requireConfiguredCid(Payment payment) {
        if (!payment.getCid().equals(kakaoPayClient.cid())) {
            log.error("Payment CID does not match configured CID: orderId={}", payment.getId());
            throw notConfigured();
        }
    }

    private Payment findOwned(UUID userId, UUID orderId) {
        Payment payment = paymentRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 주문을 찾을 수 없습니다."));
        if (!payment.isOwnedBy(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 결제 주문에 접근할 권한이 없습니다.");
        }
        return payment;
    }

    // 카카오페이 설정 오류는 상태를 유지한 채 503으로 응답한다(6-1절 오류 표).
    private <T> T configGuarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (KakaoPayException e) {
            if (e.getKind() == KakaoPayException.Kind.CONFIGURATION) {
                throw notConfigured();
            }
            throw e;
        }
    }

    private static ResponseStatusException notConfigured() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
    }
}
