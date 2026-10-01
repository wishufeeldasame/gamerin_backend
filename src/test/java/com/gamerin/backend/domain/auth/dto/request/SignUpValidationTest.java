package com.gamerin.backend.domain.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignUpValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private SignUpRequest signUp(String handle) {
        return new SignUpRequest(handle, "Tester", "user@example.com", "Password1!", "Password1!", true, true);
    }

    private SocialSignUpRequest social(String handle, String nickname) {
        return new SocialSignUpRequest("token", handle, nickname, true, true);
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
}
