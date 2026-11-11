package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record MentoringProgramDetailResponse(
    UUID id,
    Long publicNo,
    UUID mentorId,
    String mentorNickname,
    String mentorAbout,
    String gameName,
    String title,
    String content,
    String availableTimeDesc,
    ProgramStatus status,
    Long price,
    List<String> tags,
    OffsetDateTime createdAt,
    boolean membershipBadge,
    RepresentativeTierResponse representativeTier
) {
    public static MentoringProgramDetailResponse from(MentoringProgram program, boolean membershipBadge) {
        return new MentoringProgramDetailResponse(
            program.getId(),
            program.getPublicNo(),
            program.getMentor().getId(),
            program.getMentor().getUser().getNickname(),
            program.getMentor().getAbout(),
            program.getGameName(),
            program.getTitle(),
            program.getContent(),
            program.getAvailableTimeDesc(),
            program.getStatus(),
            program.getPrice(),
            program.getTags(),
            program.getCreatedAt(),
            membershipBadge,
            RepresentativeTierResponse.from(program.getMentor().getUser().getProfile())
        );
    }
}
