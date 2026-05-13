package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;

public record MentoringProgramDetailResponse(
    UUID id,
    String mentorNickname,
    String mentorAbout,
    String gameName,
    String title,
    String content,
    String availableTimeDesc,
    Long price,
    List<String> tags,
    OffsetDateTime createdAt

) {
    public static MentoringProgramDetailResponse from(MentoringProgram program) {
        return new MentoringProgramDetailResponse(
            program.getId(),
            program.getMentor().getUser().getNickname(),
            program.getMentor().getAbout(),
            program.getGameName(),
            program.getTitle(),
            program.getContent(),
            program.getAvailableTimeDesc(),
            program.getPrice(),
            program.getTags(),
            program.getCreatedAt()
        );
    }
}
