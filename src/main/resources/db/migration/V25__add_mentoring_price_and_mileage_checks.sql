-- 멘토링 프로그램 가격 음수 방지 제약조건 추가
ALTER TABLE mentoring_programs
    ADD CONSTRAINT chk_mentoring_programs_price CHECK (price >= 0);
    
-- 멘토링 신청 결제 마일리지 음수 방지 제약조건 추가
ALTER TABLE mentoring_applications
    ADD CONSTRAINT chk_mentoring_applications_applied_mileage CHECK (applied_mileage >= 0);
    
-- 마일리지 지갑 잔액 음수 방지 제약조건 추가 (지갑 잔액 무결성 보장)
ALTER TABLE mileage_wallets
    ADD CONSTRAINT chk_mileage_wallets_balance CHECK (balance >= 0);