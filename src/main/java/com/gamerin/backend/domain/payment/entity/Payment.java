package com.gamerin.backend.domain.payment.entity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.payment.model.PaymentProduct;
import com.gamerin.backend.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 카카오페이 결제 주문. id가 partner_order_id(응답의 orderId)다.
 * 상태 전이는 PaymentRepository.transition의 조건부 갱신으로만 한다(04 문서 6-6절 처리권).
 */
@Entity
@Table(name = "payments")
public class Payment {

    public static final String PROVIDER_KAKAOPAY = "KAKAOPAY";
    // TID·결제 요청 유효 시간 (04 문서 6-2절 10번)
    public static final Duration READY_TTL = Duration.ofMinutes(15);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_code", nullable = false)
    private PaymentProduct productCode;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private String cid;

    private String tid;

    @Column(name = "approved_aid")
    private String approvedAid;

    @Column(name = "canceled_aid")
    private String canceledAid;

    @Column(name = "payment_method_type")
    private String paymentMethodType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "status_changed_at", nullable = false)
    private OffsetDateTime statusChangedAt;

    @Column(name = "next_redirect_pc_url", columnDefinition = "TEXT")
    private String nextRedirectPcUrl;

    @Column(name = "next_redirect_mobile_url", columnDefinition = "TEXT")
    private String nextRedirectMobileUrl;

    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

    @Column(name = "provider_created_at")
    private OffsetDateTime providerCreatedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Payment() {
    }

    public static Payment create(User buyer, UUID requestId, PaymentProduct product, String cid, OffsetDateTime now) {
        Payment payment = new Payment();
        payment.buyer = buyer;
        payment.requestId = requestId;
        payment.provider = PROVIDER_KAKAOPAY;
        payment.purpose = product.getPurpose();
        payment.productCode = product;
        payment.itemName = product.getDisplayName();
        payment.amount = product.getPrice();
        payment.cid = cid;
        payment.status = PaymentStatus.CREATED;
        payment.statusChangedAt = now;
        payment.createdAt = now;
        return payment;
    }

    // 상태는 transition으로 바꾼 뒤 같은 트랜잭션에서 부가 정보만 기록한다.
    public void recordReady(String tid, String pcUrl, String mobileUrl, OffsetDateTime readyAt,
                            OffsetDateTime providerCreatedAt) {
        this.tid = tid;
        this.nextRedirectPcUrl = pcUrl;
        this.nextRedirectMobileUrl = mobileUrl;
        this.readyAt = readyAt;
        this.providerCreatedAt = providerCreatedAt;
    }

    public void recordApproval(String aid, OffsetDateTime approvedAt, String paymentMethodType) {
        this.approvedAid = aid;
        this.approvedAt = approvedAt;
        this.paymentMethodType = paymentMethodType;
    }

    public void recordCancel(String aid, OffsetDateTime canceledAt) {
        this.canceledAid = aid;
        this.canceledAt = canceledAt;
    }

    public boolean isReadyExpired(OffsetDateTime now) {
        return readyAt == null || !readyAt.plus(READY_TTL).isAfter(now);
    }

    public boolean isOwnedBy(UUID userId) {
        return buyer.getId().equals(userId);
    }

    public UUID getId() {
        return id;
    }

    public User getBuyer() {
        return buyer;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getProvider() {
        return provider;
    }

    public PaymentPurpose getPurpose() {
        return purpose;
    }

    public PaymentProduct getProductCode() {
        return productCode;
    }

    public String getItemName() {
        return itemName;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCid() {
        return cid;
    }

    public String getTid() {
        return tid;
    }

    public String getApprovedAid() {
        return approvedAid;
    }

    public String getCanceledAid() {
        return canceledAid;
    }

    public String getPaymentMethodType() {
        return paymentMethodType;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public OffsetDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public String getNextRedirectPcUrl() {
        return nextRedirectPcUrl;
    }

    public String getNextRedirectMobileUrl() {
        return nextRedirectMobileUrl;
    }

    public OffsetDateTime getReadyAt() {
        return readyAt;
    }

    public OffsetDateTime getProviderCreatedAt() {
        return providerCreatedAt;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public OffsetDateTime getCanceledAt() {
        return canceledAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
