# 멘토링 시스템 개발 현황

## 1. 현재 단계
- **Step 1: 데이터베이스 기반 구축** 완료
- **Step 2: 멘토 등록 API 개발** 완료 (2026-05-13)
- **Step 3: 멘토링 프로그램(상품) 등록 API 개발** 완료 (2026-05-13)
- **다음 목표**: Step 4: 멘토링 프로그램 목록 조회 API 개발

## 2. 완료된 작업 세부 내역
### Step 1
- **DB Migration**: `V7__add_mentoring_schema.sql` (테이블 4개 생성 완료)
- **JPA 엔티티 & Repository**: 구축 완료

### Step 2
- **API 구현**: `POST /api/v1/mentoring/mentors` 완료
- **DTO**: `MentorRegistrationRequest`, `MentorProfileResponse` 생성
- **Service/Controller**: `MentoringService`, `MentoringController` 구현

### Step 3 (NEW)
- **API 구현**: `POST /api/v1/mentoring/programs` 완료
- **JSON 처리**: `@JdbcTypeCode(SqlTypes.JSON)`를 사용하여 PostgreSQL `jsonb` 타입과 Java `List<String>` 자동 매핑 구현.
- **DTO**: `MentoringProgramRequest`, `MentoringProgramResponse` 생성
- **기능**: 멘토 권한 확인 후 프로그램 등록 기능 구현 및 테스트 성공.

## 3. 기술적 해결 사항 (Troubleshooting)
- **Shared PK 저장 이슈**: `MentorProfile` 저장 시 JPA INSERT/UPDATE 판단 로직 수정 (`Persistable` 구현).
- **jsonb 타입 매핑 에러**: Java `String`을 `jsonb` 컬럼에 넣을 때 발생하는 타입 불일치 에러를 `@JdbcTypeCode` 어노테이션으로 해결.

## 4. 다음 작업 예약 (Step 4, 5, 6)
- **Step 4**: `GET /api/v1/mentoring/programs` - 전체/게임별 프로그램 목록 조회 (페이징 포함 추천).
- **Step 5**: `GET /api/v1/mentoring/programs/{id}` - 특정 프로그램 상세 정보 조회.
- **Step 6**: `PATCH/DELETE /api/v1/mentoring/programs/{id}` - 프로그램 수정 및 삭제 로직 구현.
