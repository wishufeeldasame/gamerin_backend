package com.gamerin.backend.domain.user.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class SimpleUserProfileResponseTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void membershipIsExplicitInJsonAndIndependentOfVerifiedBadge(boolean active) {
        User user = User.createLocal("member@example.com", "member", "Member", "encoded");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setProfile(UserProfile.createDefault(user));
        ReflectionTestUtils.setField(user.getProfile(), "verifiedBadge", true);

        SimpleUserProfileResponse response = SimpleUserProfileResponse.from(user, active);
        JsonNode json = new ObjectMapper().valueToTree(response);

        assertThat(json.hasNonNull("membershipBadge")).isTrue();
        assertThat(json.get("membershipBadge").booleanValue()).isEqualTo(active);
        assertThat(json.get("verifiedBadge").booleanValue()).isTrue();
        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(response.handle()).isEqualTo("member");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void membershipDoesNotDependOnAnExistingProfile(boolean active) {
        User user = User.createLocal("member@example.com", "member", "Member", "encoded");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        SimpleUserProfileResponse response = SimpleUserProfileResponse.from(user, active);

        assertThat(response.membershipBadge()).isEqualTo(active);
        assertThat(response.verifiedBadge()).isFalse();
        assertThat(response.profileImageUrl()).isNull();
    }
}
