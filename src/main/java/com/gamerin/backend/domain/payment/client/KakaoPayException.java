package com.gamerin.backend.domain.payment.client;

/**
 * 카카오페이 호출 실패 (04 문서 6-2절 12번 분류).
 * HTTP 상태만으로 결제 성립 여부를 판정하지 않으므로 단계별 처리는 호출하는 쪽이 정한다.
 */
public class KakaoPayException extends RuntimeException {

    public enum Kind {
        /** 4xx 거부 응답. ready면 실패로 닫고, approve·cancel이면 즉시 주문 조회로 판정한다. */
        REJECTED,
        /** 설정 누락 또는 -401·-403·-429·-731. 오류 로그·알림 대상이다. */
        CONFIGURATION,
        /** 타임아웃·연결 끊김·5xx·해석할 수 없는 응답. 결과 불명이다. */
        UNKNOWN
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final Integer errorCode;
    private final String methodResultCode;

    public KakaoPayException(String operation, Kind kind, Integer httpStatus, Integer errorCode,
                             String methodResultCode) {
        // 사용자에게 노출될 수 있으므로 카카오페이 error_message·extras 메시지는 담지 않는다.
        super("KakaoPay " + operation + " failed: " + kind);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.methodResultCode = methodResultCode;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getMethodResultCode() {
        return methodResultCode;
    }
}
