package com.gamerin.backend.domain.mentoring.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
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
    @ValueSource(booleans = {true, false})
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
        assertThat(json.has("message")).isTrue();
        assertThat(json.get("message").isNull()).isTrue();
        assertThat(json.get("reviewed").booleanValue()).isFalse();
    }

    private MentoringApplication application() {
        User mentorUser = user("mentor");
        MentorProfile mentor = new MentorProfile();
        mentor.setUser(mentorUser);
        MentoringProgram program = new MentoringProgram();
        program.setId(UUID.randomUUID());
        program.setMentor(mentor);
        program.setTitle("coaching");
        MentoringApplication application = new MentoringApplication();
        application.setId(UUID.randomUUID());
        application.setProgram(program);
        application.setMentee(user("mentee"));
        return application;
    }

    private User user(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
