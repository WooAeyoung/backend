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
- `POST /api/v1/jarvis` (현재 브라우저의 분석 결과를 포함한 자비스 명령)
- `POST /api/v1/kakao/skill`
- `GET /api/v2/standards/{profile}` (성분·기준값·출처·입력 선택지·체크섬)
- `GET /api/v2/parameters/{profile}` (매개변수 원문·검증 상태·체크섬)

분석·추천·조회 응답과 오류 응답의 계약은 아래 "API 계약"과 `docs/openapi.yaml`에 있습니다.

## 기준 프로필 (`standardProfile`)

분석·추천 요청 본문과 `GET /api/v1/nutrients` 쿼리에 선택 필드 `standardProfile`을 줄 수 있습니다. 기존 필드는 이름·타입·의미가 그대로이고 새 필드만 추가했습니다.

| 값 | `standardVersion` | 설명 |
| --- | --- | --- |
| 미지정 또는 `FEDIAF_2025` | `FEDIAF-2025.09` | 기존 운영 기준. 결과는 리팩터링 전과 같습니다(골든 테스트로 고정). |
| `NIAS_V3` | `NIAS-v3` | 농촌진흥청 국립축산과학원 『반려동물 사료 영양표준』 기반. 기준값·매개변수가 모두 원문 대조 전(`PENDING_VERIFICATION`)입니다. 기본 설정에서는 참고 판정으로 계산하고 경고를 붙이며, `allow-unverified=false`이면 판정하지 않습니다. |

잘못된 값은 다른 enum 오류와 같은 400 응답이고, `detail.reason`이 `STANDARD_PROFILE_UNKNOWN`입니다.

기준 데이터는 `src/main/resources/standards/`에 있고 시작 시 프로필별로 한 번 읽어 검증합니다. 파일이 깨졌거나 무결성 규칙을 어기면 애플리케이션이 시작하지 않습니다.

- `nutrients-master.json`: 영양소 마스터 44개(NIAS 성분 ID 1~44). 프로필은 문자열 키로 참조합니다.
- `{fediaf-2025|nias-v3}/nutrients.json`: 성분·생애주기별 기준값 레코드(값, 단위, 출처, 표, 검증 상태). 빈칸은 `null`입니다.
- `{fediaf-2025|nias-v3}/parameters.json`: 열량 계수, 생애주기 경계, 주의 비율, Ca:P, 추천 가중치 등.
- NIAS 파일은 `python scripts/convert_nias_v3_standards.py`가 `docs/v3/기준값표_매개변수표_v3.xlsx`에서 만듭니다. `--check`로 커밋된 파일이 xlsx와 같은지 확인합니다.
- FEDIAF `nutrients.json`은 리팩터링 전 Java 기준값 표에서 일회성으로 옮겼고, 이제 이 JSON이 원본입니다.

`wooaeyoung.standards.allow-unverified`가 `true`이면 검증 대기 기준값으로도 참고 판정합니다. 기본값은 `true`이고(환경변수 `WOOAEYOUNG_STANDARDS_ALLOW_UNVERIFIED`, 2026-10-26 `a5ae994`·`c9c1147`에서 변경), `false`로 두면 검증 대기 값은 판정·추천에 쓰지 않습니다. 켜져 있으면 응답의 `standardsVerification.allowUnverified`가 `true`이고 "참고 판정" 경고가 붙습니다.

### 응답에 추가된 필드

분석(`POST /api/v1/analyses`):

| 필드 | 내용 |
| --- | --- |
| `standardProfile` | 사용한 프로필(`FEDIAF_2025`, `NIAS_V3`) |
| `standardVersion` | 기존 필드. 이제 프로필에 따라 값이 바뀝니다 |
| `parameterVersion` | 매개변수 파일 버전 |
| `parameterVerification` | 매개변수 섹션별 검증 상태 |
| `standardsVerification.allowUnverified` | 검증 대기 기준값 사용 여부 |
| `verificationSummary` | 판정 근거별 성분 수(`judgedWithVerified`, `judgedWithUnverified`, `noStandard`, `unverifiedStandard`, `unsupported`, `unitConversionUnsupported`. NIAS는 `insufficientData`도) |
| `ratioApplicability.calciumPhosphorus` | `APPLICABLE`, `NOT_APPLICABLE`(고양이), `UNAVAILABLE`, `UNVERIFIED_STANDARD`, `UNSUPPORTED` |
| `nutrients[].nutrientNo` | NIAS 성분 ID(마스터에 없는 OMEGA3는 `null`) |
| `nutrients[].verificationStatus` | 이 행의 판정 근거 검증 상태 |
| `nutrients[].reference.{minimum,caution,upper}` | 출처 블록: `sourceDocument`, `table`, `sourceYear`, `verificationStatus`, `valueType`, `unit`, `basis`, `note`. 기준값을 숨긴 행에도 보입니다 |
| `energyBasis` | 기준 열량의 계산 근거(아래 표). NIAS만 채우고 FEDIAF는 `null` |

NIAS 프로필에서만 나오는 성분 상태:

- `UNVERIFIED_STANDARD`: 기준값 또는 열량 매개변수가 검증 대기라 판정하지 않음. `minimum`·`caution`·`upper`는 `null`입니다.
- `INSUFFICIENT_DATA`: 라벨 값은 있지만 환산에 필요한 원료 형태·수분·열량이 없어 추정하지 않음. `total`은 0이 아니라 `null`이고, 같은 요청의 다른 성분은 정상 계산합니다.
- `UNIT_CONVERSION_UNSUPPORTED`: 환산표에 없는 단위(예: IU 성분에 `CFU`). `total`은 `null`입니다.

자세한 환산 규칙은 아래 "단위 환산과 성분별 근거"를 보세요.

기준 없음(`NO_STANDARD`)은 두 프로필 모두 하한·주의선·상한이 전부 `null`일 때만 나옵니다. 주의선만 있는 성분도 판정합니다.

### NIAS 생애주기와 열량 (`NIAS_V3`)

나이는 입력 단위 그대로 쓰고 다른 단위만 환산합니다(주 = 개월 × 4.345). 주 경계(8·14주)는 주로, 개월 경계(4·9·12개월)는 개월로 비교하므로 3개월 개는 13.035주로 초기 성장기입니다.

| `lifeStage` | 종 | 조건 | k | 기준값 열(최소 / 최대) |
| --- | --- | --- | --- | --- |
| (422) | 개·고양이 | 8주 미만 | — | 계산하지 않음. 기존 응답 `CALCULATION_INPUT_ERROR` "8주 미만 개체는 현재 지원하지 않습니다." |
| `GROWTH_EARLY` | 개 | 14주 미만 | 성장곡선 k | `dog_early` / `dog_growth` |
| `GROWTH_LATE` | 개 | 12개월 미만 | 성장곡선 k | `dog_late` / `dog_growth` |
| `CAT_S1` / `CAT_S2` / `CAT_S3` | 고양이 | 4개월 이하 / 9개월 이하 / 12개월 미만 | 100 × 2.25 / 1.875 / 1.5 | `cat_growth` / `cat_growth` |
| `ADULT` | 개·고양이 | 12개월 이상 | 개 110, 고양이 100 | `dog_adult`·`cat_adult` |

`E = k × 체중^지수`(개 0.75, 고양이 0.67). 모든 계수는 `standards/nias-v3/parameters.json`에서 읽습니다. FEDIAF의 `lifeStage` 값(`GROWTH`, `GROWTH_EARLY`, `GROWTH_LATE`, `ADULT`)은 바뀌지 않았습니다.

강아지 성장기(12개월 미만 개)는 요청의 `profile.adultSize`가 필요합니다. 허용값은 `S`, `M`, `L`, `XL`, `XXL`이고 대소문자를 구분합니다. 성체·고양이에서는 이 값을 무시하며, FEDIAF는 계속 `expectedAdultWeightKg`를 씁니다.

- 체급이 없으면 422 `CALCULATION_INPUT_ERROR` "체급 선택 필요: 성장기 강아지는 체급(adultSize: S, M, L, XL, XXL) 선택이 필요합니다."
- 목록에 없는 값이면 422 "지원하지 않는 체급입니다: X (S, M, L, XL, XXL)"

성장곡선은 `pct = min(a × ln(주령) − b, 100)`, `예상 성체 = 체중 ÷ (pct ÷ 100)`이고 `k = 254.1 − 135 × (pct ÷ 100)`입니다. 예상 성체 체중은 체급 범위(하한 초과·상한 이하, XXL은 하한만)로 절단합니다. 절단은 k에 영향을 주지 않습니다. 범위를 벗어나면 경고가 두 개 붙습니다.

- `WEIGHT_CLIPPED`(기존 코드값)
- `체급 또는 체중을 확인하세요: 예상 성체 체중 59.67 kg이 S 범위(0~7 kg)를 벗어나 7 kg으로 절단했습니다.`

개 성장기에서 절단한 예상 성체 체중이 31.75 kg 이상이면 칼슘 최대를 6.25 대신 4.5(1000 kcal당)로 씁니다. 이때 `reference.upper.table`은 `2-17c 각주 d`이고, `note`에 적용 근거가 나옵니다.

`energyBasis` 필드:

| 필드 | 내용 |
| --- | --- |
| `lifeStage` | 위 표의 값 |
| `kSource` | `ADULT_K`(성체 계수), `CAT_GROWTH_MULTIPLIER`(고양이 배수), `PUPPY_GROWTH_CURVE`(성장곡선) |
| `k`, `exponent`, `referenceEnergyKcal` | 사용한 k, 체중 지수, 기준 열량 |
| `blocked`, `blockedReason` | 운영 설정에서 열량 매개변수가 검증 대기라 계산하지 않았는지와 그 이유 |
| `parameterVerification` | 사용한 매개변수 섹션(`energy`, 강아지 성장기는 `growthCurve`·`largeBreedCalcium`도)의 검증 상태 |
| `adultK`, `catGrowthMultiplier` | 성체 계수, 고양이 성장 배수(해당 없으면 `null`) |
| `puppyGrowth` | 개 성장기만. `sizeClass`, `a`, `b`, `ageWeeks`, `percentOfAdultWeight`, `expectedAdultWeightRawKg`(원값), `expectedAdultWeightKg`(절단값), `clipped`, `sizeRange{lowerKg, upperKg}`, `puppyIntercept`, `puppySlope`, `largeBreedCalcium{applied, thresholdKg, calciumMaximum}` |

`blocked`가 `true`이면 검증 대기 매개변수에서 나온 숫자는 모두 `null`이고, `lifeStage`·`kSource`·`blockedReason`·`parameterVerification`·`puppyGrowth.sizeClass`만 채워집니다. 숫자는 반올림하지 않은 값입니다.

### 단위 환산과 성분별 근거 (`NIAS_V3`, Issue #3)

환산 규칙은 `docs/v3/영양성분_분석처리기준_v3.md` 7장을 따르고, 계수는 `src/main/resources/units/conversion-factors.json` 한곳에만 둡니다. 값은 `nutrients-master.json`의 기준 단위(g·mg·µg·IU)로 환산하며 반올림하지 않습니다. 이 기능은 `NIAS_V3`에서만 씁니다. `FEDIAF_2025`는 기존 계산을 그대로 유지합니다.

수동 입력 선택 필드(모두 생략 가능, 생략하면 기존과 같음):

| 필드 | 위치 | 값 |
| --- | --- | --- |
| `form` | `manualItems[].nutrients[]` | 비타민 A·D·E 원료 형태. `RETINOL`, `RETINYL_ACETATE`, `RETINYL_PROPIONATE`, `RETINYL_PALMITATE`, `RETINOL_EQUIVALENT`, `BETA_CAROTENE`, `CHOLECALCIFEROL`, `DL_ALPHA_TOCOPHERYL_ACETATE`, `D_ALPHA_TOCOPHEROL`, `D_ALPHA_TOCOPHERYL_ACETATE`, `DL_ALPHA_TOCOPHEROL`, 원료 미상 간주값 `UNKNOWN_ASSUME_RETINOL_EQUIVALENT`·`UNKNOWN_ASSUME_CHOLECALCIFEROL`·`UNKNOWN_ASSUME_DL_ALPHA_TOCOPHERYL_ACETATE` |
| `basis` | `manualItems[].nutrients[]` | `PER_SERVING`(기본), `PER_100G`, `PERCENT`, `PER_KG`, `PER_1000KCAL`, `DRY_MATTER_PERCENT` |
| `guarantee` | `manualItems[].nutrients[]` | `MIN`("이상"), `MAX`("이하"). 보증값이라 실제 함량으로 더하지 않습니다 |
| `moisturePercent` | `manualItems[]` | 수분 %(0 이상 100 미만). 건물 기준 환산에 씁니다 |
| `energyKcalPerKg` | `manualItems[]` | 사료 대사에너지(kcal/kg). 1000 kcal당 환산에 씁니다 |

- `unit`은 `g`, `mg`, `µg`(`μg`·`mcg`·`ug`도 허용), `IU`, `%`, `ppm`을 받고 대소문자는 구분하지 않습니다.
- NIAS 수동 입력은 `EPA`, `DHA`, `OMEGA3`, `BETA_CAROTENE` 성분 ID도 받습니다.
- `FEDIAF_2025`에 위 필드를 보내면 무시하고 경고 "FEDIAF_2025는 원료 형태·표기 기준·수분·열량 입력을 사용하지 않아 무시했습니다."를 붙입니다. 계산 결과는 필드가 없을 때와 같습니다.

환산 규칙:

| 라벨 표기 | 환산 | 정보가 없으면 |
| --- | --- | --- |
| mg/정·캡슐·스쿱·포(`PER_SERVING`) | 그대로(1회 제공량당) | — |
| mg/100 g(`PER_100G`) | ÷ 100 → 1 g당 | — |
| %(`PERCENT`) | ÷ 100 → 1 g당 g | — |
| mg/kg, IU/kg, ppm(`PER_KG`) | ÷ 1000 → 1 g당 | — |
| g·mg/1000 kcal(`PER_1000KCAL`) | × (kcal/kg ÷ 1000) ÷ 1000 → 1 g당 | 열량이 없으면 `INSUFFICIENT_DATA` |
| 건물 기준 %(`DRY_MATTER_PERCENT`) | × (1 − 수분 ÷ 100) ÷ 100 → 1 g당 g | 수분이 없으면 `INSUFFICIENT_DATA` |
| "이상/이하" 보증값 | 더하지 않음(정보 없음) | — |
| 비타민 A·D·E를 mg·µg로 표기 | 원료별 IU 계수(영양표준 표 7-5) | 원료가 없으면 `INSUFFICIENT_DATA`. 원료 미상 간주값을 고른 경우만 환산하고 note에 "원료 미상, ○○로 간주"를 남깁니다 |
| 고양이 베타카로틴 | 비타민 A에 더하지 않음(`EXCLUDED`) | — |
| EPA·DHA | 더해서 EPA+DHA. 한쪽만 있으면 표기된 값만 넣고 note에 남깁니다 | 총 오메가3만 있으면 정보 없음 |

1일 섭취량은 1 g당 값 × 1일 급여량(g), 또는 1회 제공량당 값 × (1일 급여량 ÷ 1회 제공량)입니다. 1 g당 표기는 `servingUnit`이 `G`일 때만 쓸 수 있습니다.

사료 몫은 성분마다 따로 정합니다(처리기준 7.1).

1. 사료에 그 성분의 환산된 값이 있으면 실제값(`ACTUAL`)
2. 라벨 값은 있지만 환산할 수 없으면 추정하지 않고 `INSUFFICIENT_DATA`(`total: null`)
3. 값이 없고, 사료 항목이 있고, 완전사료이고, 하한이 있으면 하한 × 사료 가정 배수(`ESTIMATED`). 사료가 여럿이어도 성분당 한 번만 더합니다
4. 그 밖에는 0으로 더하되 `unknownContribution: true`(실제 0과 구분)

사료 항목이 없으면 추정하지 않습니다(기존 Java 동작. 참조 구현은 완전사료면 사료 없이도 추정합니다).

NIAS 분석 응답에 추가된 필드:

| 필드 | 내용 |
| --- | --- |
| `nutrients[].provenance[]` | 제품별 근거: `name`, `type`, `source`(`ACTUAL`·`ESTIMATED`·`UNKNOWN`·`INSUFFICIENT_DATA`·`UNIT_CONVERSION_UNSUPPORTED`), `amount`(1일 기여량, 없으면 `null`), `note`, `conversion` |
| `nutrients[].provenance[].conversion` | 환산 기록: `status`, `value`, `perGram`, `originalValue`, `originalUnit`, `basis`, `form`, `baseUnit`, `formula`, `source`, `note` |
| `nutrients[].unknownContribution` | 값이 없는 제품의 몫을 0으로 계산했는지 여부 |
| `contributions[].nutrientSources` | 그 제품의 성분별 출처 |
| `verificationSummary.insufficientData` | `INSUFFICIENT_DATA` 성분 수 |

기존 `fromFeed`, `fromSupplements`, `source`, `total`의 의미는 그대로입니다.

제품 데이터(`products.json`)는 `ProductDataRegistry`가 시작할 때 검증합니다. `CatalogService`는 바뀌지 않았습니다.

- 선택 필드(없으면 기존 동작):
  - `hasDetail`, `contentBasis`(`PER_SERVING` 기본 / `PER_GRAM`)
  - `nutrientDetails`(성분별 라벨 원문 `{amount, unit, basis, form, guarantee}`. NIAS에서는 같은 성분의 `nutrients` 값보다 우선)
  - `moisturePercent`, `energyKcalPerKg`, `tags`, `dataSource`, `updatedAt`(ISO 날짜)
- 기존 `nutrients` 값의 단위: 6개 성분은 기존 단위(칼슘·인·비타민 E·오메가3·아연 mg, 비타민 D µg), 그 밖의 성분은 기준 단위입니다.
- 시작 실패: 알 수 없는 성분 ID, 음수 함량, 잘못된 단위, 성분과 맞지 않는 원료, `doseByWeight` 구간 오류(정렬 안 됨·겹침·상한 없는 구간이 마지막이 아님), `hasDetail` 모순, 중복 ID, 잘못된 날짜
- 알 수 없는 JSON 필드는 실패시키지 않고 WARN 로그(`제품 ID: 알 수 없는 필드 경로 (무시함)`)만 남깁니다.

추천(`POST /api/v1/recommendations`): `standardProfile`, `parameterVersion`, `parameterVerification`, `standardsVerification`, `warnings`. `NIAS_V3`는 `allow-unverified=false`이고 검증 대기 매개변수가 있으면 `items: []`와 이유 경고를 돌려주며, `allow-unverified=true`이면 아래 "NIAS 추천"의 점수식으로 계산합니다.

### NIAS 추천 (`NIAS_V3`, Issue #4)

규칙은 `docs/v3/계산명세서_v3.md` 10장과 `docs/v3/영양성분_분석처리기준_v3.md` 9장(A안)을 따르고, `NiasRecommendationService`가 계산합니다. `FEDIAF_2025` 추천은 바뀌지 않았습니다.

```
점수 = w2 × 보완 수 + w3 × 안전 여유 − w4 × 중복 수      (w2·w3·w4·topN은 nias-v3/parameters.json recommendation)
```

- 후보: 카탈로그 영양제 중 `recommendedDailyAmount` 또는 `doseByWeight`가 있는 제품. 성분 값은 `ProductDataRegistry`의 정규화 값(기준 단위, 종별 환산)입니다.
- 급여량: `doseByWeight`에서 체중 이하(`weightKg ≤ maxKg`)인 첫 구간. 구간은 (이전 `maxKg`, `maxKg`]이고 상한 없는 구간은 마지막입니다. 모든 닫힌 구간을 넘으면 마지막 구간을 쓰고, `doseByWeight`가 없으면 `recommendedDailyAmount`입니다.
- 거르기(탈락): 후보를 급여량만큼 더한 뒤 판정해서
  - 판정 대상 성분 중 하나라도 주의·과다(이미 주의·과다인 성분은 `ALREADY_CAUTION_OR_EXCESS`로 따로 표시)
  - 개이고 그 생애주기가 Ca:P를 판정하면, 칼슘·인을 담은 후보를 더한 뒤 비율이 범위를 벗어남(성장기처럼 Ca:P를 판정하지 않는 생애주기는 검사하지 않고 경고)
  - 합계가 `null`이거나 판정이 막힌 성분, 또는 후보 쪽 값을 환산하지 못한 성분을 후보가 담고 있고 그 성분에 주의선·상한이 있음(안전 확인 불가)
- 보완 수: 현재 부족인 성분 중 추가 후 부족이 아닌 성분 수. 합계가 `null`인 성분은 부족으로 세지 않습니다.
- 안전 여유: 후보가 담은 판정 대상 성분 중 주의선이 있는 성분(주의선만 있는 성분 포함)마다 `(주의 − 추가 후) ÷ (주의 − 하한)`의 최솟값. 하한이 없으면 0, 추가 후 값이 하한 미만이면 1, 대상 성분이 없으면 1. 평균을 쓰지 않습니다.
- 중복 수: 후보 성분 ∩ (현재 급여 영양제 성분(수동 입력 포함) ∪ 이미 고른 제품 성분). 사료는 넣지 않습니다.
- 선택: 최대 힙(`MaxHeap`)으로 1위를 고르고, 총량과 중복 성분을 갱신한 뒤 남은 후보를 다시 계산합니다. 최대 `maxItems`(없으면 `topN`)개. 현재 급여 중인 제품과 이미 고른 제품은 다시 추천하지 않습니다. 동점이면 `productId` 오름차순입니다.
- 점수와 안전 여유는 반올림하지 않습니다.

NIAS 추천 응답(기존 필드는 그대로, 아래는 추가만):

| 필드 | 내용 |
| --- | --- |
| `message` | 부족 성분이 있으면 "부족 성분을 보완하는 추천 조합입니다.", 없으면 "현재 조합에서 부족한 성분은 없습니다. 아래 제품은 추가해도 주의 구간에 들지 않습니다.", 통과 후보가 없으면 "현재 조합에 추가할 수 있는 제품이 없습니다." |
| `items[].scoreBreakdown` | 기존 `fixedNutrients`·`margin`·`overlap`에 `w2`·`w3`·`w4`, `fixedScore`(w2 × 보완), `marginScore`(w3 × 여유), `overlapPenalty`(w4 × 중복) 추가. `score = fixedScore + marginScore − overlapPenalty` |
| `items[].fixedNutrientIds` | 보완된 성분 ID |
| `items[].marginNutrientId` | 안전 여유가 가장 작은 성분 ID(대상이 없으면 `null`) |
| `items[].overlapNutrientIds` | 중복 성분 ID |
| `items[].doseBand` | `source`(`DOSE_BY_WEIGHT`·`RECOMMENDED_DAILY_AMOUNT`), `bandIndex`, `aboveKg`(초과), `maxKg`(이하, 상한 없으면 `null`), `dose`, `beyondLastBand` |
| `excluded[].reasonCode` | 대표 사유: `ALREADY_FED`, `EXCESS`, `CAUTION`, `ALREADY_CAUTION_OR_EXCESS`, `UNVERIFIABLE`, `CALCIUM_PHOSPHORUS` |
| `excluded[].reasons[]` | 사유 전체: `code`, `nutrientIds`, Ca:P면 `ratioStatus`(`LOW`·`HIGH`). 기존 `reason`은 같은 내용의 문장입니다 |

- `excluded`에는 제품마다 마지막 라운드의 사유 하나만 남깁니다. 고른 제품은 `items`에만 있습니다.
- `warnings`: 이전 NIAS 추천 경로는 차단되지 않으면 빈 목록이었습니다. 이를 유지하고 다음을 더합니다.
  - 맨 앞: `allow-unverified=true`이고 추천 매개변수가 검증 대기이거나 검증 대기 기준값으로 판정한 성분이 있으면 "참고값: NIAS-v3 기준값·추천 매개변수는 원문 대조 전이라 추천 결과는 검증되지 않은 참고 정보입니다."
  - 개인데 그 생애주기가 Ca:P를 판정하지 않으면 "칼슘:인 비율 미적용: …"
  - 이미 주의·과다인 성분이 있으면 "이미 주의·과다인 성분(…)이 있어 추가 후 판정에서 모든 후보가 탈락합니다. 현재 급여량을 먼저 확인하세요."
- 차단 응답(`allow-unverified=false`)과 `FEDIAF_2025` 응답은 바뀌지 않았습니다.
- `NiasV3Strategy.recommendationScore`(w3 항 없음)는 NIAS 추천에서 더 쓰지 않습니다.

`GET /api/v1/nutrients?standardProfile=NIAS_V3`는 44개 전체(`implemented`로 분석 대상 18개 표시)를, `FEDIAF_2025`는 6개를 기준값 레코드와 함께 돌려줍니다. 쿼리를 빼면 기존 응답과 같습니다.

## API 계약 (Issue #5, BE-V3-014~020)

- 명세: `docs/openapi.yaml`(OpenAPI 3.0.3). 응답·오류는 `api/ApiResponses.java`의 record이고, `OpenApiContractTest`가 이름·순서·타입·`required`·enum을 대조합니다. 스키마의 `properties` 순서가 실제 JSON 키 순서입니다.
- `required`에 있는 키는 값이 `null`이어도 항상 있습니다. `required`에 없는 키(`provenance`, `reasonCode`, `reason` 등)는 해당 프로필·상황에서만 있습니다.
- 하위 호환: 기존 필드의 이름·타입·의미·순서는 그대로이고, 새 필드는 기존 키 뒤 맨 끝에만 붙였습니다. `ResponseContractSnapshotTest`가 3cd1049 응답 60개·오류 26개와 바이트 단위로 대조합니다.
- 계산 결과는 바뀌지 않았습니다(FEDIAF 골든 25개, NIAS T1~T16).

### 추가된 응답 필드

| 엔드포인트 | 맨 끝에 추가 |
| --- | --- |
| `POST /api/v1/analyses`, `POST /api/v1/recommendations` | `checksums` |
| `GET /api/v2/standards/{profile}` | `labelInput`, `checksums` |
| `GET /api/v2/parameters/{profile}` | `parameters`, `checksums` |
| 400·422 오류 | `detail.reason` |

`GET /api/v1/nutrients`(쿼리 유무 모두)는 바뀌지 않았습니다.

키 순서가 고정된 객체: 전에는 `Map.of`로 만들어 서버를 다시 띄울 때마다 키 순서가 달라질 수 있었습니다. 값은 그대로이고 순서만 아래로 고정했습니다.

- `ratios.calciumPhosphorus`: `value`, `status`
- FEDIAF 추천 `excluded[]`: `productId`, `name`, `reason`
- `GET /api/v1/nutrients`(쿼리 없음) `items[]`: `id`, `name`, `unit`
- 400·422 오류 `detail`: `code`, `message`, `reason`

503 오류(JARVIS·외부 서비스)와 `/health`는 아직 `Map.of`라 키 순서가 고정되지 않았습니다.

### 오류 응답

```json
{"detail": {"code": "CALCULATION_INPUT_ERROR", "message": "8주 미만 개체는 현재 지원하지 않습니다.", "reason": "AGE_UNSUPPORTED"}}
```

`code`·`message`·HTTP 상태는 Issue #5 이전과 같습니다. 화면 분기는 `message` 문구가 아니라 `reason`으로 합니다.

| HTTP | `code` | `reason` | 상황 |
| --- | --- | --- | --- |
| 422 | `CALCULATION_INPUT_ERROR` | `AGE_UNSUPPORTED` | 8주 미만(미지원). 명세 T7의 "200 + 전 성분 미지원" 대신 422를 유지합니다 |
| 422 | `CALCULATION_INPUT_ERROR` | `SIZE_CLASS_REQUIRED` | NIAS 성장기 강아지에 `adultSize` 없음(T8) |
| 422 | `CALCULATION_INPUT_ERROR` | `SIZE_CLASS_INVALID` | 성장곡선에 없는 `adultSize` |
| 422 | `CALCULATION_INPUT_ERROR` | `EXPECTED_ADULT_WEIGHT_REQUIRED` | FEDIAF 12개월 미만 개의 예상 성체 체중 없음·현재 체중 미만(정보 부족) |
| 422 | `CALCULATION_INPUT_ERROR` | `PRODUCT_NOT_FOUND` | 카탈로그에 없는 `productId` |
| 422 | `CALCULATION_INPUT_ERROR` | `UNIT_MISMATCH` | 카탈로그 제품의 급여 단위와 다른 `unit` |
| 422 | `CALCULATION_INPUT_ERROR` | `UNIT_UNSUPPORTED` | 기준 단위로 환산할 수 없는 단위(질량 성분에 IU 등) |
| 422 | `CALCULATION_INPUT_ERROR` | `NUTRIENT_UNSUPPORTED` | 이 프로필에서 받지 않는 성분 ID |
| 422 | `CALCULATION_INPUT_ERROR` | `DUPLICATE_NUTRIENT` | 한 수동 제품 안에 같은 성분이 두 번 |
| 422 | `CALCULATION_INPUT_ERROR` | `NO_ITEMS` / `TOO_MANY_ITEMS` | 제품 0개 / 20개 초과 |
| 422 | `CALCULATION_INPUT_ERROR` | `LABEL_INVALID` | 라벨 표기 오류(원료 형태와 성분 불일치, `%`·`ppm`과 표기 기준 불일치, 수분 범위, 음수 함량 등, NIAS) |
| 422 | `CALCULATION_INPUT_ERROR` | `INPUT_ERROR` | 위에 없는 계산 입력 오류(기본값) |
| 422 | `VALIDATION_ERROR` | 없음 | Bean Validation 실패(필수값, 범위). 메시지는 서버 로캘을 따릅니다 |
| 400 | `BAD_REQUEST` | `STANDARD_PROFILE_UNKNOWN` | 경로·쿼리·본문의 알 수 없는 `standardProfile` |
| 400 | `BAD_REQUEST` | 없음 | JSON 형식 오류, 그 밖의 알 수 없는 enum 값 |
| 503 | `JARVIS_PROVIDER_UNAVAILABLE`, `EXTERNAL_SERVICE_UNAVAILABLE` | 없음 | 외부 서비스 오류(본문 형식은 같고 키 순서는 고정 전) |

계산 오류는 메시지로 분류합니다(`api/ErrorReason.java`). 계산 코드의 발생 지점 30곳(`AnalysisException` 19곳, `UnitConversionService` 11곳)과 분류 패턴의 대응은 `ErrorReasonContractTest`가 소스를 읽어 확인하므로, 메시지를 바꾸거나 발생 지점을 더하면 이 테스트가 먼저 실패합니다.

### 조회 API (프런트 하드코딩 없이 쓰기)

| 필요한 것 | 어디서 |
| --- | --- |
| 지원 성분 목록·이름·응답 단위·기준 단위·분석 대상 여부 | `GET /api/v2/standards/{profile}` `items[]`의 `id`, `name`, `unit`, `standardUnit`, `implemented` |
| 생애주기별 기준값·출처·검증 상태 | 같은 `items[]`의 `minimum`·`maximum`·`caution` → 열 이름별 `value`, `sourceDocument`, `table`, `verificationStatus`, `verifiedAt` |
| 수동 입력 단위 | `labelInput.units[]`(`alias` → 정규화 `unit`) |
| 표기 기준과 필요한 추가 입력 | `labelInput.bases[]`(`requires`가 `ENERGY`면 `energyKcalPerKg`, `MOISTURE`면 `moisturePercent`) |
| 원료 형태 선택지 | `labelInput.forms.{VITAMIN_A,VITAMIN_D,VITAMIN_E}[]`(`species`가 `null`이면 모든 종, `excluded`면 그 종에서 더하지 않음, `assumed`면 원료 미표기 시 고르는 간주 값) |
| 라벨에서만 받는 성분 ID | `labelInput.labelOnlyNutrients`(`BETA_CAROTENE`, `DHA`, `EPA`, `OMEGA3`) |
| 체급 선택지·매개변수 값 | `GET /api/v2/parameters/{profile}` `parameters`(파일 원문, 예: `growthCurve.sizeClasses`의 `S`~`XXL`) |
| 매개변수 검증 상태·판정 설정 | 같은 응답의 `verification`, `allowUnverified` |

- `labelInput.units`에는 입력 별칭이 함께 들어 있습니다(`µg`·`μg`·`ug`·`mcg`는 모두 `µg`). 선택 상자에는 대표 표기(`unit`이 같은 것 중 하나)만 쓰고, 별칭은 직접 입력·붙여넣기 정규화에 씁니다.
- `FEDIAF_2025`는 환산표를 쓰지 않아 `labelInput`이 `null`입니다. FEDIAF가 받는 단위(`NutritionService`의 UG·MG·G)는 아직 조회 API로 노출하지 않습니다.
- 모든 값은 데이터 파일에서 읽은 그대로이고 빈칸은 `null`입니다(`LookupApiContractTest`).

### 체크섬과 재현성

- `checksums`: 응답 프로필의 `standards/{profile}/nutrients.json`(`standards`), `parameters.json`(`parameters`), `standards/nutrients-master.json`(`nutrientMaster`), `units/conversion-factors.json`(`units`, NIAS만), `products.json`(`products`)의 SHA-256입니다. CRLF를 LF로 바꾼 뒤 계산해 Windows 체크아웃과 CI·Docker에서 값이 같습니다. 자세한 내용은 `docs/standards-validation.md`의 "데이터 체크섬"을 보세요.
- `traceId` = 요청 본문 해시 16자 + `-` + UUID 8자. 같은 버전·같은 입력이면 UUID 8자를 뺀 응답 전체가 바이트 단위로 같습니다(`DeterminismTest`: 같은 서버, `ResponseContractSnapshotTest`: 다른 JVM).

### 계약 테스트

| 테스트 | 검사 |
| --- | --- |
| `contract/ResponseContractSnapshotTest` | 전환 전 응답 스냅숏 60개와 바이트 비교(맨 끝 추가 키만 허용), record가 Map 응답의 모든 키를 담는지 엄격 모드로 대조 |
| `contract/ErrorReasonContractTest` | 오류 스냅숏 26개(`reason`만 추가), 발생 지점 30곳 분류, 미분류 → `INPUT_ERROR` |
| `contract/OpenApiContractTest` | `docs/openapi.yaml` ↔ record |
| `contract/LookupApiContractTest` | 조회 API 값 = 데이터 파일 |
| `contract/DataChecksumTest` | 기록 값, LF·CRLF 동일 |
| `contract/NiasGoldenCasesTest` | T1~T16 목록(`contract/nias-t1-t16.json`)과 담당 테스트 존재, T13 |
| `contract/DeterminismTest` | 같은 입력 두 번 → UUID 외 동일 |

스냅숏은 계약을 의도적으로 바꿀 때만 `tools/verify/capture_contract_snapshots.py`로 다시 뜹니다(사용법은 스크립트 주석). `tools/verify/verify_api.py`는 기본 설정이면 `cases.json`, `ALLOW_UNVERIFIED=false`이면 `--cases tools/verify/cases-strict.json`으로 실행합니다.

### 응답 시간 참고값 (BE-V3-020)

`tools/verify/measure_latency.py`, 2026-11-05, 로컬 i7-1260P·Java 26, 127.0.0.1 연결 재사용, 순차 요청 300개(워밍업 50개 제외) 2회차.

| 시나리오 | p50 ms | p95 ms | 최대 ms |
| --- | --- | --- | --- |
| FEDIAF 분석 · 제품 20개 | 0.75 | 1.08 | 18.29 |
| NIAS 분석 · 카탈로그 19개 + 수동 1개(18개 성분) | 1.33 | 1.98 | 21.34 |
| FEDIAF 추천 · 사료 1개 | 0.62 | 0.90 | 17.13 |
| NIAS 추천 · 사료 1개(후보 4개, topN 3) | 0.74 | 1.35 | 26.20 |
| NIAS 조회 · 44개 성분 | 1.31 | 1.97 | 18.62 |

부하 시험이 아니라 단일 연결 참고값입니다. 현재 카탈로그의 추천 후보는 4개뿐이고 `CatalogService`가 `products.json`을 고정으로 읽어, "후보 수백 개" 조건은 재지 못했습니다(남은 작업).

프론트엔드는 `VITE_API_URL=http://localhost:8080`으로 연결합니다. Python 서버와 응답을 비교한 뒤 Render 실행 대상을 이 프로젝트로 전환하세요.

카카오 스킬의 상태·이슈 생성·프론트 배포 명령도 Java에 구현했습니다. 전환 기간의 `/로그인` 명령은 `KAKAO_OAUTH_BASE_URL`로 지정한 기존 Python OAuth 주소를 사용합니다. GitHub 변경 명령은 `GITHUB_TOKEN`과 `KAKAO_ALLOWED_USER_IDS`가 모두 설정된 경우에만 실행됩니다.

테스트는 다음 명령으로 실행합니다.

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:+UseSerialGC'
.\mvnw.cmd -DforkCount=0 test
```
