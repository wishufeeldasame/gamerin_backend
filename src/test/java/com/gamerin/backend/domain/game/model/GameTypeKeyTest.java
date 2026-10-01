package com.gamerin.backend.domain.game.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 전적 JSON 키·API 응답이 의존하는 코드 값이 바뀌지 않도록 고정한다. */
class GameTypeKeyTest {

    @Test
    void statsKeyCodesStayStable() {
        assertThat(GameType.PUBG.name()).isEqualTo("PUBG");
        assertThat(GameType.R6.name()).isEqualTo("R6");
        assertThat(GameType.LOL.name()).isEqualTo("LOL");
    }
}
