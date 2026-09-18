#!/bin/bash
# PreToolUse(Write|Edit) hook: 편집 대상 파일에 맞춰 voltagent 서브에이전트 사용을 리마인드한다.
# 강제가 아닌 제안이므로 항상 exit 0, continue 변경 없음.
input=$(cat)
file=$(echo "$input" | jq -r '.tool_input.file_path // empty')
[ -z "$file" ] && exit 0

suggestion=""
case "$file" in
  */global/security/*|*/domain/auth/*|*Jwt*|*OAuth2*|*TokenService*)
    suggestion="인증/JWT/OAuth2 관련 파일입니다. voltagent-core-dev:auth-integration-engineer 서브에이전트 사용을 고려하세요." ;;
  */controller/*|*/global/response/*)
    suggestion="API 엔드포인트/응답 계약 관련 파일입니다. voltagent-core-dev:api-designer 서브에이전트 사용을 고려하세요." ;;
  */db/migration/*|*/repository/*)
    suggestion="Flyway 마이그레이션/리포지토리 파일입니다. voltagent-data-ai:postgres-pro 서브에이전트 사용을 고려하세요." ;;
  */src/test/*)
    suggestion="테스트 파일입니다. voltagent-qa-sec:test-automator 서브에이전트 사용을 고려하세요." ;;
  */moderation/*|*/filter/*|*Storage*|*Upload*)
    suggestion="업로드 검증/검열/보안 필터 관련 파일입니다. voltagent-infra:security-engineer 서브에이전트 사용을 고려하세요." ;;
  */Dockerfile|*docker-compose*)
    suggestion="Docker 이미지/구성 파일입니다. voltagent-infra:docker-expert 서브에이전트 사용을 고려하세요." ;;
  */README*|*/docs/*|*.md)
    suggestion="문서 파일입니다. voltagent-dev-exp:documentation-engineer 서브에이전트 사용을 고려하세요." ;;
  */src/main/java/*.java)
    suggestion="Spring Boot 소스 파일입니다. 필요시 voltagent-lang:spring-boot-engineer 서브에이전트 사용을 고려하세요." ;;
esac

[ -z "$suggestion" ] && exit 0
jq -n --arg ctx "$suggestion" '{hookSpecificOutput:{hookEventName:"PreToolUse",additionalContext:$ctx}}'
