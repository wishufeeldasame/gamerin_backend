# 멘토링 시스템 개발 현황

## 1. 현재 단계
- **Step 1: 데이터베이스 기반 구축** 완료
- **Step 2: 멘토 등록 API 개발** 완료 (2026-05-13)
- **Step 3: 멘토링 프로그램(상품) 등록 API 개발** 완료 (2026-05-13)
- **Step 4: 멘토링 프로그램 목록 조회 API 개발** 완료 (2026-05-14)
- **Step 5: 멘토링 프로그램 상세 조회 API 개발** 완료 (2026-05-14)
- **Step 6: 멘토링 프로그램 수정 및 삭제 API 개발** 완료 (2026-05-14)
- **Step 7: 멘토링 신청 시스템 개발** 진행 중 (핵심 로직 완료)
- **다음 목표**: Step 7 고도화 (신청 내역 조회 및 수락/거절 API)

## 2. 완료된 작업 세부 내역
### Step 7 (NEW)
- **마일리지 연동**: `MileageWallet` 엔티티 및 레포지토리 생성, 멘토링 신청 시 잔액 검증 및 차감 로직 구현.
- **에스크로 신청**: `POST /api/v1/mentoring/applications` (마일리지 차감 + `ESCROW_HELD` 결제 상태 설정).
- **데이터 정합성**: `@Transactional`을 통한 마일리지 차감과 신청 내역 저장의 원자성 보장.
- **레포지토리 확장**: 멘티/멘토별 신청 내역 조회를 위한 `findByMenteeId`, `findByProgramMentorId` 쿼리 메서드 추가.

### Step 4, 5, 6
... (기존 내용 동일) ...

## 3. 기술적 해결 사항 (Troubleshooting)
- **마일리지 연동**: 기존 DB 테이블(`mileage_wallets`)만 존재하던 상태에서 JPA 엔티티를 설계하여 비즈니스 로직(deduct)을 도메인 모델에 포함시킴.
- **트랜잭션 관리**: 결제와 신청 데이터 생성을 한 트랜잭션으로 묶어 데이터 불일치 위험 방지.
## 4. 다음 작업 예약 (Step 4, 5, 6)
- **Step 4**: `GET /api/v1/mentoring/programs` - 전체/게임별 프로그램 목록 조회 (페이징 포함 추천).
- **Step 5**: `GET /api/v1/mentoring/programs/{id}` - 특정 프로그램 상세 정보 조회.
- **Step 6**: `PATCH/DELETE /api/v1/mentoring/programs/{id}` - 프로그램 수정 및 삭제 로직 구현.
