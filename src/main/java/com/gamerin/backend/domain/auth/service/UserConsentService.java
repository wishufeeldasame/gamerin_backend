package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.entity.ConsentType;
import com.gamerin.backend.domain.auth.entity.UserConsent;
import com.gamerin.backend.domain.auth.repository.UserConsentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class UserConsentService {

    private final UserConsentRepository userConsentRepository;
    private final String termsVersion;
    private final String privacyVersion;

    public UserConsentService(
            UserConsentRepository userConsentRepository,
            @Value("${app.legal.terms-version}") String termsVersion,
            @Value("${app.legal.privacy-version}") String privacyVersion) {
        this.userConsentRepository = userConsentRepository;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    /** 요청의 약관 버전이 서버 현재 버전과 다르면 400. 가입 처리의 맨 앞에서 호출한다. */
    public void validateVersions(String requestedTermsVersion, String requestedPrivacyVersion) {
        if (!termsVersion.equals(requestedTermsVersion) || !privacyVersion.equals(requestedPrivacyVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "약관이 개정되었습니다. 페이지를 새로고침한 뒤 다시 동의해 주세요.");
        }
    }

    /** 가입 트랜잭션에 참여해 현재 버전 기준 동의 3건을 같은 시각으로 기록한다. */
    public void recordSignupConsents(UUID userId) {
        OffsetDateTime agreedAt = OffsetDateTime.now();
        userConsentRepository.saveAll(List.of(
                UserConsent.create(userId, ConsentType.TERMS, termsVersion, agreedAt),
                UserConsent.create(userId, ConsentType.PRIVACY, privacyVersion, agreedAt),
                // 만 14세 확인은 별도 문서가 없어 약관 버전을 함께 기록한다
                UserConsent.create(userId, ConsentType.AGE_14, termsVersion, agreedAt)));
    }
}
