package com.gamerin.backend.domain.mentoring.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramDetailResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramResponse;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import org.springframework.transaction.annotation.Transactional;

@Service
public class MentoringService {

    private final MentorProfileRepository mentorProfileRepository;
    private final UserRepository userRepository;
    private final MentoringProgramRepository mentoringProgramRepository;
    private final ObjectMapper objectMapper;

    public MentoringService(MentorProfileRepository mentorProfileRepository, UserRepository userRepository, MentoringProgramRepository mentoringProgramRepository, ObjectMapper objectMapper) {
        this.mentorProfileRepository = mentorProfileRepository;
        this.userRepository = userRepository;
        this.mentoringProgramRepository = mentoringProgramRepository;
        this.objectMapper = objectMapper;
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

    @Transactional
    public MentoringProgramResponse registerProgram(CustomUserPrincipal principal, MentoringProgramRequest request) {
        
        // 현재 사용자의 멘토 프로필 조회 (멘토 등록 여부 확인)
        MentorProfile mentor = mentorProfileRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("멘토로 등록되지 않은 사용자입니다."));

        // 프로그램 엔티티 생성 및 필드 설정
        MentoringProgram program = new MentoringProgram();
        program.setMentor(mentor);
        program.setGameName(request.gameName());
        program.setTitle(request.title());
        program.setContent(request.content());
        program.setAvailableTimeDesc(request.availableTimeDesc());
        program.setPrice(request.price());

        // List<String> -> JSON String 변환
        program.setTags(request.tags());

        // 저장 및 응답 변환
        MentoringProgram savedProgram = mentoringProgramRepository.save(program);
        return MentoringProgramResponse.from(savedProgram);
    }

    @Transactional(readOnly = true)
    public Page<MentoringProgramResponse> getPrograms(String gameName, Pageable pageable) {
        Page<MentoringProgram> programs;

        if (gameName != null && !gameName.isBlank()) {
            programs = mentoringProgramRepository.findByGameName(gameName, pageable);
        } else {
            programs = mentoringProgramRepository.findAll(pageable);
        }
        
        return programs.map(MentoringProgramResponse::from);
    }

    @Transactional(readOnly = true)
    public MentoringProgramDetailResponse getProgramDetail(UUID id) {
        MentoringProgram program = mentoringProgramRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("프로그램을 찾을 수 없습니다."));
        return MentoringProgramDetailResponse.from(program);
    }
    
}
