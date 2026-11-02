package com.gamerin.backend.global.seed;

import java.util.List;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;

/**
 * 데모 시더가 사용하는 고정 문구 모음.
 * 멘션(@handle)과 해시태그(#tag) 뒤에는 파서가 이어지는 한글을 핸들·태그로 읽지 않도록 공백을 둔다.
 */
final class DemoSeedData {

    private DemoSeedData() {
    }

    record DemoUser(
            String handle,
            String nickname,
            String bio,
            String location,
            String pubgTier,
            String r6Tier,
            String lolTier
    ) {
    }

    /** 게시물 위치. user 번째 사용자의 round 번째 게시물. */
    record PostRef(int user, int round) {
    }

    /** sender 는 대화의 A(true, 대화를 시작한 사람) 또는 B(false). text 가 null 이면 sharedPost 만 보낸다. */
    record DmLine(boolean fromA, String text, PostRef sharedPost) {
    }

    record DmConversation(int a, int b, boolean lastRecipientReads, List<DmLine> lines) {
    }

    record DemoProgram(
            int mentor,
            GameType gameType,
            String title,
            String content,
            String availableTime,
            long price,
            List<String> tags
    ) {
    }

    record DemoApplication(
            int mentee,
            int program,
            String message,
            ApplicationStatus outcome,
            int rating,
            String review
    ) {
    }

    static final List<DemoUser> USERS = List.of(
            new DemoUser("chicken_hunter", "치킨사냥꾼",
                    "배그 5년차, 에란겔 밀베 착지 전문 🐔 입문자 멘토링도 하고 있어요.", "서울",
                    "Diamond", "Silver", "Gold IV"),
            new DemoUser("siege_owl", "새벽부엉이",
                    "레식 새벽반 수비 앵커. 클랜 '부엉이둥지' 운영 중 🦉", "인천",
                    "Bronze", "Diamond", "Silver II"),
            new DemoUser("jungle_maple", "단풍정글",
                    "롤 정글러. 올해 목표는 다이아 승급! 정글 동선 코칭합니다.", "대구",
                    "Silver", "Gold", "Emerald I"),
            new DemoUser("spike_planter", "스파이크장인",
                    "발로란트 감시자 메인 / 사이퍼 원챔. 셋업 연구가 취미예요.", "부산",
                    "Gold", "Platinum", "Platinum III"),
            new DemoUser("tracer_blink", "점멸요정",
                    "오버워치 트레이서 장인 지망생 ✨ 경쟁전 같이 하실 분!", "광주",
                    "Silver", "Bronze", "Gold II"),
            new DemoUser("smoke_wizard", "연막술사",
                    "CS2 미라지 연막 라인업 수집가. 요즘 발로도 찍먹 중.", "대전",
                    "Gold", "Gold", "Silver I"),
            new DemoUser("cozy_gamer", "주말겜러",
                    "평일엔 직장인, 주말엔 인디게임 탐험가. 스타듀밸리 농장주 🍓", "수원",
                    "Bronze", "Bronze", "Bronze I"),
            new DemoUser("mid_or_feed", "미드아니면탈주",
                    "미드 아리 300판. 듀오 정글 상시 모집합니다 🔥", "서울",
                    "Silver", "Silver", "Diamond IV"),
            new DemoUser("drop_zone", "낙하산조장",
                    "배그 오더 담당. 미라마 운전은 아직 연습 중 🚗", "울산",
                    "Platinum", "Silver", "Silver III"),
            new DemoUser("coach_hana", "하나코치",
                    "오버워치·발로란트 코치. 리플레이 분석으로 습관을 고쳐드려요 ☕", "성남",
                    "Gold", "Platinum", "Platinum I")
    );

    /** POSTS.get(user).get(round). 모든 사용자가 같은 수의 게시물을 가진다. */
    static final List<List<String>> POSTS = List.of(
            List.of(
                    "에란겔 밀리터리 베이스 낙하 성공률 드디어 70% 넘었습니다. 착지 직후 3초 안에 무기 줍는 게 핵심이에요. #배그 #PUBG #에란겔",
                    "솔로 스쿼드 4킬 치킨! 마지막 자기장이 밀밭이라 숨소리도 못 냈네요 🐔 #치킨 #PUBG",
                    "@drop_zone 오늘 밤 듀오 랭크 콜? 저번처럼 파밍만 하다 끝나지 말고 교전 좀 해봐요 ㅋㅋ #듀오모집"
            ),
            List.of(
                    "레식 신규 시즌 맵 돌려봤는데 2층 해치 위치가 너무 사기네요. 수비는 무조건 위층 장악부터. #레인보우식스 #R6",
                    "새벽 3시 랭크는 진짜 낭만이 있다. 1대3 클러치 성공해서 혼자 소리 질렀음 🦉 #새벽겜 #R6",
                    "클랜 '부엉이둥지'에서 수비 앵커 한 분 모십니다. 디코 필수, 매너 필수! 관심 있으면 DM 주세요. #클랜원모집 #R6"
            ),
            List.of(
                    "정글 동선 3캠프 버스트 vs 풀캠 고민이라면, 상대 정글 시작 위치부터 체크하세요. 그게 반은 먹고 들어갑니다. #롤 #정글 #LOL",
                    "에메랄드 1 승급전 3연패... 오늘은 여기까지. 내일은 무조건 다이아 간다 🍁 #롤 #승급전",
                    "@mid_or_feed 님 오늘 바텀 갱 세 번 다 받아주셔서 감사합니다. 미드 로밍 타이밍 완벽했어요! #듀오 #LOL"
            ),
            List.of(
                    "사이퍼 어센트 B사이트 원웨이 셋업 정리해봤습니다. 트립와이어는 문 안쪽 낮게, 카메라는 천장 구석이 국룰. #발로란트 #사이퍼",
                    "스파이크 해체 0.2초 남기고 성공... 심장이 아직도 뛰네요 ⏱️ #발로란트 #VALORANT",
                    "발로 신규 요원 써봤는데 궁극기 연계가 생각보다 좋아요. 다음 주 랭크에서 바로 써볼 예정. #발로란트 #신규요원"
            ),
            List.of(
                    "트레이서 점멸 3개 다 쓰고 역행 타이밍 잡는 연습 중. 연습용 커스텀 게임 코드 공유해드릴 수 있어요! #오버워치 #트레이서",
                    "오늘 경쟁전 5연승! 팀원들이랑 소통이 다 잘 돼서 너무 즐거웠다 ✨ #오버워치 #OVERWATCH",
                    "@coach_hana 코치님 피드백 듣고 나서 포지션 잡는 게 확 달라졌어요. 다음 세션도 기대됩니다! #오버워치 #멘토링"
            ),
            List.of(
                    "CS2 미라지 A사이트 연막 라인업 3종 정리. 정글, CT, 계단 순서로 던지면 진입이 훨씬 편해집니다. #CS2 #미라지",
                    "AK 스프레이 패턴 30분 루틴 시작한 지 2주째. 확실히 원탭 확률이 올라갔어요. #CS2 #에임연습",
                    "주말에 친구들이랑 5인 내전 했는데 연막 하나 잘못 던져서 우리 팀 시야를 다 가려버림 ㅋㅋㅋ #CS2 #내전"
            ),
            List.of(
                    "퇴근하고 스타듀밸리 한 시간. 오늘은 딸기 수확하고 낚시 대회까지 끝냈어요 🍓 #스타듀밸리 #힐링겜",
                    "주말 계획: 라면 끓여놓고 인디게임 엔딩 두 개 보기. 짧고 재밌는 게임 추천 받습니다! #인디게임 #주말",
                    "모니터 받침대 바꿨더니 목이 덜 아프네요. 오래 게임하시는 분들 데스크 셋업 꼭 신경 쓰세요. #데스크셋업 #일상"
            ),
            List.of(
                    "미드 아리 300판 기념. 매혹 적중률 올리는 팁은 '상대 점멸 빠진 직후'입니다. #롤 #아리",
                    "@jungle_maple 정글님 덕분에 오늘 미드 3렙 갱 성공! 듀오 계속 갑시다 🔥 #롤 #듀오",
                    "칼바람 나락 연속 10판 했는데 한 번도 원하는 챔프가 안 나옴... 그래도 재밌다 #칼바람 #LOL"
            ),
            List.of(
                    "미라마 차량 운전 꿀팁: 언덕 넘을 때 공중에서 Ctrl 누르면 착지가 안정적이에요. 차 터지는 건 이제 그만 🚗 #배그 #미라마",
                    "@chicken_hunter 오늘 듀오 ㄱㄱ! 이번엔 제가 오더할게요. 사녹 가보죠. #PUBG #듀오",
                    "배그 신규 맵 파밍 동선 짜는 중. 보급 떨어지는 위치가 생각보다 한쪽으로 몰려요. #배그 #신규맵"
            ),
            List.of(
                    "이번 주 멘토링 피드백 공통점: 대부분 '죽은 위치'보다 '죽기 직전 3초'가 문제였어요. 리플레이 볼 때 그 구간만 반복해보세요. #멘토링 #코칭",
                    "오버워치 탱커 입문자분들께: 방벽은 아끼는 게 아니라 쿨을 돌리는 겁니다. #오버워치 #탱커",
                    "오늘은 쉬는 날. 카페에서 게임 OST 들으면서 다음 커리큘럼 정리 중 ☕ #일상 #게임OST"
            )
    );

    /** COMMENTS.get(user).get(round) 는 해당 게시물에 (user + round + 1) % USERS 번째 사용자가 남기는 댓글이다. */
    static final List<List<String>> COMMENTS = List.of(
            List.of(
                    "밀베 70%면 거의 고인물 아닌가요 ㄷㄷ 저는 착지하자마자 눕는데...",
                    "밀밭 자기장 최악이죠 ㅋㅋ 치킨 축하드려요!",
                    "두 분 듀오 하시면 저도 관전 좀 시켜주세요 👀"
            ),
            List.of(
                    "해치 위치 공감합니다. 공격할 때 드론부터 올려야겠네요.",
                    "1대3 클러치면 소리 지를 만하죠! 클립 올려주세요",
                    "레식 입문해보고 싶었는데 매너 클랜이라니 끌리네요."
            ),
            List.of(
                    "시작 위치 체크 팁 좋네요. 발로에서도 상대 시작 루트 읽는 게 중요해서 공감돼요.",
                    "승급전 3연패는 쉬어가라는 신호예요. 내일은 꼭 다이아!",
                    "미드-정글 호흡 좋은 듀오 부럽습니다 👍"
            ),
            List.of(
                    "원웨이 위치 저장해갑니다. 사이퍼 너무 어렵던데 감사해요!",
                    "CS에서도 그런 해체하면 손 떨리던데... 고생하셨어요 ㅋㅋ",
                    "신규 요원 후기 기다릴게요!"
            ),
            List.of(
                    "커스텀 코드 저도 부탁드려요. 점멸 연습 같이 해보고 싶네요.",
                    "5연승 축하해요 🎉 소통 잘 되는 판이 제일 재밌죠.",
                    "@coach_hana 코치님 피드백 저도 받아보고 싶네요."
            ),
            List.of(
                    "연막 순서까지 정리해주시다니 친절하시다... 저장!",
                    "2주 만에 효과 보셨다니 저도 루틴 따라 해볼게요.",
                    "아군 시야 가리는 연막 ㅋㅋㅋ 배그 연막도 똑같아요"
            ),
            List.of(
                    "스타듀 딸기 시즌이군요! 저도 오랜만에 농장 가봐야겠다",
                    "하데스 추천드려요. 한 판이 짧아서 주말용으로 딱이에요!",
                    "자세 정말 중요해요. 손목 받침대도 같이 추천드립니다."
            ),
            List.of(
                    "300판 장인이시라니... 매혹 팁 바로 써먹겠습니다",
                    "미드-정글 3렙 갱 깔끔하네요. 와드 위치도 궁금합니다!",
                    "칼바람은 원하는 챔프 안 나오는 맛이죠 ㅋㅋ"
            ),
            List.of(
                    "Ctrl 착지 팁 처음 알았어요. 감사합니다!",
                    "@drop_zone 좋아요 사녹 콜! 9시에 접속할게요",
                    "보급 위치 정리되면 공유 부탁드려요"
            ),
            List.of(
                    "죽기 직전 3초... 제 리플레이 다시 봐야겠네요. 좋은 팁 감사합니다.",
                    "방벽은 쿨 돌리는 것, 명언이네요.",
                    "게임 OST 플레이리스트 공유해주세요 ☕"
            )
    );

    static final List<String> BOOKMARK_COLLECTIONS = List.of("공략 모음", "나중에 볼 글");

    static final List<DmConversation> CONVERSATIONS = List.of(
            new DmConversation(0, 8, true, List.of(
                    a("오늘 밤 9시에 듀오 가능하세요?"),
                    b("넵! 사녹 가기로 한 거 맞죠?"),
                    aShare(8, 0),
                    a("이 팁대로 이번엔 차 터뜨리지 말기 ㅋㅋ"),
                    b("그건 장담 못 합니다... 🚗💥")
            )),
            new DmConversation(4, 9, false, List.of(
                    a("코치님 안녕하세요! 지난 세션 정말 도움 됐어요."),
                    b("다행이에요 😊 커스텀 연습은 꾸준히 하고 계세요?"),
                    a("하루 20분씩 하고 있어요. 다음 세션은 언제 가능하세요?"),
                    b("목요일 저녁 8시 어떠세요? 리플레이 하나 준비해 오세요.")
            )),
            new DmConversation(4, 1, true, List.of(
                    a("클랜원 모집 글 보고 연락드려요! 레식 완전 초보인데 괜찮을까요?"),
                    b("그럼요! 매너만 있으면 됩니다. 디코 아이디 알려주세요"),
                    a("넵, 따로 보내드릴게요. 주로 몇 시에 하세요?"),
                    b("보통 밤 12시 이후 새벽반이에요 🦉")
            )),
            new DmConversation(2, 7, false, List.of(
                    a("오늘도 듀오 ㄱ?"),
                    b("ㄱㄱ 근데 승급전 3연패라면서요 괜찮으세요? ㅋㅋ"),
                    a("괜찮아요... 오늘은 다를 겁니다"),
                    bShare(7, 0),
                    b("제 아리 믿고 따라오세요")
            )),
            new DmConversation(5, 3, true, List.of(
                    a("발로에도 연막 라인업 같은 게 있나요? CS만 하다가 넘어가보려고요"),
                    b("브림스톤이나 오멘 하시면 비슷해요! 제 셋업 글도 참고해보세요"),
                    bShare(3, 0),
                    a("오 정리 깔끔하네요. 이번 주말에 바로 해볼게요")
            )),
            new DmConversation(6, 0, false, List.of(
                    a("배그 입문하려는데 기초반 신청해도 될까요? 완전 뉴비예요"),
                    b("물론이죠! 기초반은 뉴비 위주라 부담 없이 오세요 😄"),
                    a("감사합니다. 마우스 감도는 어떻게 맞추면 좋을까요?"),
                    b("일단 800DPI 기준으로 맞추고 세션 때 같이 조정해봐요")
            )),
            new DmConversation(9, 2, true, List.of(
                    a("정글 코칭 커리큘럼 너무 좋더라구요. 공동 세미나 해보실 생각 있으세요?"),
                    b("와 좋아요! 오버워치랑 롤 운영 비교하면 재밌을 것 같아요"),
                    a("다음 달 초에 일정 맞춰봐요 📅")
            )),
            new DmConversation(8, 1, false, List.of(
                    a("새벽 클러치 글 잘 봤어요 ㅋㅋ 1대3 미쳤던데요"),
                    b("감사합니다 ㅎㅎ 배그만 하시는 줄 알았는데 레식도 하세요?"),
                    a("요즘 조금씩 배우는 중이에요. 언제 같이 한 판 해요!"),
                    bShare(1, 1),
                    b("이 판 리플레이 같이 보면서 설명해드릴게요")
            ))
    );

    /** 멘토 사용자 번호와 멘토 소개. 각 멘토는 PROGRAMS 에 프로그램을 가진다. */
    static final List<Integer> MENTORS = List.of(0, 1, 2, 3, 9);

    static final List<String> MENTOR_ABOUTS = List.of(
            "배그 5년차, 시즌 최고 티어 다이아. 생존 기본기와 솔로 스쿼드 교전을 가르칩니다.",
            "레식 다이아 수비 앵커. 맵별 보강·함정 배치와 정보전을 알려드려요.",
            "롤 에메랄드 정글러. 동선 설계와 오브젝트 타이밍 코칭 전문입니다.",
            "발로란트 감시자 원챔. 사이트별 셋업과 에임 루틴을 함께 만듭니다.",
            "오버워치·발로란트 코치 3년차. 리플레이 분석으로 포지션 습관을 교정합니다."
    );

    static final List<DemoProgram> PROGRAMS = List.of(
            new DemoProgram(0, GameType.PUBG, "배그 입문자 생존 기초반",
                    "착지 지점 선정부터 파밍 동선, 자기장 운영까지 첫 치킨을 위한 기본기를 함께 다집니다. 뉴비 환영!",
                    "평일 저녁 8시 이후", 8_000L, List.of("입문", "생존", "파밍")),
            new DemoProgram(0, GameType.PUBG, "솔로 스쿼드 교전 집중 코칭",
                    "1대다 상황에서 엄폐물 활용과 교전 각 잡는 법을 리플레이 분석으로 피드백합니다.",
                    "주말 오후 협의", 15_000L, List.of("교전", "리플레이분석")),
            new DemoProgram(1, GameType.R6, "레식 수비 앵커 마스터",
                    "사이트별 보강·함정 배치와 앵커 포지션을 맵별로 정리해 드립니다.",
                    "새벽 12시~2시", 12_000L, List.of("수비", "앵커", "맵리딩")),
            new DemoProgram(1, GameType.R6, "드론 & 정보전 기초",
                    "공격 시작 30초 드론 운용과 콜아웃 방법을 실전 커스텀으로 연습합니다.",
                    "평일 밤 11시 이후", 10_000L, List.of("드론", "콜아웃")),
            new DemoProgram(2, GameType.LOL, "정글 동선 설계 1:1",
                    "상대 정글 시작 위치를 읽고 첫 갱·오브젝트 타이밍을 잡는 동선을 함께 설계합니다.",
                    "평일 저녁 9시", 13_000L, List.of("정글", "동선", "운영")),
            new DemoProgram(2, GameType.LOL, "승급전 멘탈 & 운영 클리닉",
                    "연패를 끊는 밴픽 전략과 후반 운영 판단을 리플레이로 점검합니다.",
                    "주말 협의", 9_000L, List.of("승급전", "멘탈관리")),
            new DemoProgram(3, GameType.VALORANT, "감시자 요원 셋업 클래스",
                    "사이퍼·킬조이 사이트별 원웨이와 셋업을 맵 3개 기준으로 알려드립니다.",
                    "평일 저녁 7시~10시", 11_000L, List.of("감시자", "셋업", "사이퍼")),
            new DemoProgram(3, GameType.VALORANT, "에임 루틴 2주 챌린지",
                    "사격장 루틴과 데스매치 활용법으로 2주간 에임을 관리해 드립니다.",
                    "매일 30분 온라인 체크", 20_000L, List.of("에임", "루틴")),
            new DemoProgram(9, GameType.OVERWATCH, "오버워치 포지셔닝 코칭",
                    "죽기 직전 3초를 중심으로 리플레이를 분석해 포지션 습관을 교정합니다.",
                    "평일 저녁 8시", 14_000L, List.of("포지셔닝", "리플레이분석")),
            new DemoProgram(9, GameType.OVERWATCH, "트레이서·겐지 돌격 딜러 심화",
                    "점멸·역행 쿨 관리와 뒷라인 진입 타이밍을 커스텀 게임으로 연습합니다.",
                    "주말 오전 협의", 16_000L, List.of("트레이서", "딜러", "심화"))
    );

    /** 사용자마다 한 건씩 신청하며, 신청 상태 7종이 모두 나오도록 결과를 정한다. */
    static final List<DemoApplication> APPLICATIONS = List.of(
            new DemoApplication(0, 6, "사이퍼 처음 해보는데 셋업이 너무 어려워요. 어센트 위주로 배우고 싶습니다.",
                    ApplicationStatus.COMPLETED, 5, "맵별 셋업을 그림까지 그려가며 알려주셔서 바로 랭크에서 써먹었어요!"),
            new DemoApplication(1, 0, "레식만 하다가 배그 입문하려 합니다. 기초부터 부탁드려요!",
                    ApplicationStatus.COMPLETED, 4, "파밍 동선 정리가 특히 좋았어요. 자기장 운영도 감이 잡혔습니다."),
            new DemoApplication(2, 8, "롤만 하다가 오버워치 시작했는데 자꾸 끊겨요. 포지션 교정 받고 싶습니다.",
                    ApplicationStatus.APPLIED, 0, null),
            new DemoApplication(3, 4, "발로 하다가 롤 정글 해보려는데 동선이 감이 안 와요.",
                    ApplicationStatus.FINISHED, 0, null),
            new DemoApplication(4, 9, "트레이서 300시간인데 역행 타이밍이 늘 늦습니다. 심화 코칭 부탁드려요.",
                    ApplicationStatus.COMPLETED, 5, "커스텀 코드까지 만들어 주셔서 연습이 정말 편했어요. 강력 추천!"),
            new DemoApplication(5, 2, "CS2 하다 레식 왔는데 수비 보강 위치를 전혀 모르겠어요.",
                    ApplicationStatus.ONGOING, 0, null),
            new DemoApplication(6, 0, "완전 뉴비인데 첫 치킨 먹어보고 싶어요! 천천히 알려주세요 🙏",
                    ApplicationStatus.ACCEPTED, 0, null),
            new DemoApplication(7, 5, "승급전만 가면 손이 떨려요... 멘탈 관리법 배우고 싶습니다.",
                    ApplicationStatus.REJECTED, 0, null),
            new DemoApplication(8, 1, "솔로 스쿼드 교전에서 자꾸 지는데 리플레이 봐주실 수 있나요?",
                    ApplicationStatus.CANCELLED, 0, null),
            new DemoApplication(9, 3, "코치로서 레식 정보전 운영 방식을 배워보고 싶어요.",
                    ApplicationStatus.APPLIED, 0, null)
    );

    private static DmLine a(String text) {
        return new DmLine(true, text, null);
    }

    private static DmLine b(String text) {
        return new DmLine(false, text, null);
    }

    private static DmLine aShare(int user, int round) {
        return new DmLine(true, null, new PostRef(user, round));
    }

    private static DmLine bShare(int user, int round) {
        return new DmLine(false, null, new PostRef(user, round));
    }
}
