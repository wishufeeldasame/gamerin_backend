package com.gamerin.backend.domain.membership.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

import com.gamerin.backend.domain.payment.model.PaymentProduct;

public record MyMembershipResponse(
        boolean active,
        OffsetDateTime expiresAt,
        long price,
        int durationDays,
        List<String> benefits
) {

    public static MyMembershipResponse from(OffsetDateTime expiresAt, OffsetDateTime now) {
        PaymentProduct product = PaymentProduct.MEMBERSHIP_30D;
        return new MyMembershipResponse(
                expiresAt != null && expiresAt.isAfter(now),
                expiresAt,
                product.getPrice(),
                product.getDurationDays(),
                List.of(
                        "멤버십 배지",
                        "최초 게시 후 1시간 이내 게시물 최대 1회 수정",
                        "멘토 정산 수수료 5%"
                )
        );
    }
}
