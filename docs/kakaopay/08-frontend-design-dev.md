# 카카오페이 설계 — 프론트 디자인·개발 가이드 (12절)

[← 카카오페이 설계 문서 목차](README.md)

## 12. 프론트 디자인·개발 가이드

작성일: 2026-10-10 · 코드 확인 기준: frontend `feature/develop` `6c338a9`, backend `feature/kakaopay` `5a8c503`

이 절은 확정 정책(README), 결제 계약(04 문서 6절), 멤버십 항목(06 문서 10절)을 프론트에서 구현하는 방법을 정리한다. 경로는 frontend 저장소 루트 기준이다. 백엔드 DTO 필드 이름은 구현 중 조정될 수 있으므로 각 백엔드 PR이 머지될 때 실제 DTO와 대조한다(`확인 필요` 표시).

### 12-1. 범위

| 화면·기능 | 내용 | 백엔드 의존 |
|---|---|---|
| `/wallet` | 잔액, 충전 패키지, 마일리지 내역·결제 내역 탭 | [#85](https://github.com/wishufeeldasame/gamerin_backend/issues/85), [#86](https://github.com/wishufeeldasame/gamerin_backend/issues/86) |
| `/membership` | 혜택·가격, 이용권 구매·재구매, 현재 이용 기간 | #85, [#89](https://github.com/wishufeeldasame/gamerin_backend/issues/89) |
| `/payments/result` | 카카오페이 복귀, 팝업 결과 전달, 승인·결과 표시 | #85, #86 |
| 결제 진행 UI | PC 팝업 열기·진행 모달·팝업 차단 안내, 모바일 페이지 이동 | #85 |
| 멤버십 배지 | 닉네임 옆 왕관 아이콘 | [#88](https://github.com/wishufeeldasame/gamerin_backend/issues/88) |
| 게시물 수정 | 본인 게시물 메뉴의 `수정`, 텍스트 전용 편집 | [#92](https://github.com/wishufeeldasame/gamerin_backend/issues/92) |
| 멘토링 정리 | 가상 충전 제거, 잔액 요약·충전 바로가기, 멘토 수수료 표시 | #86, [#91](https://github.com/wishufeeldasame/gamerin_backend/issues/91) |
| 메뉴 | 데스크톱 사이드바 `멤버십`, 지갑 진입점 | — |

제외: 사용자 환불 화면, 결제 취소 버튼(취소 API는 화면에 연결하지 않음), 자동 갱신, 관리자 화면 배지, 수정 이력·`수정됨` 표시.

### 12-2. 현재 코드에서 출발점

| 영역 | 현재 구현 | 바꿀 점 |
|---|---|---|
| 가상 충전 | `src/app/(app)/mentoring/page.tsx`의 `내 멘토링` 탭에 `테스트 충전` 폼(`handleChargeMileage` → `chargeMileage`)과 `MileageTransactionPanel` | 폼·전체 내역을 `/wallet`로 옮기고 탭에는 잔액 요약·충전 링크만 남김. `src/lib/mileage-api.ts`의 `chargeMileage` 제거 |
| 잔액 부족 | 같은 파일의 `isMileageShortageError`, `bannerType === 'mileage'` 배너와 부족 모달이 `changeTab('mine')`으로 이동 | `/wallet?returnTo=…`로 이동하고 충전 후 원래 프로그램으로 복귀 |
| 딥링크 | 멘토링은 `?tab=mine`, `?applicationId`, `?reviewId`만 처리. 프로그램 상세는 `openProgramDetail(programId)` 상태 기반 | `?programId=`로 상세를 여는 딥링크 추가 |
| 메뉴 | `src/app/home/components/Sidebar.tsx`의 `menuItems` 5개를 데스크톱 `Sidebar`와 모바일 `MobileTabBar`(`grid-cols-5`)가 공유 | 모바일 탭은 5개 유지. 데스크톱 전용 항목을 따로 둠(12-4절) |
| 설정 진입 | 모바일은 프로필 페이지(`profile/[userId]/page.tsx`)의 설정 버튼으로 `/settings` 진입. 설정에는 `내 신고` 링크 패턴 존재 | 같은 링크 패턴으로 `멤버십`·`내 지갑` 추가 |
| 인증 | `AuthProvider`가 루트 `src/app/layout.tsx`에 있어 **모든 페이지가 로드 시 refresh**를 호출. 백엔드는 refresh마다 기존 토큰을 폐기하고 새로 발급(`LocalAuthService.refresh`) | 결제 팝업에서 refresh를 건너뛰어야 함(12-6절 필수) |
| `(app)` 레이아웃 | `isAuthReady` 전에는 `null`, 사용자 없으면 `/login`으로 이동 | 결과 페이지는 이 그룹 밖에 둠 |
| 공통 UI | `ToastContext`(`useToast`: success·error·info), `ConfirmContext`(`useConfirm`), lucide 아이콘, Tailwind v4, 다크 모드는 `.dark` 클래스 | 그대로 재사용, 새 의존성 추가 없음 |
| 배지 | `verifiedBadge`·`authorVerifiedBadge`는 타입(`feed-api.ts`)에만 있고 화면 표시 없음. 닉네임 공통 컴포넌트 없음 | `MembershipBadge` 신설 후 표시 위치마다 삽입 |
| 게시물 메뉴 | `Post.tsx`·`PostDetail.tsx`의 `MoreHorizontal` 메뉴에 신고·삭제·취소, `canDeletePost = post.mine \|\| …` | `수정` 항목 추가 |
| 본문 제한 | `PostComposer.tsx`의 `MAX_POST_CONTENT_LENGTH = 1000`(파일 내부 상수) | export해 수정 모달과 공유 |
| 목록 갱신 | `src/lib/post-mutations.ts`의 `updatePosts*State` | `updatePostsContent` 추가 |
| 보안 헤더 | `next.config.ts` CSP(`form-action 'self'`, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, COOP 없음, `Permissions-Policy: payment=()` | 팝업·페이지 이동은 최상위 이동이라 변경 불필요. COOP를 추가하지 않음 |

### 12-3. 디자인 원칙

기존 화면 톤을 따른다. 새 디자인 시스템이나 라이브러리를 만들지 않는다.

- **색:** 기본 흑백. 주요 버튼 `bg-black text-white`, 다크 모드 강조 `dark:bg-[#f5b93d] dark:text-black`(멘토링 탭·사이드바와 동일).
- **형태:** 카드 `rounded-2xl border border-zinc-100 bg-white p-5`, 버튼·입력 `rounded-xl h-12`, 섹션 라벨 `text-xs font-black uppercase tracking-[0.18em] text-zinc-400`, 제목 `text-2xl font-black`.
- **알림:** 일시 메시지는 `useToast`, 화면에 남아야 하는 상태(결제 확인 중·실패)는 카드 안 상태 영역, 위험 동작 확인은 `useConfirm`.
- **금액 표기:** 결제 금액은 `4,900원`, 마일리지는 `5,000 M`처럼 단위를 구분해 같은 돈이 두 번 쓰인 것처럼 보이지 않게 한다. 기존 `formatMileage`를 공용으로 옮겨 재사용한다.
- **멤버십 강조색:** 왕관은 라이트 `text-amber-600`(흰 배경 대비 3:1 이상), 다크 `text-[#f5b93d]`. `#f5b93d`는 흰 배경에서 대비가 부족하므로 라이트 모드에 쓰지 않는다.
- **반응형:** 모바일 우선 1열, `lg` 이상 2열. 하단 탭바 높이(`--tab-bar-height`)만큼 하단 여백을 유지한다.

### 12-4. 라우트와 진입점

| 경로 | 위치 | 비고 |
|---|---|---|
| `/wallet` | `src/app/(app)/wallet/page.tsx` | 로그인 필요. `?tab=payments`, `?returnTo=` 지원 |
| `/membership` | `src/app/(app)/membership/page.tsx` | 로그인 필요 |
| `/payments/result` | `src/app/payments/result/page.tsx` (**`(app)` 밖**) | 팝업에서도 렌더돼야 하므로 `(app)` 레이아웃의 대기·`/login` 이동을 받지 않음. `useSearchParams`는 `Suspense`로 감싼다(`home/page.tsx`와 같은 방식) |

진입점:

- **데스크톱 사이드바:** `Sidebar` 내비게이션 아래에 데스크톱 전용 링크 `멤버십`(`Crown`)을 둔다. 공유 `menuItems`에 넣지 않아 `MobileTabBar`의 `grid-cols-5`를 깨지 않는다. 사이드바 사용자 카드에 `내 지갑`(`Wallet`) 링크와 잔액을 둔다.
- **모바일:** 하단 탭 5개 유지. 프로필 페이지 본인 영역의 설정 버튼 옆에 `멤버십`·`내 지갑` 아이콘 버튼을 둔다.
- **설정:** `계정` 섹션 상단에 `내 신고`와 같은 링크 카드로 `멤버십`, `내 지갑`을 추가한다.
- **문맥 진입:** 멘토링 잔액 요약의 `충전하기`, 잔액 부족 배너·모달, 멘토 수수료 안내, 게시물 수정 안내에서 각각 `/wallet`·`/membership`으로 연결한다.

### 12-5. 화면 설계

#### `/wallet` 내 지갑

```text
┌ 내 지갑 ─────────────────────────────────────────┐
│ [잔액 카드]  12,000 M          (Wallet 아이콘)      │
│  멘토링 신청·환불·정산에 사용됩니다.                 │
├ 충전 ────────────────────────────────────────────┤
│ ( ) 5,000원   → 5,000 M                          │
│ (•) 10,000원  → 10,000 M                         │
│ ( ) 30,000원  → 30,000 M                         │
│ ( ) 50,000원  → 50,000 M                         │
│ 1원당 1마일리지 적립 · 보너스 없음                   │
│ [ 카카오페이로 10,000원 결제 ]                      │
├ [마일리지 내역] [결제 내역] ───────────────────────┤
│ 목록 …                                            │
└──────────────────────────────────────────────────┘
```

- 패키지는 `GET /api/v1/payments/products`에서 목적이 충전인 항목만 표시한다. 가격·적립액을 하드코딩하지 않는다.
- 패키지 선택은 `role="radiogroup"` + 화살표 키 이동. 선택 전에는 결제 버튼 비활성.
- 결제 버튼은 요청 중 비활성(`결제 준비 중…`). 진행은 12-6절.
- `returnTo`가 있으면 상단에 `충전 후 멘토링 신청으로 돌아갑니다` 안내와 충전 성공 시 `돌아가기` 버튼을 보여준다. 신청을 자동 제출하지 않는다.
- **마일리지 내역 탭:** 기존 `fetchMyMileageTransactions`(페이지 방식)와 `MileageTransactionPanel` 표시를 옮긴다.
- **결제 내역 탭:** `GET /api/v1/payments` 커서 목록. 행: 날짜, 상품명, 금액(원), 상태 배지, 결제 수단(카드·카카오페이머니). `더 보기`로 다음 커서. 빈 상태 `결제 내역이 없습니다`.

#### `/membership` GamerIN 멤버십

```text
비가입자                                  가입자
┌ GamerIN 멤버십 ───────────────┐        ┌ GamerIN 멤버십 ── 👑 이용 중 ─┐
│ 30일 이용권 4,900원            │        │ 만료일 2026-11-09 23:10       │
│ 자동 갱신 없음 · 직접 재구매     │        │ 지금 재구매하면 → 2026-12-09  │
│ ✓ 닉네임 옆 멤버십 배지         │        │ ✓ 혜택 3종                    │
│ ✓ 게시물 1시간 내 1회 수정      │        │ [ 30일 연장하기 4,900원 ]     │
│ ✓ 멘토 정산 수수료 10% → 5%    │        └──────────────────────────────┘
│ [ 카카오페이로 4,900원 결제 ]   │
└───────────────────────────────┘
```

- 데이터: `GET /api/v1/memberships/me`(상태·만료일·가격·기간), 가격은 상품 목록의 `MEMBERSHIP_30D`와 같아야 한다.
- 가입자 재구매 시 예상 만료일 = `max(지금, 만료일) + 30일`을 화면에서 계산해 안내만 한다(실제 값은 서버 응답).
- 혜택 문구에 `멘티 결제 금액은 바뀌지 않습니다`, `GamerIN 정산 수수료 할인이며 카카오페이 수수료와 무관합니다`를 작게 덧붙인다.
- 서버가 409(이용권 결제 취소 진행 중)를 주면 `이전 결제 취소를 처리하고 있습니다. 잠시 후 다시 시도해주세요`로 표시한다.

#### 결제 진행 (부모 창, PC)

```text
┌ 결제 진행 중 ─────────────────────────────┐
│ 카카오페이 창에서 결제를 완료해주세요.       │
│ QR 또는 카카오톡 알림으로 결제할 수 있어요.   │
│ [ 결제 창 다시 열기 ]  [ 결제 중단 ]          │
└──────────────────────────────────────────┘
```

- 팝업이 차단되면 모달 대신 `팝업이 차단되었습니다. 브라우저 주소창의 팝업 차단을 해제한 뒤 다시 시도해주세요.` 오류 카드를 보여주고 ready를 호출하지 않는다.
- `결제 중단`은 팝업을 닫고 주문 상태를 조회한다(서버 취소 호출 없음, 15분 만료로 정리).
- 모달은 포커스를 가두고 `Esc`는 `결제 중단`과 같게 동작한다.

#### `/payments/result` 결과

| 화면 상태 | 조건 | 제목 | 행동 |
|---|---|---|---|
| 승인 중 | approve 요청 중 | `결제를 확인하고 있어요` | 스피너, 버튼 없음 |
| 충전 완료 | `APPROVED` + 충전 | `10,000 M이 충전되었어요` | `지갑으로`, `returnTo` 있으면 `멘토링으로 돌아가기` |
| 멤버십 완료 | `APPROVED` + 이용권 | `멤버십이 2026-12-09까지 이용 가능해요` | `멤버십 보기`, 사용자 정보 재조회로 배지 즉시 반영 |
| 결제 확인 중 | `APPROVING`·`CANCELING`·`NEEDS_REVIEW` | `결제 결과를 확인하고 있어요` | 자동 재조회(12-6절), `결제 내역 보기`. **새 결제 버튼을 보여주지 않음** |
| 결제 안 됨 | `FAILED`·`EXPIRED`, cancel 복귀 | `결제가 완료되지 않았어요` | `다시 결제하기`(원래 화면으로) |
| 로그인 필요 | 인증 복구 실패 | `로그인 후 결제 내역에서 결과를 확인해주세요` | `로그인`. approve를 호출하지 않음. 미승인 주문은 15분 후 자동 정리 |

- 제목에 포커스를 옮기고 상태 영역은 `aria-live="polite"`.
- 처리 직후 `history.replaceState`로 URL에서 `pg_token`을 지운다.

#### 멤버십 배지

- `src/app/home/components/MembershipBadge.tsx`: lucide `Crown` 14px, `role="img"`, `aria-label="멤버십 회원"`, `title="멤버십 회원"`, 색은 12-3절.
- 닉네임 바로 뒤, 같은 줄, `inline-flex items-center gap-1`. 말줄임(`truncate`)은 닉네임에만 걸고 배지는 잘리지 않게 `shrink-0`.
- 기존 `verifiedBadge`(인증 배지)와 섞지 않는다. 지금 화면에 인증 배지는 없으므로 멤버십 배지만 추가한다.

#### 게시물 수정

```text
┌ 게시물 수정 ───────────────────────── 남은 시간 42분 ┐
│ [textarea: 기존 본문]                       812/1000 │
│ 이미지·영상은 수정할 수 없어요. 게시물당 1회만 수정됩니다. │
│                              [ 취소 ]  [ 수정하기 ]   │
└──────────────────────────────────────────────────────┘
```

- 메뉴 노출: `post.mine && editCount === 0 && now < editableUntil`. 숨김 게시물은 서버가 거부한다.
- 멤버십이 아니면 메뉴의 `수정`을 누를 때 `useConfirm`으로 `게시물 수정은 멤버십 혜택이에요` → `/membership`.
- 본문이 같으면 `수정하기` 비활성. 남은 시간이 0이 되면 버튼을 비활성하고 안내를 바꾼다.
- 저장 성공 시 목록·상세의 본문을 바꾸고 `editCount`를 1로 갱신해 메뉴에서 `수정`을 숨긴다. 다른 사용자에게 `수정됨` 표시를 하지 않는다.

#### 멘토링 화면 변경

- `내 멘토링` 탭: 가상 충전 폼과 전체 내역을 제거하고 `내 마일리지` 카드에 `충전하기`(→ `/wallet?returnTo=/mentoring?tab=mine`)와 `전체 내역`(→ `/wallet`) 링크를 둔다.
- 잔액 부족 배너·모달의 `충전하기`: `/wallet?returnTo=/mentoring?programId={id}`.
- 멘토 `받은 멘토링 요청` 카드: 적용 요율·수수료·예상 수령액(`10,000 M 중 수수료 500 M(5%) · 수령 9,500 M`). 비가입 멘토에게 `멤버십이면 수수료 5%` 안내와 `/membership` 링크. 멘티 화면의 가격은 그대로.

### 12-6. 결제 흐름 구현

#### 공통

1. 사용자가 결제 버튼을 누르면 `requestId = crypto.randomUUID()`를 만든다. ready가 네트워크 오류로 실패해 다시 시도할 때는 **같은 `requestId`**를 쓰고(서버가 기존 주문을 재응답), 결과가 `FAILED`·`EXPIRED`인 뒤 새로 결제할 때만 새로 만든다.
2. ready 응답의 `orderId`·`pcUrl`·`mobileUrl`만 메모리에 둔다. 응답에 TID는 없다.
3. PC·모바일 판정은 `src/lib/payment-device.ts`의 `isMobileBrowser()` 하나로 통일한다(`navigator.userAgentData?.mobile` 우선, 없으면 UA의 `Mobi|Android|iPhone|iPad`).

#### PC (팝업)

```ts
// 클릭 핸들러 안에서 await 전에 동기적으로 연다. await 뒤에 열면 팝업 차단에 걸린다.
const popup = window.open('', 'gamerin-kakaopay', 'width=480,height=720');
if (!popup) return showPopupBlocked();          // ready 호출하지 않음
try {
  const order = await readyPayment({ productCode, requestId });
  popup.location.href = order.pcUrl;
  startWatching(order.orderId, popup);           // message 수신 + closed 감시
} catch (error) {
  popup.close();
  showError(error);
}
```

- **결과 수신:** 부모 창은 `message` 이벤트에서 `event.origin === window.location.origin`, `event.source === popup`, `data.type === 'kakaopay-result'`, `data.orderId === 현재 orderId`를 모두 확인한다. `result === 'approval'`이면 `approvePayment({ orderId, pgToken })`, 그 외에는 `getPayment(orderId)`로 상태만 조회한다.
- **팝업 종료 감시:** 500ms 간격으로 `popup.closed`를 확인한다. 메시지 없이 닫히면 `getPayment`를 호출해 `READY`면 `결제가 취소되었습니다`로 표시한다.
- 부모 창이 승인하므로 팝업은 인증이 필요 없다.

#### 모바일 웹

- ready 후 `location.assign(order.mobileUrl)`. 결제를 마치면 같은 탭이 `/payments/result`로 돌아온다.
- 돌아온 결과 페이지는 일반 페이지처럼 인증을 복원한 뒤(`isAuthReady`) 사용자가 있으면 approve, 없으면 `로그인 필요` 상태.

#### `/payments/result` 페이지

```ts
const orderId = params.get('orderId');
const result = params.get('result');          // approval | cancel | fail
const pgToken = params.get('pg_token');

// 팝업 모드: 같은 origin의 opener가 있으면 결과만 넘기고 닫는다.
if (isSameOriginOpener()) {
  window.opener.postMessage({ type: 'kakaopay-result', orderId, result, pgToken }, window.location.origin);
  window.close();
  return;
}
// 폴백(모바일, 부모 창이 닫힘): isAuthReady 후 approve 또는 상태 조회
```

- `isSameOriginOpener()`는 `window.opener`가 있고 `opener.location.origin` 접근이 예외 없이 같은 origin일 때만 true다(try/catch).
- React Strict Mode의 effect 이중 실행에 대비해 approve는 `useRef` 플래그로 한 번만 보낸다. 서버도 멱등이지만 불필요한 요청을 막는다.
- 처리 직후 `history.replaceState(null, '', '/payments/result?orderId=…')`로 `pg_token`을 지운다.

#### 팝업에서 refresh를 건너뛰어야 한다 (필수)

`AuthProvider`는 루트 레이아웃에 있어 팝업으로 열린 `/payments/result`도 로드 시 refresh를 보낸다. 백엔드는 refresh마다 기존 토큰을 폐기하고 새 토큰을 쿠키로 내려준다. 팝업이 결과를 넘기고 바로 닫히면 refresh 응답이 도착하기 전에 창이 닫힐 수 있다. 이때 서버에서는 기존 토큰이 이미 폐기됐는데 브라우저에는 새 쿠키가 저장되지 않는다. 그러면 부모 창의 다음 refresh가 401로 실패하고 모든 탭이 로그아웃된다.

- `AuthContext`의 `bootstrapAuth` 시작에서 `pathname === '/payments/result'`이고 `isSameOriginOpener()`이면 refresh 없이 `isAuthReady = true`, `user = null`로 끝낸다.
- 이 예외는 이 경로와 팝업 조건에만 둔다. 모바일·폴백은 기존 부트스트랩을 그대로 쓴다.
- 인증 코드 변경이므로 기존 `AuthContext`·`auth-store` 테스트와 함께 `팝업 모드에서 refresh 요청이 0회`인 테스트를 추가한다.

#### 결과 불명 재조회

- `APPROVING`·`CANCELING`·`NEEDS_REVIEW`를 받으면 `getPayment`를 3초, 5초, 10초, 이후 15초 간격으로 최대 2분 동안 재조회한다. 서버가 조회 시점 복구를 하므로 재조회만으로 결과가 확정될 수 있다.
- 2분이 지나면 재조회를 멈추고 `결제 내역에서 결과를 확인할 수 있어요`와 `결제 내역 보기`를 보여준다. 이 상태에서 새 결제를 유도하지 않는다.
- 탭이 숨겨지면(`visibilitychange`) 재조회를 멈추고, 다시 보이면 한 번 조회한다.

#### 성공 후 갱신

- 충전: 잔액·마일리지 내역·결제 내역을 다시 불러온다.
- 멤버십: `GET /api/v1/memberships/me`와 `/api/v1/auth/me`를 다시 불러와 `updateUser`로 `membershipBadge`를 갱신한다(헤더·사이드바 배지 즉시 반영).

### 12-7. 상태 표시 매핑

| 서버 상태 | 결제 내역 배지 | 결과 화면 |
|---|---|---|
| `APPROVED` | `완료` | 완료 |
| `CANCELED` | `취소됨` | — |
| `APPROVING`, `CANCELING`, `NEEDS_REVIEW` | `확인 중` | 결제 확인 중 |
| `CREATED`, `READY` | (목록 제외) | 결제 진행 중(부모 창) |
| `FAILED`, `EXPIRED` | (목록 제외) | 결제 안 됨 |

### 12-8. API 모듈과 타입

기존 방식대로 `apiRequest()` + 도메인 `toError`를 쓴다(`mileage-api.ts` 참고). 응답 필드 이름은 백엔드 DTO 확정 시 맞춘다(`확인 필요`).

**`src/lib/payment-api.ts` (신설)**

```ts
export type PaymentStatus =
  | 'CREATED' | 'READY' | 'APPROVING' | 'APPROVED'
  | 'CANCELING' | 'CANCELED' | 'FAILED' | 'EXPIRED' | 'NEEDS_REVIEW';
export type PaymentPurpose = 'MILEAGE_CHARGE' | 'MEMBERSHIP_PASS';

export interface PaymentProduct {
  productCode: string; purpose: PaymentPurpose; name: string;
  price: number; mileage?: number | null; durationDays?: number | null;
}
export interface PaymentReadyResponse {
  orderId: string; status: PaymentStatus; pcUrl?: string | null; mobileUrl?: string | null;
}
export interface PaymentResponse {
  orderId: string; status: PaymentStatus; purpose: PaymentPurpose; productCode: string;
  itemName: string; amount: number; paymentMethodType?: 'CARD' | 'MONEY' | null;
  createdAt: string; approvedAt?: string | null; canceledAt?: string | null;
}

fetchPaymentProducts()                       // GET  /api/v1/payments/products
readyPayment({ productCode, requestId })     // POST /api/v1/payments/kakao/ready
approvePayment({ orderId, pgToken })         // POST /api/v1/payments/kakao/approve
getPayment(orderId)                          // GET  /api/v1/payments/{orderId}
fetchMyPayments(cursor?)                     // GET  /api/v1/payments → CursorPage<PaymentResponse>
```

**`src/lib/membership-api.ts` (신설)**: `fetchMyMembership()` → `GET /api/v1/memberships/me` (`active`, `expiresAt`, `price`, `durationDays`, 혜택 요약).

**기존 모듈 변경**

| 파일 | 변경 |
|---|---|
| `mileage-api.ts` | `chargeMileage` 제거 |
| `auth-api.ts` | `AuthUser.membershipBadge?: boolean`, `/auth/me` 매핑 |
| `feed-api.ts` | `PostRecord`·`CommentRecord.authorMembershipBadge`, `UserProfile`·`FollowUserRecord`·`ReposterInfo.membershipBadge`, 본인 게시물 `editableUntil`·`editCount`, `updatePostContent(postId, content)`(`PATCH /api/v1/posts/{postId}`) |
| `notification-api.ts`, `message-api.ts`, `mentoring-api.ts` | 사용자 객체에 `membershipBadge`, 멘토링 신청에 `feeRateBp`·`feeAmount`·수령액 |
| `post-mutations.ts` | `updatePostsContent(posts, postId, content, editCount)` |

배지 필드는 정규화 단계에서 `Boolean(raw.membershipBadge)`로 기본값 false를 준다. 백엔드 배지 PR 전에도 화면이 깨지지 않는다.

**오류 표시**

| HTTP | 화면 |
|---|---|
| 400 | 서버 `message` (잘못된 상품 등) |
| 401·차단 | 기존 `api-client` 정책(세션 종료) |
| 403 | `권한이 없는 주문입니다` |
| 404 | `주문을 찾을 수 없습니다` |
| 409 | 서버 `message` (상태 충돌, 다른 상품의 같은 요청, 취소 진행 중 등) |
| 503·네트워크 | `결제 서비스를 잠시 사용할 수 없어요. 잠시 후 다시 시도해주세요` |

approve 단계의 네트워크 오류·5xx는 실패로 표시하지 않고 `결제 확인 중`으로 전환해 재조회한다.

### 12-9. 배지 표시 위치

| 화면 | 파일·위치(현재 코드) | 데이터 |
|---|---|---|
| 피드 게시물 작성자 | `home/components/Post.tsx` `{post.author}` 제목 | `authorMembershipBadge` |
| 리포스트 안내 | `Post.tsx` `{post.reposterInfo.nickname}님이 리포스트했습니다` | `reposterInfo.membershipBadge` |
| 게시물 상세·댓글 | `home/components/PostDetail.tsx` 작성자 제목, `{comment.author}` | `authorMembershipBadge` |
| 알림 | `home/components/NotificationPanel.tsx` `actorName` | `actor.membershipBadge` |
| 검색 계정 | `(app)/search/page.tsx` `{account.nickname}` | `membershipBadge` |
| 프로필 헤더·팔로우 목록 | `(app)/profile/[userId]/page.tsx` `{profile.nickname}`, `{followUserRecord.nickname}` | `membershipBadge` |
| DM 대화 목록·수신자 선택 | `(app)/messages/page.tsx` `recipient.name` | `membershipBadge` |
| 멘토링 | `(app)/mentoring/page.tsx` 멘토 프로필, 리뷰 작성자, 신청 카드의 멘토·멘티 | 멘토링 DTO 배지 필드 |
| 헤더·사이드바 본인 | `Header.tsx`, `Sidebar.tsx` `{user.nickname}` | `AuthUser.membershipBadge` |

관리자 화면(`(admin)`)은 제외한다. 북마크·해시태그·홈은 `Post` 컴포넌트를 재사용하므로 자동 반영된다.

### 12-10. 보안·접근성 체크

- `pg_token`은 URL에서 즉시 제거하고 `localStorage`·`sessionStorage`·로그·에러 리포트에 남기지 않는다. `console.log`로 결과 객체를 출력하지 않는다.
- `postMessage`는 대상 origin을 `window.location.origin`으로 지정하고, 수신 측은 origin·source·type·orderId를 모두 검증한다.
- `returnTo`는 `/`로 시작하고 `//`로 시작하지 않으며 `/mentoring`·`/membership`·`/wallet` 접두사일 때만 허용한다. 그 외에는 무시한다.
- 결제 버튼은 요청 중 비활성, 이중 클릭으로 ready를 두 번 보내지 않는다(같은 `requestId`라 서버도 막는다).
- 패키지 선택 radiogroup, 모달 포커스 가두기, 결과 제목 포커스 이동, `aria-live` 상태 알림, 왕관 아이콘 `aria-label`.
- 다크 모드에서 카드·배지·상태색을 확인한다.

### 12-11. 구현 순서

| 순서 | 작업 | 시작 조건 |
|---|---|---|
| 1 | `MembershipBadge`, 타입·정규화에 배지 필드(기본 false), 표시 위치 삽입 | 즉시 |
| 2 | `payment-api.ts`·`membership-api.ts` 타입과 함수, `payment-device.ts`, `returnTo` 검증 유틸과 단위 테스트 | 즉시(mock 테스트) |
| 3 | `/wallet` 화면(잔액·패키지·마일리지 내역 이동), 멘토링 가상 충전 제거·잔액 요약·`programId` 딥링크 | 상품 목록·ready API(#85) |
| 4 | 결제 진행(PC 팝업·모바일), `/payments/result`, `AuthContext` 팝업 예외 | ready·approve·조회(#85) |
| 5 | 결제 내역 탭, 결과 불명 재조회 | 목록·복구(#86) |
| 6 | `/membership`, 성공 후 사용자 재조회, 메뉴·설정 진입점 | 내 멤버십 조회(#89) |
| 7 | 게시물 수정 메뉴·모달·목록 갱신 | 수정 API(#92) |
| 8 | 멘토 수수료 표시 | 요율 응답(#91) |

가상 충전 제거(3)는 백엔드의 무료 충전 API 제거(#86)와 같은 시점에 머지한다. 백엔드 API가 먼저 사라지면 기존 화면이 실패한다.

### 12-12. 테스트

- **Vitest(단위):** `returnTo` 허용 규칙, `isMobileBrowser`, 상태 → 화면 매핑, 결과 불명 재조회 간격·중단, 금액 표기, `updatePostsContent`.
- **Vitest(컴포넌트):** 패키지 radiogroup 키보드 선택, 팝업 차단 시 ready 미호출, 부모 창 `message` 검증(다른 origin·다른 orderId 무시), 결과 페이지 팝업 모드에서 postMessage 후 닫힘·approve 미호출·**refresh 미호출**, 폴백에서 인증 실패 시 approve 미호출, approve 이중 실행 방지, 게시물 수정 노출 조건·무변경 비활성.
- **Playwright(E2E, `page.route`로 API mock):** PC 팝업 전체 흐름(`context.waitForEvent('page')`), 팝업 수동 종료, 모바일 viewport 페이지 이동, 결과 불명 → 확정, 잔액 부족 → 충전 → 멘토링 복귀.
- **실제 카카오페이 결제 확인:** 백엔드 개발자센터 설정 후 데모 계정으로 PC 팝업·모바일 복귀를 확인한다.

### 12-13. 확인 필요

- 백엔드 응답 DTO 필드 이름(상품·결제·멤버십·배지·수정 정보·수수료). 각 백엔드 PR 머지 시 12-8절 타입을 맞춘다.
- `PATCH /api/v1/posts/{postId}` 응답 형태(수정된 게시물 반환인지 `data: null`인지).
- 실제 브라우저에서 카카오페이 도메인을 거친 팝업의 `window.opener` 유지 여부(COOP 미설정 기준으로는 유지).
- 카카오페이 결제창 권장 팝업 크기. 현재 480×720으로 두고 실제 화면에서 조정한다.
