package com.gamerin.backend.domain.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SignUpValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private SignUpRequest signUp(String handle) {
        return new SignUpRequest(handle, "Tester", "user@example.com", "Password1!", "Password1!", true, true, true, "1.0", "1.0", "verify-token");
    }

    private SocialSignUpRequest social(String handle, String nickname) {
        return new SocialSignUpRequest("token", handle, nickname, true, true, true, "1.0", "1.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a.b", "User_01", "abc.", "aaaaaaaaaaaaaaaaaaaaa"})
    void signUpRejectsInvalidHandle(String handle) {
        assertThat(validator.validate(signUp(handle))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "user_01", "aaaaaaaaaaaaaaaaaaaa"})
    void signUpAcceptsValidHandle(String handle) {
        assertThat(validator.validate(signUp(handle))).isEmpty();
    }

    @Test
    void signUpRequiresEmailVerificationToken() {
        SignUpRequest request = new SignUpRequest("abc", "Tester", "user@example.com", "Password1!", "Password1!", true, true, true, "1.0", "1.0", " ");
        assertThat(validator.validate(request))
                .extracting(v -> v.getMessage())
                .containsExactly("이메일 인증이 필요합니다.");
    }

    @Test
    void socialSignUpUsesSameHandleMessageAsSignUp() {
        String signUpMessage = validator.validate(signUp("a.b")).iterator().next().getMessage();
        assertThat(validator.validate(social("a.b", "Tester")))
                .extracting(v -> v.getMessage())
                .contains(signUpMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "aaaaaaaaaaaaaaaaaaaaa"})
    void socialSignUpRejectsInvalidNicknameLength(String nickname) {
        assertThat(validator.validate(social("abc", nickname))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "aaaaaaaaaaaaaaaaaaaa"})
    void socialSignUpAcceptsValidNicknameLength(String nickname) {
        assertThat(validator.validate(social("abc", nickname))).isEmpty();
    }

    private static final String TERMS_MSG = "이용약관 동의가 필요합니다.";
    private static final String PRIVACY_MSG = "개인정보 처리방침 동의가 필요합니다.";
    private static final String AGE_MSG = "만 14세 이상만 가입할 수 있습니다.";

    // 각 동의 필드를 false 또는 null로 바꾼 요청은 해당 메시지 하나만 위반해야 한다
    @ParameterizedTest
    @CsvSource(value = {"0,false,이용약관 동의가 필요합니다.", "0,null,이용약관 동의가 필요합니다.",
            "1,false,개인정보 처리방침 동의가 필요합니다.", "1,null,개인정보 처리방침 동의가 필요합니다.",
            "2,false,만 14세 이상만 가입할 수 있습니다.", "2,null,만 14세 이상만 가입할 수 있습니다."})
    void consentFlagsMustBeTrueForBothRequests(int index, String value, String message) {
        Boolean[] flags = {true, true, true};
        flags[index] = "null".equals(value) ? null : false;

        SignUpRequest local = new SignUpRequest("abc", "Tester", "user@example.com", "Password1!", "Password1!",
                flags[0], flags[1], flags[2], "1.0", "1.0", "verify-token");
        SocialSignUpRequest social = new SocialSignUpRequest("token", "abc", "Tester",
                flags[0], flags[1], flags[2], "1.0", "1.0");

        assertThat(validator.validate(local)).extracting(v -> v.getMessage()).containsExactly(message);
        assertThat(validator.validate(social)).extracting(v -> v.getMessage()).containsExactly(message);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void versionsMustNotBeBlankForBothRequests(String version) {
        SignUpRequest local = new SignUpRequest("abc", "Tester", "user@example.com", "Password1!", "Password1!",
                true, true, true, version, version, "verify-token");
        SocialSignUpRequest social = new SocialSignUpRequest("token", "abc", "Tester",
                true, true, true, version, version);

        assertThat(validator.validate(local)).extracting(v -> v.getMessage())
                .containsExactlyInAnyOrder("약관 버전이 필요합니다.", "개인정보 처리방침 버전이 필요합니다.");
        assertThat(validator.validate(social)).extracting(v -> v.getMessage())
                .containsExactlyInAnyOrder("약관 버전이 필요합니다.", "개인정보 처리방침 버전이 필요합니다.");
    }
}
