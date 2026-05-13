# 멘토링 시스템 개발 현황

## 1. 현재 단계
- **Step 1: 데이터베이스 기반 구축** 완료
- **Step 2: 멘토 등록 API 개발** 완료 (2026-05-13)
- **Step 3: 멘토링 프로그램(상품) 등록 API 개발** 완료 (2026-05-13)
- **Step 4: 멘토링 프로그램 목록 조회 API 개발** 완료 (2026-05-14)
- **Step 5: 멘토링 프로그램 상세 조회 API 개발** 완료 (2026-05-14)
- **Step 6: 멘토링 프로그램 수정 및 삭제 API 개발** 완료 (2026-05-14)
- **다음 목표**: Step 7: 멘토링 신청 시스템 개발

## 2. 완료된 작업 세부 내역
### Step 4, 5, 6 (NEW)
- **목록 조회**: `GET /api/v1/mentoring/programs` (게임별 필터, 페이징, `@ParameterObject` 적용)
- **상세 조회**: `GET /api/v1/mentoring/programs/{id}` (멘토 정보 포함)
- **수정/삭제**: `PATCH/DELETE /api/v1/mentoring/programs/{id}` (작성자 권한 검증 로직 포함)
- **DTO 개선**: 응답 DTO에 `mentorNickname` 추가하여 프론트엔드 요구사항 반영.
- **Swagger**: `@PageableDefault` 및 `@ParameterObject`를 통해 테스트 편의성 강화.

### Step 3
... (기존 내용 동일) ...

## 3. 기술적 해결 사항 (Troubleshooting)
- **Shared PK 저장 이슈**: `MentorProfile` 저장 시 JPA INSERT/UPDATE 판단 로직 수정 (`Persistable` 구현).
- **jsonb 타입 매핑 에러**: Java `String`을 `jsonb` 컬럼에 넣을 때 발생하는 타입 불일치 에러를 `@JdbcTypeCode` 어노테이션으로 해결.

## 4. 다음 작업 예약 (Step 4, 5, 6)
- **Step 4**: `GET /api/v1/mentoring/programs` - 전체/게임별 프로그램 목록 조회 (페이징 포함 추천).
- **Step 5**: `GET /api/v1/mentoring/programs/{id}` - 특정 프로그램 상세 정보 조회.
- **Step 6**: `PATCH/DELETE /api/v1/mentoring/programs/{id}` - 프로그램 수정 및 삭제 로직 구현.
