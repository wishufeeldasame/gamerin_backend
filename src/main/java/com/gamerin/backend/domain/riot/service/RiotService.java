package com.gamerin.backend.domain.riot.service;
    
    import java.math.BigDecimal;
    import java.math.RoundingMode;
    import java.util.List;
    import java.util.UUID;
    
    import com.gamerin.backend.domain.riot.client.RiotApiClient;
    import com.gamerin.backend.domain.riot.dto.external.LeagueEntryResponse;
    import com.gamerin.backend.domain.riot.dto.external.MatchResponse;
    import com.gamerin.backend.domain.riot.dto.external.RiotAccountResponse;
    import com.gamerin.backend.domain.riot.dto.request.RiotConnectRequest;
    import com.gamerin.backend.domain.riot.dto.response.RiotConnectionResponse;
    import com.gamerin.backend.domain.riot.dto.response.RiotSummaryResponse;
    import com.gamerin.backend.domain.user.entity.User;
    import com.gamerin.backend.domain.user.entity.UserProfile;
    import com.gamerin.backend.domain.user.repository.UserRepository;
    import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
    import org.springframework.http.HttpStatus;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;
    import org.springframework.web.server.ResponseStatusException;
    
    @Service
    @Transactional
    public class RiotService {
    
        private final UserRepository userRepository;
        private final RiotApiClient riotApiClient;
    
        public RiotService(UserRepository userRepository, RiotApiClient riotApiClient) {
            this.userRepository = userRepository;
            this.riotApiClient = riotApiClient;
        }
    
        // 1. Riot 계정 연동
        public RiotConnectionResponse connect(CustomUserPrincipal principal, RiotConnectRequest request) {
            User user = getCurrentUser(principal);
            UserProfile profile = getCurrentProfile(user);
    
            String riotId = request.riotId();
            if (!riotId.contains("#")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Riot ID 형식은 '닉네임#태그'여야 합니다.");
            }
    
            String[] parts = riotId.split("#", 2);
            String gameName = parts[0];
            String tagLine = parts[1];
    
            // Riot API를 호출하여 PUUID 획득
            RiotAccountResponse account = riotApiClient.findAccount(gameName, tagLine);
            String puuid = account.puuid();
    
            // 다른 유저가 사용 중인지 중복 검사
            validateRiotPuuidDuplicate(user.getId(), puuid);
    
            // 연동 정보 저장
            profile.connectRiot(riotId, puuid);
            return new RiotConnectionResponse(true, riotId);
        }
    
        // 2. 리그 오브 레전드(LoL) 요약 전적 가져오기
        public RiotSummaryResponse getLolSummary(CustomUserPrincipal principal) {
            User user = getCurrentUser(principal);
            UserProfile profile = getCurrentProfile(user);
    
            if (!profile.hasConnectedRiot()) {
                return new RiotSummaryResponse("League of Legends", null, 0.0, 0, 0, false);
            }
    
            String puuid = profile.getRiotPuuid();
            
            try {
                // [수정] PUUID를 활용해 다이렉트로 리그 정보 조회
                List<LeagueEntryResponse> entries = riotApiClient.findLeagueEntriesByPuuid(puuid);
    
                // 솔로랭크 전적 우선 탐색, 없으면 자유랭크 탐색
                LeagueEntryResponse targetEntry = entries.stream()
                        .filter(e -> "RANKED_SOLO_5x5".equals(e.queueType()))
                        .findFirst()
                        .orElseGet(() -> entries.stream()
                                .filter(e -> "RANKED_FLEX_SR".equals(e.queueType()))
                                .findFirst()
                                .orElse(null));
    
                String tierLabel = "UNRANKED";
                int wins = 0;
                int losses = 0;
                int games = 0;
                int winRate = 0;
    
                if (targetEntry != null) {
                    tierLabel = targetEntry.tier() + " " + targetEntry.rank();
                    wins = targetEntry.wins();
                    losses = targetEntry.losses();
                    games = wins + losses;
                    winRate = games == 0 ? 0 : (int) Math.round((wins * 100.0) / games);
                }
    
                // 최근 5게임 KDA 계산
                double kda = calculateLolKda(puuid);
    
                RiotSummaryResponse response = new RiotSummaryResponse("League of Legends", tierLabel, kda, winRate, games, true);
                
                // DB에 LoL 요약 정보 캐싱
                profile.updateLolSummary(tierLabel, kda, winRate, games);
                return response;
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                e.printStackTrace();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LoL 전적정보를 가져오는 데 실패했습니다.", e);
            }
        }
    
        // 최근 5게임 상세 조회를 통한 KDA 연산
        private double calculateLolKda(String puuid) {
            List<String> matchIds = riotApiClient.findRecentMatchIds(puuid, 5);
            if (matchIds == null || matchIds.isEmpty()) {
                return 0.0;
            }
    
            int totalKills = 0;
            int totalDeaths = 0;
            int totalAssists = 0;
    
            for (String matchId : matchIds) {
                try {
                    MatchResponse match = riotApiClient.findMatchDetail(matchId);
                    if (match != null && match.info() != null && match.info().participants() != null) {
                        MatchResponse.ParticipantDto participant = match.info().participants().stream()
                                .filter(p -> puuid.equals(p.puuid()))
                                .findFirst()
                                .orElse(null);
    
                        if (participant != null) {
                            totalKills += participant.kills();
                            totalDeaths += participant.deaths();
                            totalAssists += participant.assists();
                        }
                    }
                } catch (Exception e) {
                    // 특정 매치 조회 실패 시 무시하고 다음 매치 진행
                }
            }
    
            if (totalDeaths == 0) {
                return truncate2((double) (totalKills + totalAssists));
            }
    
            return truncate2((double) (totalKills + totalAssists) / totalDeaths);
        }
    


        // 4. Riot 연동 해제
        public void disconnect(CustomUserPrincipal principal) {
            User user = getCurrentUser(principal);
            UserProfile profile = getCurrentProfile(user);
            profile.disconnectRiot();
        }

        private User getCurrentUser(CustomUserPrincipal principal) {
            if (principal == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
            }
            return userRepository.findById(principal.getUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 유저를 찾을 수 없습니다."));
        }

        private UserProfile getCurrentProfile(User user) {
            UserProfile profile = user.getProfile();
            if (profile == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "사용자 프로필이 초기화되지 않았습니다.");
            }
            return profile;
        }

        private void validateRiotPuuidDuplicate(UUID userId, String puuid) {
            boolean duplicated = userRepository.existsConnectedRiotPuuidByOtherUser(userId, puuid);
            if (duplicated) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 유저가 연동한 Riot 계정입니다.");
            }
        }

        private double truncate2(double value) {
            return BigDecimal.valueOf(value)
                    .setScale(2, RoundingMode.DOWN)
                    .doubleValue();
        }
    }