package com.gamerin.backend.domain.user.controller;

import com.gamerin.backend.domain.user.dto.request.MileageChargeRequest;
import com.gamerin.backend.domain.user.dto.response.MileageTransactionResponse;
import com.gamerin.backend.domain.user.dto.response.MyMileageResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mileage", description = "마일리지 및 정산 내역 API")
@RestController
@RequestMapping("/api/v1/mileage")
@SecurityRequirement(name = "bearerAuth")
public class MileageController {

    private final MileageService mileageService;
    private final UserRepository userRepository;

    public MileageController(MileageService mileageService, UserRepository userRepository) {
        this.mileageService = mileageService;
        this.userRepository = userRepository;
    }

    @Operation(summary = "내 마일리지 잔액 조회", description = "사용자의 현재 마일리지 잔액만 조회합니다.")
    @GetMapping("/me/balance")
    public ApiResponse<MyMileageResponse> getMyBalance(@AuthenticationPrincipal CustomUserPrincipal principal) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        return ApiResponse.ok(mileageService.getMyBalance(user));
    }

    @Operation(summary = "내 마일리지 내역 조회 (페이징)", description = "적립 및 차감 내역을 페이징하여 조회합니다.")
    @GetMapping("/me/transactions")
    public ApiResponse<Page<MileageTransactionResponse>> getMyTransactions(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @ParameterObject @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        return ApiResponse.ok(mileageService.getMyTransactions(user, pageable));
    }

    @Operation(summary = "마일리지 가상 충전 (테스트용)", description = "테스트를  위해 입력한 금액만큼 마일리지를 충전합니다.")
    @PostMapping("/charge")
    public ApiResponse<MyMileageResponse> chargeMileage(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @RequestBody MileageChargeRequest request
    ) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        return ApiResponse.ok(mileageService.chargeMileage(user, request.amount()));
    }

}