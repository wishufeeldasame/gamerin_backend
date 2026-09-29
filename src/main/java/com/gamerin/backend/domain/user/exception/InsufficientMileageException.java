package com.gamerin.backend.domain.user.exception;

/**
 * 마일리지 잔액이 부족할 때 발생하는 도메인 비즈니스 예외.
 * HTTP 계층에 종속되지 않으며, 웹 환경에서는 GlobalExceptionHandler에 의해 400 BAD_REQUEST로 변환됩니다.
 */
public class InsufficientMileageException extends RuntimeException {

    public InsufficientMileageException(String message) {
        super(message);
    }

    public InsufficientMileageException(Long currentBalance) {
        super("마일리지가 부족합니다. (현재 잔액: " + currentBalance + ")");
    }
}