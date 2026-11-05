package com.gamerin.backend.domain.mention.model;

import java.util.List;

public record ParsedMention(
        String rawHandle,
        List<String> lookupCandidates
) {
    public ParsedMention {
        lookupCandidates = List.copyOf(lookupCandidates);
    }
}
