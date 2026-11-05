package com.gamerin.backend.domain.admin.dto.response;

import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 어드민 멘토링 프로그램 관리 목록 응답 DTO
 */
public record AdminMentoringProgramResponse(
        UUID id,
        String title,
        String game,
        String mentorHandle,
        String mentorNickname,
        Long price,
        long sessions,
        Double rating,
        long reports,
        ProgramStatus status,
        boolean isHidden,
        OffsetDateTime createdAt) {
    public static AdminMentoringProgramResponse of(
            MentoringProgram program,
            long sessions,
            Double rating,
            long reports) {
        return new AdminMentoringProgramResponse(
                program.getId(),
                program.getTitle(),
                program.getGameName(),
                program.getMentor().getUser().getHandle(),
                program.getMentor().getUser().getNickname(),
                program.getPrice(),
                sessions,
                rating,
                reports,
                program.getStatus(),
                program.getDeletedAt() != null,
                program.getCreatedAt());
    }
}