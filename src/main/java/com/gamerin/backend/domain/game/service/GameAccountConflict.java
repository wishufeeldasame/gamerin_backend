package com.gamerin.backend.domain.game.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum GameAccountConflict {
    R6("uq_user_profiles_connected_r6_account", "이미 다른 유저가 사용 중인 R6 계정입니다."),
    RIOT("uq_user_profiles_connected_riot_puuid", "이미 다른 유저가 연동한 Riot 계정입니다.");

    private final String constraintName;
    private final String message;

    GameAccountConflict(String constraintName, String message) {
        this.constraintName = constraintName;
        this.message = message;
    }

    public ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public RuntimeException translate(DataIntegrityViolationException failure) {
        // Only these unique indexes represent account ownership; other integrity failures remain server errors.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && constraintName.equals(violation.getConstraintName())) {
                return new ResponseStatusException(HttpStatus.CONFLICT, message, failure);
            }
        }
        return failure;
    }
}
