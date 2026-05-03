package com.gamerin.backend.domain.user.dto.request;

import jakarta.validation.constraints.Size;

public class UpdateProfileRequest {
    
    @Size(min = 2, max = 20, message = "닉네임은 2~20자 사이여야 합니다.")
    private String nickname;

    @Size(max = 160, message = "소개글은 160자를 초과할 수 없습니다.")
    private String bio;

    private String profileImageUrl;
    private String coverImageUrl;
    private String location;
    private String website;

    public UpdateProfileRequest() {
    }

    public UpdateProfileRequest(String nickname, String bio, String profileImageUrl, String coverImageUrl, String location, String website) {
        this.nickname = nickname;
        this.bio = bio;
        this.profileImageUrl = profileImageUrl;
        this.coverImageUrl = coverImageUrl;
        this.location = location;
        this.website = website;
    }

    public String getNickname() {
        return nickname;
    }

    public String getBio() {
        return bio;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public String getLocation() {
        return location;
    }

    public String getWebsite() {
        return website;
    }
}
