package com.gamerin.backend.domain.mentoring.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringReviewRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramDetailResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringReviewResponse;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringReviewRepository;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MentoringService {

    private static final List<ApplicationStatus> REAPPLY_BLOCKING_STATUSES = List.of(
            ApplicationStatus.APPLIED,
            ApplicationStatus.ACCEPTED,
            ApplicationStatus.ONGOING,
            ApplicationStatus.FINISHED
    );

    private final MentorProfileRepository mentorProfileRepository;
    private final UserRepository userRepository;
    private final MentoringProgramRepository mentoringProgramRepository;
    private final MentoringApplicationRepository mentoringApplicationRepository;
    private final MentoringReviewRepository mentoringReviewRepository;
    private final MileageService mileageService;
    private final SettlementProcessor settlementProcessor;

    public MentoringService(
            MentorProfileRepository mentorProfileRepository,
            UserRepository userRepository, 
            MentoringProgramRepository mentoringProgramRepository,
            MentoringApplicationRepository mentoringApplicationRepository,
            MentoringReviewRepository mentoringReviewRepository,
            MileageService mileageService,
            SettlementProcessor settlementProcessor ) {
        this.mentorProfileRepository = mentorProfileRepository;
        this.userRepository = userRepository;
        this.mentoringProgramRepository = mentoringProgramRepository;
        this.mentoringApplicationRepository = mentoringApplicationRepository;
        this.mentoringReviewRepository = mentoringReviewRepository;
        this.mileageService = mileageService;
        this.settlementProcessor = settlementProcessor;
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

        
        // 멘토 등록 시 마일리지 지갑 생성 확인
        mileageService.getOrCreateWallet(user);
        

        // 멘토 프로필 생성 및 저장
        MentorProfile profile = new MentorProfile();
        profile.setUser(user);
        profile.setAbout(request.about());

        MentorProfile savedProfile = mentorProfileRepository.save(profile);

        return MentorProfileResponse.from(savedProfile);

    }

    // 멘토 프로필 조회
    @Transactional(readOnly = true)
    public MentorProfileResponse getMyMentorProfile(CustomUserPrincipal principal) {
        return mentorProfileRepository.findById(principal.getUserId())
                .map(MentorProfileResponse::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public MentorProfileResponse getMentorProfile(UUID mentorId) {
        MentorProfile mentorProfile = mentorProfileRepository.findById(mentorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 멘토를 찾을 수 없습니다."));

        return MentorProfileResponse.from(mentorProfile);
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
        MentoringProgram program = mentoringProgramRepository.findByIdForUpdate(request.programId())
                .orElseThrow(() -> new RuntimeException("신청하려는 프로그램을 찾을 수 없습니다."));

        // 본인의 프로그램인지 확인 (자신에게 신청 불가)
        if (program.getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("자신이 등록한 프로그램에는 신청할 수 없습니다.");
        }

        if (program.getStatus() != ProgramStatus.ACTIVE) {
            throw new RuntimeException("마감된 프로그램에는 신청할 수 없습니다.");
        }

        if (mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(
                principal.getUserId(),
                program.getId(),
                REAPPLY_BLOCKING_STATUSES
        )) {
            throw new RuntimeException("이미 진행 중인 신청 내역이 있습니다.");
        }

        // 멘티 유저 정보 조회
        User mentee = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자 정보를 찾을 수 없습니다."));


        // 마일리지 차감 및 트랜잭션 기록
        mileageService.useMileage(
                mentee,
                program.getPrice(),
                TransactionType.MENTORING_PAY,
                "멘토링 신청 결제: " + program.getTitle(), 
                null // 아직 application 엔티티가 저장되기 전이므로 null 처리
        );

        // 신청 엔티티 생성 및 저장
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(mentee);
        application.setAppliedMileage(program.getPrice()); // 프로그램 가격만큼 마일리지 적용 (추후 마일리지 검증 로직 추가 가능)
        application.setMessage(request.message());
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        MentoringApplication savedApplication = mentoringApplicationRepository.save(application);

        return toApplicationResponse(savedApplication);
    }

    //멘토링 신청 취소
    @Transactional
    public MentoringApplicationResponse cancelApplication(CustomUserPrincipal principal, UUID applicationId) {
        MentoringApplication application = mentoringApplicationRepository.findById(applicationId)
                        .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));

        // 권한 확인: 신청한 멘티 본인인지 확인
        if (!application.getMentee().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 신청을 취소할 권한이 없습니다.");
        }

        // 상태 확인: 신청 상태(APPLIED)인 경우에만 취소 가능
        if (application.getStatus() != ApplicationStatus.APPLIED) {
            throw new RuntimeException("수락 전인 신청 건만 취소할 수 있습니다.");
        }

        // 상태 변경
        application.setStatus(ApplicationStatus.CANCELLED);
        application.setPaymentStatus(PaymentStatus.REFUNDED);

        // 마일리지 환불 및 트랜잭션 기록
        mileageService.addMileage(
            application.getMentee(),
            application.getAppliedMileage(),
            TransactionType.MENTORING_REFUND,
            "멘토링 신청 취소에 따른 환불",
            application.getId()
        );

        return toApplicationResponse(application);
    }

    // 멘티가 본인 신청 내역 확인하는거
    @Transactional(readOnly = true)
    public Page<MentoringApplicationResponse> getMyApplicationsAsMentee(CustomUserPrincipal principal, Pageable pageable) {
        return toApplicationResponsePage(mentoringApplicationRepository.findByMenteeId(principal.getUserId(), pageable));
    }

    // 멘토가 신청 내역 확인하는거
    @Transactional(readOnly = true)
    public Page<MentoringApplicationResponse> getMyApplicationsAsMentor(CustomUserPrincipal principal, Pageable pageable) {
        return toApplicationResponsePage(mentoringApplicationRepository.findByProgramMentorId(principal.getUserId(), pageable));
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
        return toApplicationResponse(application);
    
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

        // 마일리지 환불 및 트랜잭션 기록
        mileageService.addMileage(
            application.getMentee(),
            application.getAppliedMileage(),
            TransactionType.MENTORING_REFUND,
            "멘토링 거절에 따른 환불", application.getId()
        );

        return toApplicationResponse(application);
    }

    // 멘토링 시작 (멘토가 수행)
    @Transactional
    public MentoringApplicationResponse startMentoring(CustomUserPrincipal principal, UUID applicationId) {
        MentoringApplication application = mentoringApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));

        // 권한 확인: 해당 프로그램의 멘토만 시작 가능
        if (!application.getProgram().getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 멘토링을 시작할 권한이 없습니다.");
        }

        // 상태 확인: 수락된 상태(ACCEPTED)에서만 시작 가능
        if (application.getStatus() != ApplicationStatus.ACCEPTED) {
            throw new RuntimeException("수락된 신청 건만 시작할 수 있습니다.");
        }

        application.setStatus(ApplicationStatus.ONGOING);
        return toApplicationResponse(application);
    }

    // 멘토가 수업 완료를 선언 (정산 대기 상태로 진입)
    @Transactional
    public MentoringApplicationResponse finishMentoring(CustomUserPrincipal principal, UUID applicationId) {
        MentoringApplication application = mentoringApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));

        if (!application.getProgram().getMentor().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 권한이 없습니다.");
        }

        if (application.getStatus() != ApplicationStatus.ONGOING) {
            throw new RuntimeException("진행 중인 멘토링만 완료 보고를 할 수 있습니다.");
        }

        application.setStatus(ApplicationStatus.FINISHED);

        return toApplicationResponse(application);
    }

    // 멘토링 완료 확정 및 정산 (멘티가 수행)
    @Transactional
    public MentoringApplicationResponse completeMentoring(CustomUserPrincipal principal, UUID applicationId) {
        MentoringApplication application = mentoringApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));

        // 권한 확인: 신청한 멘티만 완료 확정 가능
        if (!application.getMentee().getId().equals(principal.getUserId())) {
            throw new RuntimeException("해당 멘토링을 완료 확정할 권한이 없습니다.");
        }

        // 상태 확인: 진행 중(ONGOING)이거나 멘토가 완료 보고(FINISHED)한 상태에서만 멘티가 완료 확정 가능
        if (application.getStatus() != ApplicationStatus.ONGOING && application.getStatus() != ApplicationStatus.FINISHED) {
            throw new RuntimeException("행 중이거나 완료 보고된 멘토링만 완료 확정할 수 있습니다.");
        }

        // 상태 변경
        application.setStatus(ApplicationStatus.COMPLETED);
        application.setPaymentStatus(PaymentStatus.SETTLED);
        application.setCompletedAt(java.time.OffsetDateTime.now());

        // 멘토에게 마일리지 입금 및 트랜잭션 기록
        MentorProfile mentorProfile = application.getProgram().getMentor();
        mileageService.addMileage(
            mentorProfile.getUser(),
            application.getAppliedMileage(),
            TransactionType.SETTLEMENT,
            "멘토링 완료 정산",
            application.getId()
        );


        // 멘토 통계 업데이트 : 누적 멘티 수 증가
        mentorProfile.setMenteeCount(mentorProfile.getMenteeCount() + 1);

        return toApplicationResponse(application);

    }

    // 자동 정산 대상 처리
    @Transactional
    public void processAutoSettlement() {
        
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(7);
        List<MentoringApplication> targets = mentoringApplicationRepository.findByStatusAndUpdatedAtBefore(ApplicationStatus.FINISHED, threshold);

        for (MentoringApplication application : targets) {
            try {
                // 개별 트랜잭션 호출
                settlementProcessor.processSingleSettlement(application);

            } catch (Exception e) {
                
                System.err.println("자동 정산 실패 (ID: " + application.getId() + "): " + e.getMessage());
            }
        }
    }


    // 리뷰 생성
    @Transactional
    public MentoringReviewResponse createReview(CustomUserPrincipal principal, MentoringReviewRequest request) {
        MentoringApplication application = mentoringApplicationRepository.findById(request.applicationId())
                .orElseThrow(() -> new RuntimeException("신청 내역을 찾을 수 없습니다."));
        

        // 권한 확인: 해당 멘토링을 신청한 멘티만 작성 가능
        if (!application.getMentee().getId().equals(principal.getUserId())) {
            throw new RuntimeException("리뷰를 작성할 권한이 없습니다.");
        }

        // 상태 확인: 완료(COMPLETED)된 멘토링만 리뷰 작성 가능
        if (application.getStatus() != ApplicationStatus.COMPLETED) {
            throw new RuntimeException("완료된 멘토링에 대해서만 리뷰를 남길 수 있습니다.");
        }

        // 중복 확인: 이미 리뷰를 작성했는지 확인
        if (mentoringReviewRepository.existsByApplicationId(application.getId())) {
            throw new RuntimeException("이미 이 멘토링에 대한 리뷰를 작성했습니다.");
        }

        // 리뷰 엔티티 생성 및 저장
        MentoringReview review = new MentoringReview();
        review.setApplication(application);
        review.setMentor(application.getProgram().getMentor());
        review.setMentee(application.getMentee());
        review.setRating(request.rating());
        review.setContent(request.content());

        MentoringReview savedReview = mentoringReviewRepository.save(review);

        // 멘토 통계 업데이트
        updateMentorStats(application.getProgram().getMentor(), request.rating());

        return MentoringReviewResponse.from(savedReview);
    }

    private MentoringApplicationResponse toApplicationResponse(MentoringApplication application) {
        return MentoringApplicationResponse.from(
                application,
                mentoringReviewRepository.existsByApplicationId(application.getId())
        );
    }

    private Page<MentoringApplicationResponse> toApplicationResponsePage(Page<MentoringApplication> applications) {
        if (applications.getContent().isEmpty()) {
            return applications.map(application -> MentoringApplicationResponse.from(application, false));
        }

        List<UUID> applicationIds = applications.getContent().stream()
                .map(MentoringApplication::getId)
                .toList();
        Set<UUID> reviewedApplicationIds = new HashSet<>(
                mentoringReviewRepository.findReviewedApplicationIds(applicationIds)
        );

        return applications.map(application ->
                MentoringApplicationResponse.from(
                        application,
                        reviewedApplicationIds.contains(application.getId())
                )
        );
    }

    // 멘토 평점 업데이트
    private void updateMentorStats(MentorProfile mentor, int newRating) {
        int oldCount = mentor.getReviewCount();
        BigDecimal oldAvg = mentor.getRatingAvg();

        int newCount = oldCount + 1;
        // 새로운 평균 = (기존평균 * 기존갯수 + 신규점수) / 신규갯수
        BigDecimal newAvg = oldAvg.multiply(BigDecimal.valueOf(oldCount))
                .add(BigDecimal.valueOf(newRating))
                .divide(BigDecimal.valueOf(newCount), 2, RoundingMode.HALF_UP);

        mentor.setReviewCount(newCount);
        mentor.setRatingAvg(newAvg);

        mentorProfileRepository.save(mentor);

    }

    // 특정 멘토의 리뷰 목록 조회
    @Transactional(readOnly = true)
    public Page<MentoringReviewResponse> getMentorReviews(UUID mentorId, Pageable pageable) {
        return mentoringReviewRepository.findByMentorUserId(mentorId, pageable).map(MentoringReviewResponse::from);
    }



    
}
