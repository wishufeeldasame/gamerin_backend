package com.gamerin.backend.domain.payment.service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.gamerin.backend.domain.payment.model.PaymentProduct;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;

/**
 * 카카오페이 결제 준비·승인·조회 (04 문서 6-1·6-2·6-6절).
 * 외부 호출은 트랜잭션 밖에서 하고, 처리권 확보와 결과 반영은 짧은 트랜잭션의 조건부 전이로 한다.
 * 결과 불명 주문의 조회 시점 복구·스케줄러·만료 전이·취소·목록은 A2(#86) 범위다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String RESULT_PATH = "/payments/result";
    private static final String CHARGE_DESCRIPTION = "카카오페이 충전";
    private static final String KAKAOPAY_SUCCESS = "SUCCESS_PAYMENT";
    private static final List<String> KAKAOPAY_FAILED = List.of("FAIL_PAYMENT", "QUIT_PAYMENT", "FAIL_AUTH_PASSWORD");
    private static final List<String> KAKAOPAY_CANCELED = List.of("CANCEL_PAYMENT", "PART_CANCEL_PAYMENT");
    private static final String NOT_CONFIGURED_MESSAGE = "결제 기능을 사용할 수 없습니다. 잠시 후 다시 시도해주세요.";

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final MileageService mileageService;
    private final KakaoPayClient kakaoPayClient;
    private final TransactionTemplate transactionTemplate;
    private final String frontendBaseUrl;

    public PaymentService(PaymentRepository paymentRepository,
                          UserRepository userRepository,
                          MileageService mileageService,
                          KakaoPayClient kakaoPayClient,
                          TransactionTemplate transactionTemplate,
                          @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.mileageService = mileageService;
        this.kakaoPayClient = kakaoPayClient;
        this.transactionTemplate = transactionTemplate;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    public List<PaymentProductResponse> getProducts() {
        return Arrays.stream(PaymentProduct.values())
                .map(PaymentProductResponse::from)
                .toList();
    }

    public PaymentResponse getPayment(UUID userId, UUID orderId) {
        return PaymentResponse.from(findOwned(userId, orderId));
    }

    public PaymentReadyResponse ready(UUID userId, PaymentReadyRequest request) {
        PaymentProduct product = PaymentProduct.fromCode(request.productCode());
        if (product.getPurpose() != PaymentPurpose.MILEAGE_CHARGE) {
            // shortcut: 이용권 결제는 MembershipService.grant(#87 스텁)·CANCELING 중 ready 거부가 들어온 뒤 연다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용하지 않은 상품 코드입니다.");
        }
        if (!kakaoPayClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
        }

        Optional<Payment> existing = paymentRepository.findByBuyerIdAndRequestId(userId, request.requestId());
        if (existing.isPresent()) {
            return replayReady(existing.get(), product);
        }

        Payment payment;
        try {
            payment = transactionTemplate.execute(status -> paymentRepository.saveAndFlush(Payment.create(
                    userRepository.getReferenceById(userId),
                    request.requestId(),
                    product,
                    kakaoPayClient.cid(),
                    now()
            )));
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
            transition(payment.getId(), PaymentStatus.CREATED, payment.getStatusChangedAt(), PaymentStatus.FAILED);
            if (e.getKind() == KakaoPayException.Kind.CONFIGURATION) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
            }
            return PaymentReadyResponse.from(reload(payment.getId()), now());
        }

        OffsetDateTime readyAt = now();
        transactionTemplate.executeWithoutResult(status -> {
            if (paymentRepository.transition(payment.getId(), PaymentStatus.CREATED, payment.getStatusChangedAt(),
                    PaymentStatus.READY, readyAt) == 1) {
                reload(payment.getId()).recordReady(
                        response.tid(),
                        response.nextRedirectPcUrl(),
                        response.nextRedirectMobileUrl(),
                        readyAt,
                        KakaoPayClient.toOffsetDateTime(response.createdAt())
                );
            }
        });
        return PaymentReadyResponse.from(reload(payment.getId()), now());
    }

    public PaymentResponse approve(UUID userId, PaymentApproveRequest request) {
        Payment payment = findOwned(userId, request.orderId());
        switch (payment.getStatus()) {
            case READY -> {
            }
            // 재호출: 외부 호출 없이 현재 상태 (6-6절 재호출 응답)
            case APPROVING, APPROVED, CANCELING, CANCELED, NEEDS_REVIEW -> {
                return PaymentResponse.from(payment);
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "승인할 수 없는 결제 주문입니다.");
        }
        if (payment.isReadyExpired(now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 요청 시간이 만료되었습니다. 다시 결제해주세요.");
        }
        if (!payment.getCid().equals(kakaoPayClient.cid())) {
            log.error("Payment CID does not match configured CID: orderId={}", payment.getId());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
        }

        OffsetDateTime claimedAt = now();
        if (!transition(payment.getId(), PaymentStatus.READY, payment.getStatusChangedAt(),
                PaymentStatus.APPROVING, claimedAt)) {
            // 다른 요청이 처리권을 가져갔다.
            return PaymentResponse.from(reload(payment.getId()));
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
            return handleApproveFailure(payment, claimedAt, e);
        }
        return completeApproval(payment, claimedAt, new ApprovalResult(
                response.aid(),
                response.tid(),
                response.cid(),
                response.partnerOrderId(),
                response.partnerUserId(),
                response.amount() == null ? null : response.amount().total(),
                response.paymentMethodType(),
                KakaoPayClient.toOffsetDateTime(response.approvedAt())
        ));
    }

    // approve 4xx는 실패로 단정하지 않고 TID 주문 조회로 판정한다. 타임아웃·5xx는 APPROVING으로 둔다(6-2절 12번).
    private PaymentResponse handleApproveFailure(Payment payment, OffsetDateTime claimedAt, KakaoPayException e) {
        switch (e.getKind()) {
            case CONFIGURATION -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
            case UNKNOWN -> {
                return PaymentResponse.from(reload(payment.getId()));
            }
            case REJECTED -> {
                KakaoPayClient.OrderResponse order;
                try {
                    order = kakaoPayClient.order(new KakaoPayClient.OrderRequest(payment.getCid(), payment.getTid()));
                } catch (KakaoPayException lookupError) {
                    if (lookupError.getKind() == KakaoPayException.Kind.CONFIGURATION) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
                    }
                    return PaymentResponse.from(reload(payment.getId()));
                }
                return applyOrderStatus(payment, claimedAt, order);
            }
            default -> throw new IllegalStateException("Unhandled KakaoPay failure kind: " + e.getKind());
        }
    }

    // 주문 조회 status를 APPROVING 주문의 다음 상태로 옮긴다(6-2절 11번).
    private PaymentResponse applyOrderStatus(Payment payment, OffsetDateTime claimedAt,
                                             KakaoPayClient.OrderResponse order) {
        String status = order.status();
        if (KAKAOPAY_SUCCESS.equals(status)) {
            return completeApproval(payment, claimedAt, new ApprovalResult(
                    paymentAid(order),
                    order.tid(),
                    order.cid(),
                    order.partnerOrderId(),
                    order.partnerUserId(),
                    order.amount() == null ? null : order.amount().total(),
                    order.paymentMethodType(),
                    KakaoPayClient.toOffsetDateTime(order.approvedAt())
            ));
        }
        if (KAKAOPAY_FAILED.contains(status)) {
            transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.FAILED);
        } else if (KAKAOPAY_CANCELED.contains(status)) {
            // 승인 반영 전 취소·부분 취소는 이번 범위에서 발생하지 않아야 하므로 수동 확인 대상이다.
            log.error("KakaoPay order canceled before internal approval: orderId={}, status={}",
                    payment.getId(), status);
            transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.NEEDS_REVIEW);
        }
        // 그 밖의 status는 진행 중이므로 APPROVING을 유지하고 복구로 넘긴다.
        return PaymentResponse.from(reload(payment.getId()));
    }

    private String paymentAid(KakaoPayClient.OrderResponse order) {
        if (order.paymentActionDetails() == null) {
            return null;
        }
        return order.paymentActionDetails().stream()
                .filter(detail -> "PAYMENT".equals(detail.paymentActionType()))
                .map(KakaoPayClient.PaymentActionDetail::aid)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // 검증 후 한 트랜잭션에서 APPROVED 전환과 충전 적립을 반영한다(6-2절 3·4번).
    private PaymentResponse completeApproval(Payment payment, OffsetDateTime claimedAt, ApprovalResult result) {
        if (!result.matches(payment)) {
            log.error("KakaoPay approval does not match stored order: orderId={}", payment.getId());
            transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.NEEDS_REVIEW);
            return PaymentResponse.from(reload(payment.getId()));
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (paymentRepository.transition(payment.getId(), PaymentStatus.APPROVING, claimedAt,
                        PaymentStatus.APPROVED, now()) == 0) {
                    // 처리권을 잃은 늦은 응답은 새 상태를 덮어쓰지 않는다.
                    return;
                }
                Payment approved = reload(payment.getId());
                approved.recordApproval(result.aid(), result.approvedAt(), result.paymentMethodType());
                grantBenefit(approved);
            });
        } catch (DataIntegrityViolationException e) {
            // 원장 (type, reference_id) 부분 UNIQUE가 중복 적립을 막았다. 주문은 APPROVING으로 남아 복구 대상이 된다.
            log.error("Payment benefit already reflected: orderId={}", payment.getId());
        }
        return PaymentResponse.from(reload(payment.getId()));
    }

    private void grantBenefit(Payment payment) {
        if (payment.getPurpose() == PaymentPurpose.MILEAGE_CHARGE) {
            mileageService.addMileage(payment.getBuyer(), payment.getAmount(), TransactionType.CHARGE,
                    CHARGE_DESCRIPTION, payment.getId());
            return;
        }
        // shortcut: 이용권은 ready에서 막혀 있어 도달하지 않는다. #87 머지 후 MembershipService.grant로 바꾼다.
        throw new IllegalStateException("Membership grant is not available yet.");
    }

    private PaymentReadyResponse replayReady(Payment payment, PaymentProduct product) {
        if (payment.getProductCode() != product) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 요청 ID로 다른 상품을 결제할 수 없습니다.");
        }
        return PaymentReadyResponse.from(payment, now());
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

    private Payment findOwned(UUID userId, UUID orderId) {
        Payment payment = paymentRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 주문을 찾을 수 없습니다."));
        if (!payment.isOwnedBy(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 결제 주문에 접근할 권한이 없습니다.");
        }
        return payment;
    }

    private Payment reload(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment disappeared: " + paymentId));
    }

    private boolean transition(UUID paymentId, PaymentStatus observed, OffsetDateTime observedAt, PaymentStatus next) {
        return transition(paymentId, observed, observedAt, next, now());
    }

    private boolean transition(UUID paymentId, PaymentStatus observed, OffsetDateTime observedAt,
                               PaymentStatus next, OffsetDateTime now) {
        Integer updated = transactionTemplate.execute(status ->
                paymentRepository.transition(paymentId, observed, observedAt, next, now));
        return updated != null && updated == 1;
    }

    // DB(PostgreSQL)는 마이크로초까지 저장하므로 status_changed_at 비교가 어긋나지 않게 맞춘다.
    private static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private record ApprovalResult(
            String aid,
            String tid,
            String cid,
            String partnerOrderId,
            String partnerUserId,
            Long amountTotal,
            String paymentMethodType,
            OffsetDateTime approvedAt
    ) {
        // 금액·CID·TID·주문번호·사용자 식별자가 저장한 주문과 같아야 한다(6-2절 1번, 6-5절).
        private boolean matches(Payment payment) {
            return aid != null && !aid.isBlank()
                    && payment.getAmount().equals(amountTotal)
                    && payment.getCid().equals(cid)
                    && payment.getTid().equals(tid)
                    && payment.getId().toString().equals(partnerOrderId)
                    && payment.getBuyer().getId().toString().equals(partnerUserId);
        }
    }
}
