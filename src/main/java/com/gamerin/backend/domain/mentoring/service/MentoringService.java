package com.gamerin.backend.domain.mentoring.service;

import org.springframework.stereotype.Service;

import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import jakarta.transaction.Transactional;

@Service
public class MentoringService {

    private final MentorProfileRepository mentorProfileRepository;
    private final UserRepository userRepository;

    public MentoringService(MentorProfileRepository mentorProfileRepository, UserRepository userRepository) {
        this.mentorProfileRepository = mentorProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public MentorProfileResponse registerMentor(CustomUserPrincipal principal, MentorRegistrationRequest request) {
        // 멘토 등록 확인
        if (mentorProfileRepository.existsById(principal.getUserId())) {
            throw new RuntimeException("이미 멘토로 등록된 사용자입니다.");
        } 

        // 유저 엔티티 조회
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        

        // 멘토 프로필 생성 및 저장
        MentorProfile profile = new MentorProfile();
        profile.setUser(user);
        profile.setAbout(request.about());

        MentorProfile savedProfile = mentorProfileRepository.save(profile);

        return MentorProfileResponse.from(savedProfile);

    }
    
}
