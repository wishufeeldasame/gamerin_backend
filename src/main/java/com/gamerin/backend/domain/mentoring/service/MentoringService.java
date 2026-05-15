package com.gamerin.backend.domain.mentoring.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramDetailResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramResponse;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import org.springframework.transaction.annotation.Transactional;

@Service
public class MentoringService {

    private final MentorProfileRepository mentorProfileRepository;
    private final UserRepository userRepository;
    private final MentoringProgramRepository mentoringProgramRepository;
    private final MentoringApplicationRepository mentoringApplicationRepository;
    private final MileageWalletRepository mileageWalletRepository;
    private final ObjectMapper objectMapper;

    public MentoringService(
            MentorProfileRepository mentorProfileRepository,
            UserRepository userRepository, 
            MentoringProgramRepository mentoringProgramRepository,
            MentoringApplicationRepository mentoringApplicationRepository,
            MileageWalletRepository mileageWalletRepository,
            ObjectMapper objectMapper) {
        this.mentorProfileRepository = mentorProfileRepository;
        this.userRepository = userRepository;
        this.mentoringProgramRepository = mentoringProgramRepository;
        this.mentoringApplicationRepository = mentoringApplicationRepository;
        this.mileageWalletRepository = mileageWalletRepository;
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

    @Transactional
    public MentoringProgramResponse updateProgram(CustomUserPrincipal principal, UUID programId, MentoringProgramUpdateRequest request) {
        // 프로그램 존재 여부 확인
        MentoringProgram program = mentoringProgramRepository.findById(programId)
                .orElseThrow(() -> new RuntimeException("프로그램을 찾을 수 없습니다."));

        // 권한 확인 (프로그램의 멘토 ID와 현재 접속 유저 ID 비교)
        if (!program.getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 프로그램을 수정할 권한이 없습니다.");
        }

        // 필드 업데이트
        program.setTitle(request.title());
        program.setContent(request.content());
        program.setAvailableTimeDesc(request.availableTimeDesc());    
        program.setPrice(request.price());
        program.setStatus(request.status());
        program.setTags(request.tags());

        return MentoringProgramResponse.from(program);

    }

    @Transactional
    public void deleteProgram(CustomUserPrincipal principal, UUID programId) {
        // 프로그램 존재 여부 확인
        MentoringProgram program = mentoringProgramRepository.findById(programId)
                .orElseThrow(() -> new RuntimeException("프로그램을 찾을 수 없습니다."));

        // 권한 확인
        if (!program.getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 프로그램을 삭제할 권한이 없습니다.");
        }

        // 삭제 처리
        mentoringProgramRepository.delete(program);
    }

    // 신청
    @Transactional
    public MentoringApplicationResponse applyToProgram(CustomUserPrincipal principal, MentoringApplicationRequest request) {
        // 프로그램 존재 여부 확인
        MentoringProgram program = mentoringProgramRepository.findById(request.programId())
                .orElseThrow(() -> new RuntimeException("신청하려는 프로그램을 찾을 수 없습니다."));

        // 본인의 프로그램인지 확인 (자신에게 신청 불가)
        if (program.getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("자신이 등록한 프로그램에는 신청할 수 없습니다.");
        }

        // 멘티 유저 정보 조회
        User mentee = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자 정보를 찾을 수 없습니다."));


        // 마일리지 지갑 조회 및 잔액 확인 / 차감
        MileageWallet wallet = mileageWalletRepository.findById(mentee.getId())
                .orElseThrow(() -> new RuntimeException("마일리지 지갑을 찾을 수 없습니다."));

        wallet.deduct(program.getPrice());

        // 신청 엔티티 생성 및 저장
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(mentee);
        application.setAppliedMileage(program.getPrice()); // 프로그램 가격만큼 마일리지 적용 (추후 마일리지 검증 로직 추가 가능)
        application.setMessage(request.message());
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        MentoringApplication savedApplication = mentoringApplicationRepository.save(application);

        return MentoringApplicationResponse.from(savedApplication);
    }

    // 멘티가 본인 신청 내역 확인하는거
    @Transactional(readOnly = true)
    public Page<MentoringApplicationResponse> getMyApplicationsAsMentee(CustomUserPrincipal principal, Pageable pageable) {
        return mentoringApplicationRepository.findByMenteeId(principal.getUserId(), pageable).map(MentoringApplicationResponse::from);
    }

    // 멘토가 신청 내역 확인하는거
    @Transactional(readOnly = true)
    public Page<MentoringApplicationResponse> getMyApplicationsAsMentor(CustomUserPrincipal principal, Pageable pageable) {
        return mentoringApplicationRepository.findByProgramMentorId(principal.getUserId(), pageable).map(MentoringApplicationResponse::from);
    }

    // 신청 수락
    @Transactional
    public MentoringApplicationResponse acceptApplication(CustomUserPrincipal principal, UUID applicationId) {
        MentoringApplication application = mentoringApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));

        // 권한 확인: 신청된 프로그램의 멘토가 본인인지 확인
        if (!application.getProgram().getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 신청을 수락할 권한이 없습니다.");
        }

        if (application.getStatus() != ApplicationStatus.APPLIED) {
            throw new RuntimeException("신청 상태인 경우에만 수락할 수 있습니다.");
        }

        application.setStatus(ApplicationStatus.ACCEPTED);
        return MentoringApplicationResponse.from(application);
    
    }

    // 신청 거절
    @Transactional
    public MentoringApplicationResponse rejectApplication(CustomUserPrincipal principal, UUID applicationId) {
        MentoringApplication application = mentoringApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));

        // 권한 확인
        if (!application.getProgram().getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 신청을 거절할 권한이 없습니다.");
        }

        if (application.getStatus() != ApplicationStatus.APPLIED) {
            throw new RuntimeException("신청 상태인 경우에만 거절할 수 있습니다.");
        }

        // 상태 변경
        application.setStatus(ApplicationStatus.REJECTED);
        application.setPaymentStatus(PaymentStatus.REFUNDED);

        // 마일리지 환불
        MileageWallet wallet = mileageWalletRepository.findById(application.getMentee().getId())
                .orElseThrow(() -> new RuntimeException("멘티의 마일리지 지갑을 찾을 수 없습니다."));
        wallet.addBalance(application.getAppliedMileage());

        return MentoringApplicationResponse.from(application);
    }



    
}
