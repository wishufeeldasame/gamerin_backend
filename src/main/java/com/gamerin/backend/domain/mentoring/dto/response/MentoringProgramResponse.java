package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record MentoringProgramResponse(
    UUID id,
    Long publicNo,
    UUID mentorId,
    String mentorNickName,
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
    public static MentoringProgramResponse from(MentoringProgram program, boolean membershipBadge) {
        return new MentoringProgramResponse(
            program.getId(),
            program.getPublicNo(),
            program.getMentor().getId(),
            program.getMentor().getUser().getNickname(),
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
