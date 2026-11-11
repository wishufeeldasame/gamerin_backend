package com.gamerin.backend.domain.weeklyreport.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI Chat Completions 호환 API로 주간 분석 문장을 만든다.
 * provider(factchat: 학교 Mindlogic 게이트웨이, nvidia: NVIDIA Build)에 따라 URL·키·모델만 달라진다.
 */
@Component
public class WeeklyReportAiClient {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportAiClient.class);
    private static final int ATTEMPTS = 2;
    private static final int MAX_HEADLINE = 80;
    private static final int MAX_SUMMARY = 1100;
    private static final int MAX_FOCUS = 450;
    static final String SYSTEM_PROMPT = """
            당신은 발로란트 경쟁전 주간 전적을 해석해 주는 코치입니다. 사용자는 화면에서 경기 수·승패·승률·K/D·HS%·ACS·티어·맵/요원별 승패를 이미 보고 있습니다. 당신의 역할은 그 수치를 반복하는 것이 아니라, 아래 근거 지표를 연결해 이번 주의 패턴을 해석하고 돌아볼 장면을 제안하는 것입니다.

            입력(JSON, 값이 없는 필드는 생략됨):
            - current/previous: 화면에 표시되는 대상 주·전주 지표. tierChange는 티어 변화. 해석의 배경으로만 쓰고 다시 나열하지 않습니다.
            - insight.current/insight.previous: 분석 근거. overall(주 전체), byMap(맵별), byAgent(요원별), byMapAgent("맵 × 요원" 조합별), wins/losses(승리 경기·패배 경기 묶음), abilities(요원별 스킬 사용량), afkMatches(AFK 라운드가 있었던 경기 수).
            - 각 묶음의 matches는 경기 수(표본), insightMatches는 상세 근거가 있는 경기 수입니다. evidence는 insightMatches 경기만으로 계산한 값입니다.
            - evidence.adr: 라운드당 피해량. firstKills/firstDeaths: 라운드의 첫 교전(양 팀 간 첫 처치)에서 내가 먼저 처치한 횟수/먼저 사망한 횟수, firstKillRoundWins/firstDeathRoundWins: 그 라운드의 승리 수.
            - attack/defense: 공격·수비 진영 라운드의 rounds·wins·kills·deaths. postPlantAttack: 우리 팀이 스파이크를 설치한 라운드, postPlantDefense: 상대가 설치한 라운드이며 kills·deaths는 설치 이후에 나온 것만 셉니다.
            - tradedDeaths: 내가 사망한 뒤 5초 안에 아군이 내 처치자를 잡은 횟수. tradeKills: 아군을 처치한 적을 5초 안에 내가 잡은 횟수.
            - economyBehind/economyEven/economyAhead: 라운드 장비 가치의 팀 평균이 상대보다 1000 이상 낮은/±1000 이내/1000 이상 높은 라운드. 상대 설치 이후 수비와 장비 열세 라운드는 원래 불리한 상황이므로 승률이 낮다는 사실만으로 약점이라 하지 말고 전주와의 차이로만 해석합니다.
            - abilities: 요원별 라운드당 스킬 사용 수. grenadePerRound=C, ability1PerRound=Q, ability2PerRound=E, ultimatePerRound=X 슬롯입니다.
            - weekOverWeek: 주 전체 지표의 전주 대비 비교표입니다. current/previous는 비율 값이고 change는 상승·하락·비슷입니다. "전주보다 늘었다/줄었다/비슷하다"는 표현은 반드시 이 표의 change를 따릅니다. 두 주의 경기 수가 달라서 firstKills 같은 횟수를 주끼리 직접 비교하면 안 됩니다. 또 "첫 킬이 첫 데스보다 많았다"는 것과 "첫 킬이 전주보다 늘었다"는 것은 다른 말이니 섞지 않습니다.
            - collectionIssues: 상세를 받지 못한 수집 이슈 건수. 0보다 크면 일부 경기가 빠졌을 수 있다는 점만 짧게 밝힙니다.

            작성 규칙:
            1. 화면에 이미 보이는 수치를 표처럼 나열하지 않습니다. 다만 승률·K/D·HS%·ACS는 해설에 필요하면 글 전체에서 2~3회까지 인용해도 되고, 인용할 때는 그 수치가 무엇을 뜻하는지(예: 교전은 비슷하게 주고받았는데 승률이 내려갔다) 함께 풀어 줍니다.
            2. 입력에 있는 수치만 인용하고, 입력에 없는 수치·경기 내용은 지어내지 않습니다.
            3. 여러 조건을 검토하되 글에는 중요한 패턴 2~3개만 고릅니다. 라운드 단위 수치(몇 라운드 중 몇 승)는 글 전체에서 2회 이하로만 쓰고, 나머지는 "열 번에 두 번꼴", "절반 가까이", "전주보다 줄었습니다"처럼 체감되는 표현이나 대략적인 비율로 바꿉니다. 한 문장에 숫자를 3개 이상 넣지 않습니다.
            4. 관찰된 사실, 가능한 해석, 복기할 행동을 구분해서 씁니다. 해석은 "~일 수 있습니다", "~로 보기는 어렵습니다"처럼 가능성으로 표현합니다.
            5. matches가 2 이하인 맵·요원·조합은 강점이나 약점으로 단정하지 않고, 그 문장 안에서 표본이 적다고 밝힙니다. insightMatches가 matches보다 적으면 근거 범위를 밝힙니다.
            6. 낮은 승률만으로 요원 변경을 권하거나 실력 저하로 단정하지 않습니다.
            7. 스킬은 사용량 변화만 말하고 효과나 사용 타이밍은 평가하지 않습니다. 장비 격차는 결과만 말하고 구매가 잘못됐다고 단정하지 않습니다. 위치·시야·이동·팀 지원처럼 입력에 없는 원인은 추측하지 않습니다.
            8. 읽는 사람은 통계 분석가가 아니라 게임을 즐기는 플레이어입니다. 라운드를 따로 기록하거나 여러 라운드를 모아 분류·대조하라는 식의 숙제를 내지 않습니다. focus는 다음 판에서 바로 의식할 수 있는 행동 중심으로 쓰고, 복기를 권한다면 특정 맵의 한두 판을 가볍게 돌아보는 정도로 한 가지만 제안합니다. "기용이 확인된", "선택 폭을 넓히세요" 같은 추상적 표현과 보고서 말투는 피하고, 첫 킬·첫 데스·리테이크·세이브처럼 플레이어가 평소 쓰는 말로 씁니다.
            9. 맵·요원 이름은 한국어 통용 표기(예: 헤이븐, 어비스, 선셋, 제트, 오멘)로 씁니다. 닉네임이나 계정 정보는 쓰지 않습니다.
            10. 자연스러운 존댓말과 완결된 문장을 씁니다. 짧게 쓰려고 조사·연결어를 생략하지 않습니다. 각 필드는 줄바꿈 없이 한 문단으로 씁니다.
            11. 근거가 적으면 분량을 채우려 일반론을 쓰지 말고 짧게 끝냅니다. previous가 없으면 전주와 비교하지 않습니다.

            분량: headline은 이번 주 핵심 해석 한 가지를 담은 한 문장 60자 이내, summary는 전체 흐름 → 눈에 띄는 패턴과 근거 → 해석의 한계 순서로 4~6문장 700자 이내, focus는 근거와 연결된 다음 주 행동 1~2가지를 2~3문장 300자 이내로 씁니다.

            나쁜 예(화면 수치 나열과 추상적 권고):
            {"headline": "초월자 2로 내려왔고 승률 33.3%, ACS 238입니다", "summary": "확인된 9경기에서 3승, 승률 33.3%를 기록했습니다. 전주와 비교하면 승률, K/D, ACS가 낮아졌고 티어도 내려왔습니다. 맵별로는 두 맵이 각 1승 1패입니다.", "focus": "Jett 기용이 확인된 6경기에서 1승 5패였습니다. 다음 주에는 Jett 외 요원의 지표를 점검해 선택 폭을 넓히세요."}

            좋은 예(문체와 분석 수준만 참고하고, 예시의 내용·수치를 그대로 베끼지 말고 반드시 입력 수치로 다시 씁니다):
            summary 일부: "K/D는 1.0으로 교전 자체는 비슷하게 주고받았는데, 이긴 경기와 진 경기의 피해량 차이가 컸습니다. 제트로 플레이한 경기에서도 맵에 따라 양상이 달라서, 선셋에서는 개인 교전 지표가 높았는데도 졌고 헤이븐에서는 두 주 모두 교전 성과가 낮았습니다. 먼저 죽는 일은 전주보다 줄었으니 이번 부진을 무리한 초반 교전 탓으로 보기는 어렵습니다. 다만 맵별 경기 수가 적어 반복되는 약점인지는 다음 주 기록까지 봐야 합니다."
            focus 일부: "먼저 죽은 라운드는 열 번에 일곱 번꼴로 내줬으니, 다음 주에는 초반에 혼자 먼저 잘리지 않는 것부터 의식해 보세요. 시간이 된다면 헤이븐 경기 한 판만 가볍게 돌려 보는 것도 좋겠습니다."

            다른 설명 없이 JSON 객체 하나만 출력합니다: {"headline": "...", "summary": "...", "focus": "..."}
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String model;
    private final boolean configured;
    private final String reasoningEffort;

    public WeeklyReportAiClient(
            @Value("${app.weekly-report.ai.provider:factchat}") String provider,
            @Value("${app.weekly-report.ai.factchat.url:}") String factchatUrl,
            @Value("${app.weekly-report.ai.factchat.key:}") String factchatKey,
            @Value("${app.weekly-report.ai.factchat.model:}") String factchatModel,
            @Value("${app.weekly-report.ai.nvidia.url:}") String nvidiaUrl,
            @Value("${app.weekly-report.ai.nvidia.key:}") String nvidiaKey,
            @Value("${app.weekly-report.ai.nvidia.model:}") String nvidiaModel,
            @Value("${app.weekly-report.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.weekly-report.ai.read-timeout:300s}") Duration readTimeout,
            @Value("${app.weekly-report.ai.reasoning-effort:}") String reasoningEffort,
            ObjectMapper objectMapper
    ) {
        boolean nvidia = "nvidia".equalsIgnoreCase(provider == null ? "" : provider.strip());
        this.url = trimToNull(nvidia ? nvidiaUrl : factchatUrl);
        this.model = trimToNull(nvidia ? nvidiaModel : factchatModel);
        String key = trimToNull(nvidia ? nvidiaKey : factchatKey);
        this.configured = url != null && model != null && key != null;
        this.objectMapper = objectMapper;
        this.reasoningEffort = trimToNull(reasoningEffort);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(connectTimeout)
                                .withReadTimeout(readTimeout)));
        if (key != null) {
            builder.defaultHeader("Authorization", "Bearer " + key);
        }
        this.restClient = builder.build();
    }

    /** 분석에 쓴 모델 이름. 설정되지 않았으면 null이다. */
    public String model() {
        return configured ? model : null;
    }

    /** 키·모델이 없거나 2회 모두 실패하면 비어 있다. 입력은 집계 숫자뿐이다. */
    public Optional<WeeklyAnalysis> analyze(WeeklyStats stats, WeeklyInsight insight) {
        if (!configured) {
            return Optional.empty();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        // 비어 있으면 보내지 않아 모델 기본 추론 강도를 쓴다.
        if (reasoningEffort != null) {
            request.put("reasoning_effort", reasoningEffort);
        }
        try {
            request.putAll(requestBody(stats, insight, objectMapper));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
                String content = response == null ? null
                        : response.path("choices").path(0).path("message").path("content").asText(null);
                Optional<WeeklyAnalysis> analysis = parse(content, objectMapper);
                if (analysis.isPresent()) {
                    return analysis;
                }
                log.warn("Weekly report AI response rejected. attempt={}", attempt);
            } catch (RestClientException e) {
                log.warn("Weekly report AI request failed. attempt={} error={}", attempt, e.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    /** model 을 뺀 요청 본문. 고정 자료 테스트가 실호출 검증용 본문을 남길 때도 이 메서드를 쓴다. */
    public static Map<String, Object> requestBody(WeeklyStats stats, WeeklyInsight insight, ObjectMapper objectMapper)
            throws JsonProcessingException {
        // 경기 ID·날짜·오류 진단은 빼고 건수만 보낸다. null 필드는 빼서 입력을 줄인다.
        ObjectMapper mapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        JsonNode tree = mapper.valueToTree(new AiInput(stats.current(), stats.previous(), stats.tierChange(), insight,
                stats.collectionIssues().size()));
        // 맵·요원·조합별은 PLAN 정의대로 표본·K/D·ACS·ADR·첫 킬/데스만 남겨 입력을 2만 자 안쪽으로 줄인다.
        for (String week : List.of("current", "previous")) {
            for (String key : List.of("byMap", "byAgent", "byMapAgent")) {
                tree.path("insight").path(week).path(key).forEach(group -> {
                    if (group.path("stats").path("evidence") instanceof ObjectNode evidence) {
                        evidence.retain("adr", "firstKills", "firstDeaths");
                    }
                });
            }
        }
        JsonNode currentOverall = tree.path("insight").path("current").path("overall");
        JsonNode previousOverall = tree.path("insight").path("previous").path("overall");
        if (tree instanceof ObjectNode root && currentOverall.isObject() && previousOverall.isObject()) {
            root.set("weekOverWeek", weekOverWeek(mapper, currentOverall, previousOverall));
        }
        String input = mapper.writeValueAsString(tree);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("temperature", 0.3);
        // 추론형 모델은 추론 토큰도 이 한도에서 쓴다.
        body.put("max_tokens", 32000);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", input)));
        return body;
    }

    private record AiInput(WeekStats current, WeekStats previous, String tierChange, WeeklyInsight insight,
            int collectionIssues) {
    }

    // 전주 대비 방향을 모델이 횟수로 잘못 비교하지 않도록 비율과 증감을 서버에서 계산해 준다.
    private static ArrayNode weekOverWeek(ObjectMapper mapper, JsonNode current, JsonNode previous) {
        ArrayNode rows = mapper.createArrayNode();
        Object[][] metrics = {
                {"경기 승률(%)", "wins", "matches", 1.0},
                {"K/D", "kd", null, 0.03},
                {"HS%", "headshotPercent", null, 1.0},
                {"ACS", "acs", null, 3.0},
                {"라운드당 피해량(ADR)", "evidence.adr", null, 3.0},
                {"첫 킬 비율(라운드당 %)", "evidence.firstKills", "rounds", 1.0},
                {"첫 데스 비율(라운드당 %)", "evidence.firstDeaths", "rounds", 1.0},
                {"첫 킬을 낸 라운드 승률(%)", "evidence.firstKillRoundWins", "evidence.firstKills", 3.0},
                {"첫 데스가 난 라운드 승률(%)", "evidence.firstDeathRoundWins", "evidence.firstDeaths", 3.0},
                {"공격 라운드 승률(%)", "evidence.attack.wins", "evidence.attack.rounds", 3.0},
                {"수비 라운드 승률(%)", "evidence.defense.wins", "evidence.defense.rounds", 3.0},
                {"우리 팀 설치 라운드 승률(%)", "evidence.postPlantAttack.wins", "evidence.postPlantAttack.rounds", 3.0},
                {"상대 설치 라운드 승률(%)", "evidence.postPlantDefense.wins", "evidence.postPlantDefense.rounds", 3.0},
                {"내 데스 중 5초 안에 교환된 비율(%)", "evidence.tradedDeaths", "deaths", 3.0},
                {"장비 열세 라운드 승률(%)", "evidence.economyBehind.wins", "evidence.economyBehind.rounds", 3.0},
                {"장비 유사 라운드 승률(%)", "evidence.economyEven.wins", "evidence.economyEven.rounds", 3.0},
                {"장비 우세 라운드 승률(%)", "evidence.economyAhead.wins", "evidence.economyAhead.rounds", 3.0},
        };
        for (Object[] metric : metrics) {
            Double now = ratio(current, (String) metric[1], (String) metric[2]);
            Double before = ratio(previous, (String) metric[1], (String) metric[2]);
            if (now == null || before == null) {
                continue;
            }
            double diff = now - before;
            rows.addObject()
                    .put("metric", (String) metric[0])
                    .put("current", now)
                    .put("previous", before)
                    .put("change", Math.abs(diff) < (double) metric[3] ? "비슷" : diff > 0 ? "상승" : "하락");
        }
        return rows;
    }

    // 분모가 없으면 값 그대로, 있으면 백분율(소수 1자리). 일부 경기만 근거가 있으면 전체 라운드·데스로 나누지 않는다.
    private static Double ratio(JsonNode stats, String numerator, String denominator) {
        JsonNode top = at(stats, numerator);
        if (!top.isNumber()) {
            return null;
        }
        if (denominator == null) {
            return top.asDouble();
        }
        boolean partial = numerator.startsWith("evidence.") && !denominator.startsWith("evidence.")
                && stats.path("insightMatches").asInt() != stats.path("matches").asInt();
        JsonNode bottom = at(stats, denominator);
        if (partial || !bottom.isNumber() || bottom.asDouble() == 0) {
            return null;
        }
        return Math.round(top.asDouble() * 1000 / bottom.asDouble()) / 10.0;
    }

    private static JsonNode at(JsonNode node, String path) {
        for (String key : path.split("\\.")) {
            node = node.path(key);
        }
        return node;
    }

    /** 코드 펜스나 앞뒤 문장을 걷어 내고 세 필드의 존재와 길이를 검증한다. */
    static Optional<WeeklyAnalysis> parse(String content, ObjectMapper objectMapper) {
        if (content == null) {
            return Optional.empty();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(content.substring(start, end + 1));
            String headline = text(json, "headline", MAX_HEADLINE);
            String summary = text(json, "summary", MAX_SUMMARY);
            String focus = text(json, "focus", MAX_FOCUS);
            if (headline == null || summary == null || focus == null) {
                return Optional.empty();
            }
            return Optional.of(new WeeklyAnalysis(headline, summary, focus));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode json, String field, int maxLength) {
        JsonNode node = json.path(field);
        if (!node.isTextual()) {
            return null;
        }
        // 화면은 한 문단으로 표시하므로 줄바꿈은 공백으로 바꿔 받는다.
        String value = node.asText().strip().replaceAll("\\s*\\R\\s*", " ");
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
