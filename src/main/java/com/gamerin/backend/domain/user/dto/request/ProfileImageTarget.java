package com.gamerin.backend.domain.user.dto.request;

public enum ProfileImageTarget {
    PROFILE("profile"),
    COVER("cover");

    private final String directorySegment;

    ProfileImageTarget(String directorySegment) {
        this.directorySegment = directorySegment;
    }

    public String getDirectorySegment() {
        return directorySegment;
    }
}
