package com.gamerin.backend.domain.mentoring.dto.request;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

class MentoringProgramUpdateRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("수정 DTO의 price가 음수이면 Validation 에러가 발생한다")
    void updateRequestRejectsNegativePrice() {
        MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                "제목",
                "내용",
                "시간설명",
                -1000L,
                ProgramStatus.ACTIVE,
                List.of("tag")
        );

        Set<ConstraintViolation<MentoringProgramUpdateRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("price"));
    }

    @Test
    @DisplayName("수정 DTO의 price가 0원이면 정상 통과한다")
    void updateRequestAcceptsZeroPrice() {
        MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                "무료 강의",
                "내용",
                "시간설명",
                0L,
                ProgramStatus.ACTIVE,
                List.of("tag")
        );

        Set<ConstraintViolation<MentoringProgramUpdateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}