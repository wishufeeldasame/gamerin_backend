package com.gamerin.backend.domain.pubg.exception;

public final class NoRankedRecordException extends RuntimeException {

    public NoRankedRecordException() {
        super("No ranked stats found for the selected mode.");
    }
}
