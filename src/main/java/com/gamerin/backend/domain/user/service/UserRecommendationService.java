package com.gamerin.backend.domain.user.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.user.dto.response.UserRecommendationResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Service
public class UserRecommendationService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final MembershipService membershipService;

    public UserRecommendationService(FollowRepository followRepository, UserRepository userRepository,
            MembershipService membershipService) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.membershipService = membershipService;
    }

    @Transactional(readOnly = true)
    public List<UserRecommendationResponse> recommend(UUID viewerId, int limit) {
        if (limit < 1 || limit > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit은 1~20이어야 합니다.");
        }
        Map<UUID, Long> candidates = new LinkedHashMap<>();
        followRepository.findRecommendationCandidates(viewerId, limit)
                .forEach(candidate -> candidates.put(UUID.fromString(candidate.getUserId()), candidate.getMutualCount()));
        if (candidates.size() < limit) {
            // 빈 IN 목록을 피하면서 FOF 결과가 보충 추천에 중복되지 않게 한다.
            var excludedIds = candidates.isEmpty() ? List.of(viewerId) : candidates.keySet();
            userRepository.findRecentRecommendationIds(viewerId, excludedIds, limit - candidates.size())
                    .forEach(id -> candidates.put(UUID.fromString(id), 0L));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<UUID, User> users = userRepository.findAllWithProfileByIdIn(candidates.keySet()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<UUID> members = membershipService.findActiveUserIds(candidates.keySet());
        return candidates.entrySet().stream()
                .filter(entry -> users.containsKey(entry.getKey()) && users.get(entry.getKey()).isActive())
                .map(entry -> UserRecommendationResponse.from(
                        users.get(entry.getKey()), entry.getValue(), members.contains(entry.getKey())))
                .toList();
    }
}
