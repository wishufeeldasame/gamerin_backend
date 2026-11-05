package com.gamerin.backend.domain.post.entity;

/**
 * 업로드 시점 등급으로 정하는 게시물 영상 화질. 짧은 변 기준이라 세로 영상에도 같은 규칙이 적용된다.
 */
public enum VideoQualityTier {
    HD(720, "4M", "8M"),
    FHD(1080, "8M", "16M");

    private final int maxShortSide;
    private final String maxBitrate;
    private final String bufferSize;

    VideoQualityTier(int maxShortSide, String maxBitrate, String bufferSize) {
        this.maxShortSide = maxShortSide;
        this.maxBitrate = maxBitrate;
        this.bufferSize = bufferSize;
    }

    public int maxShortSide() {
        return maxShortSide;
    }

    public String maxBitrate() {
        return maxBitrate;
    }

    public String bufferSize() {
        return bufferSize;
    }
}
