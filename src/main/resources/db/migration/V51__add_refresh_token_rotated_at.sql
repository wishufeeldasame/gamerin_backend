-- refresh 토큰 회전으로 폐기된 시각. 로그아웃·비밀번호 변경·제재 등 다른 폐기와 구분해 재사용(탈취) 탐지에만 쓴다.
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;
