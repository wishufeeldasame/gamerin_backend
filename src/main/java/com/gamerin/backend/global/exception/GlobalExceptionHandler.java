package com.gamerin.backend.global.exception;

import com.gamerin.backend.global.logging.JsonLogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            ResponseStatusException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String message = e.getReason() == null ? status.getReasonPhrase() : e.getReason();
        JsonLogContext.setFailureReason(request, message);

        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "message", message
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("입력값이 올바르지 않습니다.");
        JsonLogContext.setFailureReason(request, message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", message
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestParameter(
            MissingServletRequestParameterException e,
            HttpServletRequest request
    ) {
        String message = "필수 요청 파라미터가 누락되었습니다: " + e.getParameterName();
        JsonLogContext.setFailureReason(request, message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", message
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e,
            HttpServletRequest request
    ) {
        String message = "입력값 타입이 올바르지 않습니다: " + e.getName();
        JsonLogContext.setFailureReason(request, message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", message
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableMessage(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {
        String message = "요청 본문을 읽을 수 없습니다.";
        JsonLogContext.setFailureReason(request, message);

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
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException e,
            HttpServletRequest request
    ) {
        String message = "업로드 가능한 파일 크기를 초과했습니다.";
        JsonLogContext.setFailureReason(request, message);

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "success", false,
                "message", message
        ));
    }
}
