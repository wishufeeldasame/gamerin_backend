package com.gamerin.backend.domain.mentoring.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.user.entity.User;

class MentoringMembershipBadgeSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void allGenericMentoringBadgesArePresentIncludingFalse(boolean active) {
        MentoringApplication application = application();
        MentoringProgram program = application.getProgram();
        MentorProfile mentor = program.getMentor();
        MentoringReview review = new MentoringReview();
        review.setApplication(application);
        review.setMentee(application.getMentee());
        List<Object> responses = List.of(
                MentorProfileResponse.from(mentor, active),
                MentoringProgramResponse.from(program, active),
                MentoringProgramDetailResponse.from(program, active),
                MentoringReviewResponse.from(review, active));

        for (Object response : responses) {
            JsonNode json = mapper.valueToTree(response);
            assertThat(json.has("membershipBadge")).isTrue();
            assertThat(json.get("membershipBadge").isBoolean()).isTrue();
            assertThat(json.get("membershipBadge").booleanValue()).isEqualTo(active);
        }
    }

    @Test
    void userApplicationsIncludeIndependentExplicitBooleans() {
        MentoringApplication application = application();
        JsonNode json = mapper.valueToTree(MentoringApplicationResponse.from(application, true, false, true));

        assertThat(json.get("mentorMembershipBadge").isBoolean()).isTrue();
        assertThat(json.get("mentorMembershipBadge").booleanValue()).isFalse();
        assertThat(json.get("menteeMembershipBadge").booleanValue()).isTrue();
        assertThat(json.get("reviewed").booleanValue()).isTrue();
        assertThat(json.get("mentorId").asText()).isEqualTo(application.getProgram().getMentor().getId().toString());
        assertThat(json.get("menteeId").asText()).isEqualTo(application.getMentee().getId().toString());
    }

    @Test
    void adminApplicationFactoryOmitsOnlyNewBadgesAndPreservesLegacyNullFields() {
        MentoringApplicationResponse response = MentoringApplicationResponse.from(application());
        JsonNode json = mapper.valueToTree(response);

        assertThat(response.mentorMembershipBadge()).isNull();
        assertThat(response.menteeMembershipBadge()).isNull();
        assertThat(json.has("mentorMembershipBadge")).isFalse();
        assertThat(json.has("menteeMembershipBadge")).isFalse();
        assertThat(json.has("feeRateBp")).isFalse();
        assertThat(json.has("feeAmount")).isFalse();
        assertThat(json.has("payoutAmount")).isFalse();
        assertThat(json.has("message")).isTrue();
        assertThat(json.get("message").isNull()).isTrue();
        assertThat(json.get("reviewed").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("[KP-C2/F5] 멘토 관점 응답은 수수료, 요율, 수령액, 비가입 안내를 JSON으로 직렬화한다")
    void mentorApplicationResponseIncludesFeeDetailsInJson() {
        MentoringApplication application = application();
        application.setAppliedMileage(10000L);
        application.setFeeRateBp(1000);

        MentoringApplicationResponse response = MentoringApplicationResponse.fromMentor(
                application, false, false, false);
        JsonNode json = mapper.valueToTree(response);

        assertThat(json.has("feeRateBp")).isTrue();
        assertThat(json.get("feeRateBp").asInt()).isEqualTo(1000);
        assertThat(json.has("feeAmount")).isTrue();
        assertThat(json.get("feeAmount").asLong()).isEqualTo(1000L);
        assertThat(json.has("payoutAmount")).isTrue();
        assertThat(json.get("payoutAmount").asLong()).isEqualTo(9000L);
        assertThat(json.has("nonMemberNotice")).isTrue();
        assertThat(json.get("nonMemberNotice").asBoolean()).isTrue();
        assertThat(json.has("feeNotice")).isTrue();
        assertThat(json.get("feeNotice").asText()).isEqualTo("멤버십 가입 시 수수료 5% 혜택을 받을 수 있습니다.");
    }

    @Test
    @DisplayName("[KP-C2/F5] 멘티 관점 응답은 수수료 및 수령액 정보를 JSON 직렬화에서 제외한다 (멘티 가격 유지)")
    void menteeApplicationResponseOmitsFeeDetailsInJson() {
        MentoringApplication application = application();
        application.setAppliedMileage(10000L);
        application.setFeeRateBp(500);

        MentoringApplicationResponse response = MentoringApplicationResponse.fromMentee(
                application, false, true, false);
        JsonNode json = mapper.valueToTree(response);

        assertThat(json.has("feeRateBp")).isFalse();
        assertThat(json.has("feeAmount")).isFalse();
        assertThat(json.has("payoutAmount")).isFalse();
        assertThat(json.has("nonMemberNotice")).isFalse();
        assertThat(json.has("feeNotice")).isFalse();
        assertThat(json.get("appliedMileage").asLong()).isEqualTo(10000L);
    }

    private MentoringApplication application() {
        User mentorUser = user("mentor");
        MentorProfile mentor = new MentorProfile();
        mentor.setUser(mentorUser);
        MentoringProgram program = new MentoringProgram();
        program.setId(UUID.randomUUID());
        program.setMentor(mentor);
        program.setTitle("coaching");
        program.setPrice(10000L);
        MentoringApplication application = new MentoringApplication();
        application.setId(UUID.randomUUID());
        application.setProgram(program);
        application.setMentee(user("mentee"));
        application.setAppliedMileage(10000L);
        return application;
    }

    private User user(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}