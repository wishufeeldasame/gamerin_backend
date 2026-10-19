package com.gamerin.backend.domain.payment.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.payment.dto.request.PaymentApproveRequest;
import com.gamerin.backend.domain.payment.dto.request.PaymentReadyRequest;
import com.gamerin.backend.domain.payment.dto.response.PaymentProductResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentReadyResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentResponse;
import com.gamerin.backend.domain.payment.service.PaymentService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Payment", description = "카카오페이 결제 API")
@RestController
@RequestMapping("/api/v1/payments")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "결제 상품 목록", description = "충전 패키지와 멤버십 이용권의 서버 상품 정의를 조회합니다.")
    @GetMapping("/products")
    public ApiResponse<List<PaymentProductResponse>> getProducts() {
        return ApiResponse.ok(paymentService.getProducts());
    }

    @Operation(summary = "카카오페이 결제 준비", description = "상품 코드로 주문을 만들고 결제창 URL을 받습니다. 같은 requestId는 기존 주문으로 재응답합니다.")
    @PostMapping("/kakao/ready")
    public ApiResponse<PaymentReadyResponse> ready(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody PaymentReadyRequest request
    ) {
        return ApiResponse.ok(paymentService.ready(principal.getUserId(), request));
    }

    @Operation(summary = "카카오페이 결제 승인", description = "결제창 복귀 후 pg_token으로 승인하고 충전 마일리지를 적립합니다.")
    @PostMapping("/kakao/approve")
    public ApiResponse<PaymentResponse> approve(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody PaymentApproveRequest request
    ) {
        return ApiResponse.ok(paymentService.approve(principal.getUserId(), request));
    }

    @Operation(summary = "내 결제 목록", description = "결제가 일어났거나 확인 중인 본인 주문을 최신순 커서로 조회합니다.")
    @GetMapping
    public ApiResponse<CursorPageResponse<PaymentResponse>> getMyPayments(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(paymentService.getMyPayments(principal.getUserId(), cursor, size));
    }

    @Operation(summary = "결제 주문 조회", description = "본인 주문의 결제·혜택 반영 상태를 조회합니다.")
    @GetMapping("/{orderId}")
    public ApiResponse<PaymentResponse> getPayment(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID orderId
    ) {
        return ApiResponse.ok(paymentService.getPayment(principal.getUserId(), orderId));
    }

    @Operation(summary = "결제 취소", description = "본인 주문을 전액 취소하고 충전 마일리지를 회수합니다. kakaopay.cancel-enabled가 켜져 있을 때만 동작합니다.")
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<PaymentResponse> cancel(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID orderId
    ) {
        return ApiResponse.ok(paymentService.cancel(principal.getUserId(), orderId));
    }
}
