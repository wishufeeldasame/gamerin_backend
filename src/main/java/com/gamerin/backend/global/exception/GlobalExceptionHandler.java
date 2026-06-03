package com.gamerin.backend.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());

        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "message", e.getReason() == null ? status.getReasonPhrase() : e.getReason()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("입력값이 올바르지 않습니다.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", message
        ));
    }

    /*** 
    비즈니스 로직 처리 중 발생하는 일반적인 예외 (IllegalArgumentException등)를 처리합니다.
    HTTP 상태 코드는 400 (BAD_REQUEST)으로 반환합니다.
    ***/
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", false);
        response.put("message", e.getMessage() != null ? e.getMessage() : "잘못된 요청입니다.");
        response.put("data", null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /*** 
    지정되지 않은 모든 RuntimeException을 처리합니다. 
    이 핸들러가 없으면 Spring Security가 에러를 낚아채서 401 인증 에러로 덮어씌우는 문제가 발생합니다.
    ***/
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        // 서버 로그에는 에러의 원인을 남깁니다 (필요에 따라 log.error 사용 가능)

        e.printStackTrace();

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", false);
        response.put("message", e.getMessage() != null ? e.getMessage() : "서버 처리 중 오류가 발생했습니다.");
        response.put("data", null);
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
