# 우애영 Spring Boot Backend

기존 Python/FastAPI 서버의 프론트 연동 계약을 유지하는 Java 17 + Spring Boot 구현입니다.

## 실행

```powershell
cd spring-backend
.\mvnw.cmd spring-boot:run
```

기본 주소는 `http://localhost:8080`이며 Render에서는 `PORT` 환경변수를 사용합니다.

## API

- `GET /health`
- `GET /api/v1/products?query=&type=&limit=20`
- `GET /api/v1/nutrients` (선택 쿼리 `standardProfile`)
- `POST /api/v1/analyses`
- `POST /api/v1/recommendations`
- `POST /api/v1/kakao/skill`

## 기준 프로필 (`standardProfile`)

분석·추천 요청 본문과 `GET /api/v1/nutrients` 쿼리에 선택 필드 `standardProfile`을 줄 수 있습니다. 기존 필드는 이름·타입·의미가 그대로이고 새 필드만 추가했습니다.

| 값 | `standardVersion` | 설명 |
| --- | --- | --- |
| 미지정 또는 `FEDIAF_2025` | `FEDIAF-2025.09` | 기존 운영 기준. 결과는 리팩터링 전과 같습니다(골든 테스트로 고정). |
| `NIAS_V3` | `NIAS-v3` | 농촌진흥청 국립축산과학원 『반려동물 사료 영양표준』 기반. 기준값·매개변수가 모두 원문 대조 전(`PENDING_VERIFICATION`)이라 판정에 쓰지 않습니다. |

잘못된 값은 다른 enum 오류와 같은 400 응답입니다.

기준 데이터는 `src/main/resources/standards/`에 있고 시작 시 프로필별로 한 번 읽어 검증합니다. 파일이 깨졌거나 무결성 규칙을 어기면 애플리케이션이 시작하지 않습니다.

- `nutrients-master.json`: 영양소 마스터 44개(NIAS 성분 ID 1~44). 프로필은 문자열 키로 참조합니다.
- `{fediaf-2025|nias-v3}/nutrients.json`: 성분·생애주기별 기준값 레코드(값, 단위, 출처, 표, 검증 상태). 빈칸은 `null`입니다.
- `{fediaf-2025|nias-v3}/parameters.json`: 열량 계수, 생애주기 경계, 주의 비율, Ca:P, 추천 가중치 등.
- NIAS 파일은 `python scripts/convert_nias_v3_standards.py`가 `docs/v3/기준값표_매개변수표_v3.xlsx`에서 만듭니다. `--check`로 커밋된 파일이 xlsx와 같은지 확인합니다.
- FEDIAF `nutrients.json`은 리팩터링 전 Java 기준값 표에서 일회성으로 옮겼고, 이제 이 JSON이 원본입니다.

`wooaeyoung.standards.allow-unverified`(기본 `false`)가 `true`이면 검증 대기 기준값으로도 판정합니다. `test` 프로필에서만 허용되며, 다른 환경에서 켜면 시작이 실패합니다. 켜져 있으면 응답의 `standardsVerification.allowUnverified`가 `true`이고 경고가 붙습니다.

### 응답에 추가된 필드

분석(`POST /api/v1/analyses`):

| 필드 | 내용 |
| --- | --- |
| `standardProfile` | 사용한 프로필(`FEDIAF_2025`, `NIAS_V3`) |
| `standardVersion` | 기존 필드. 이제 프로필에 따라 값이 바뀝니다 |
| `parameterVersion` | 매개변수 파일 버전 |
| `parameterVerification` | 매개변수 섹션별 검증 상태 |
| `standardsVerification.allowUnverified` | 검증 대기 기준값 사용 여부 |
| `verificationSummary` | 판정 근거별 성분 수(`judgedWithVerified`, `judgedWithUnverified`, `noStandard`, `unverifiedStandard`, `unsupported`, `unitConversionUnsupported`) |
| `ratioApplicability.calciumPhosphorus` | `APPLICABLE`, `NOT_APPLICABLE`(고양이), `UNAVAILABLE`, `UNVERIFIED_STANDARD`, `UNSUPPORTED` |
| `nutrients[].nutrientNo` | NIAS 성분 ID(마스터에 없는 OMEGA3는 `null`) |
| `nutrients[].verificationStatus` | 이 행의 판정 근거 검증 상태 |
| `nutrients[].reference.{minimum,caution,upper}` | 출처 블록: `sourceDocument`, `table`, `sourceYear`, `verificationStatus`, `valueType`, `unit`, `basis`, `note`. 기준값을 숨긴 행에도 보입니다 |

NIAS 프로필에서만 나오는 성분 상태:

- `UNVERIFIED_STANDARD`: 기준값이 검증 대기라 판정하지 않음. `minimum`·`caution`·`upper`는 `null`입니다.
- `UNSUPPORTED`: NIAS 성장기(성장곡선·고양이 3단계, BE-V3-005~007 미구현). `referenceEnergyKcal`도 `null`입니다.
- `UNIT_CONVERSION_UNSUPPORTED`: IU 성분의 제품 단위를 환산할 수 없음(BE-V3-010 미구현). `total`은 0이 아니라 `null`입니다.

추천(`POST /api/v1/recommendations`): `standardProfile`, `parameterVersion`, `parameterVerification`, `standardsVerification`, `warnings`. `NIAS_V3`는 추천식(BE-V3-012~013)이 없어 `items: []`와 이유 경고를 돌려줍니다.

`GET /api/v1/nutrients?standardProfile=NIAS_V3`는 44개 전체(`implemented`로 분석 대상 18개 표시)를, `FEDIAF_2025`는 6개를 기준값 레코드와 함께 돌려줍니다. 쿼리를 빼면 기존 응답과 같습니다.

프론트엔드는 `VITE_API_URL=http://localhost:8080`으로 연결합니다. Python 서버와 응답을 비교한 뒤 Render 실행 대상을 이 프로젝트로 전환하세요.

카카오 스킬의 상태·이슈 생성·프론트 배포 명령도 Java에 구현했습니다. 전환 기간의 `/로그인` 명령은 `KAKAO_OAUTH_BASE_URL`로 지정한 기존 Python OAuth 주소를 사용합니다. GitHub 변경 명령은 `GITHUB_TOKEN`과 `KAKAO_ALLOWED_USER_IDS`가 모두 설정된 경우에만 실행됩니다.

테스트는 다음 명령으로 실행합니다.

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:+UseSerialGC'
.\mvnw.cmd -DforkCount=0 test
```
