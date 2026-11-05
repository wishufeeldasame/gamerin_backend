package com.gamerin.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.auth.entity.ConsentType;
import com.gamerin.backend.domain.auth.entity.UserConsent;
import com.gamerin.backend.domain.auth.repository.UserConsentRepository;

@ExtendWith(MockitoExtension.class)
class UserConsentServiceTest {

    @Mock
    private UserConsentRepository userConsentRepository;

    @Captor
    private ArgumentCaptor<List<UserConsent>> consentsCaptor;

    private UserConsentService service() {
        return new UserConsentService(userConsentRepository, "2.0", "3.0");
    }

    @Test
    void acceptsCurrentVersions() {
        service().validateVersions("2.0", "3.0");
    }

    @ParameterizedTest
    @CsvSource({"1.0,3.0", "2.0,1.0", "' 2.0',3.0", "2,3"})
    void rejectsOutdatedOrDifferentVersions(String terms, String privacy) {
        assertThatThrownBy(() -> service().validateVersions(terms, privacy))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("약관이 개정되었습니다. 페이지를 새로고침한 뒤 다시 동의해 주세요.");
                });
        verifyNoInteractions(userConsentRepository);
    }

    @Test
    void recordsThreeConsentsWithSameTimestamp() {
        UUID userId = UUID.randomUUID();

        service().recordSignupConsents(userId);

        verify(userConsentRepository).saveAll(consentsCaptor.capture());
        List<UserConsent> consents = consentsCaptor.getValue();
        assertThat(consents).extracting(UserConsent::getConsentType)
                .containsExactly(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_14);
        assertThat(consents).extracting(UserConsent::getVersion).containsExactly("2.0", "3.0", "2.0");
        assertThat(consents).extracting(UserConsent::getUserId).containsOnly(userId);
        assertThat(consents).extracting(UserConsent::getAgreedAt).doesNotContainNull().containsOnly(consents.get(0).getAgreedAt());
    }
}
