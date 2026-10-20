package com.gamerin.backend.domain.r6.client;

import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.gamerin.backend.domain.r6.model.R6SummaryStats;

public interface R6StatsClient {

    R6Profile findProfile(String playerName);

    R6SummaryStats getSummary(R6ProfileRef profileRef);
}
