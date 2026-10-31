# FEDIAF 2025 영양 기준 검증 기록

검증일: 2026-10-01  
공식 원문: [FEDIAF Nutritional Guidelines 2025](https://europeanpetfood.org/wp-content/uploads/2025/09/FEDIAF-Nutritional-Guidelines_2025-ONLINE.pdf)

## 적용 범위

건강한 개·고양이용 완전사료의 생애주기별 권장 농도인 Table III-3b(개, PDF 16쪽)와 Table III-4b(고양이, PDF 19쪽)의 `per 1000 kcal ME` 열을 사용한다. 코드에서는 개체별 기준 열량을 곱해 하루 기준선으로 변환한다.

`하루 기준선 = 원문 1000 kcal당 값 × 하루 기준 열량 / 1000`

## 코드 값 대조

| 종·단계 | 성분 | 원문 값/1000 kcal ME | 코드 값 | 변환·판단 |
| --- | --- | ---: | ---: | --- |
| 개 성체 | 칼슘 | 1.45–6.25 g | 1450–6250 mg | g × 1000 |
| 개 성체 | 인 | 1.16–4.00 g | 1160–4000 mg | g × 1000 |
| 개 성체 | 비타민 D | 159–800 IU | 3.975–20 µg | IU ÷ 40 |
| 개 성체 | 아연 | 최소 20.8 mg | 최소 20.8 mg | 동일 단위 |
| 개 초기 성장기 | 칼슘 | 2.50–4.00 g | 2500–4000 mg | 14주 미만 |
| 개 초기 성장기 | 인 | 최소 2.25 g | 최소 2250 mg | g × 1000 |
| 개 초기 성장기 | 비타민 D | 138–800 IU | 3.45–20 µg | IU ÷ 40 |
| 개 초기 성장기 | 아연 | 최소 25 mg | 최소 25 mg | 동일 단위 |
| 개 후기 성장기 | 칼슘 | 2.00–4.50 g | 2000–4500 mg | 기본값, 각주 b 예외 적용 |
| 개 후기 성장기 | 인 | 최소 1.75 g | 최소 1750 mg | g × 1000 |
| 개 후기 성장기 | 비타민 D | 125–800 IU | 3.125–20 µg | IU ÷ 40 |
| 개 후기 성장기 | 아연 | 최소 25 mg | 최소 25 mg | 동일 단위 |
| 고양이 성체 | 칼슘 | 최소 1.33 g | 최소 1330 mg | g × 1000 |
| 고양이 성체 | 인 | 최소 0.85 g | 최소 850 mg | g × 1000 |
| 고양이 성체 | 비타민 D | 83.3–7500 IU | 2.0825–187.5 µg | IU ÷ 40 |
| 고양이 성체 | 아연 | 최소 25 mg | 최소 25 mg | 동일 단위 |
| 고양이 성장기 | 칼슘 | 최소 2.50 g | 최소 2500 mg | g × 1000 |
| 고양이 성장기 | 인 | 최소 2.10 g | 최소 2100 mg | g × 1000 |
| 고양이 성장기 | 비타민 D | 70–7500 IU | 1.75–187.5 µg | IU ÷ 40 |
| 고양이 성장기 | 아연 | 최소 18.8 mg | 최소 18.8 mg | 동일 단위 |

## 각주와 비율 조건

- 개는 14주 미만을 초기 성장기, 14주 이상 1년 미만을 후기 성장기로 구분한다.
- Table III-3 각주 b에 따라 예상 성체 체중이 15 kg을 초과하는 개는 생후 6개월 미만 후기 성장기에 칼슘 최소 2.50 g/1000 kcal를 적용한다. 이후에는 2.00 g/1000 kcal를 적용한다.
- Ca:P 상한은 개 초기 성장기 1.6:1, 후기 성장기 기본 1.8:1이다. 각주 b가 적용되는 대형견 6개월 미만은 1.6:1, 성체는 2:1을 적용한다.
- 고양이 성장기의 Ca:P 상한은 1.5:1, 성체는 2:1이다. 현재 화면의 Ca:P 비율 판정은 개에서 제공한다.
- 법정 최대치가 건물 기준으로만 제시되어 열량 기준으로 직접 비교할 수 없는 값은 상한으로 임의 변환하지 않는다.

## 서비스 주의선 정책

FEDIAF 표는 최소 권장량과 최대치를 제공하며 별도의 `주의선`은 제공하지 않는다. 화면의 주의 상태는 최대치에 도달하기 전에 보호자에게 확인 신호를 주기 위한 서비스 내부 정책이다.

- 개: 적용 가능한 공식 상한의 75% 이상부터 `주의`
- 고양이: 적용 가능한 공식 상한의 50% 이상부터 `주의`
- 공식 상한이 없는 성분에는 주의선을 만들지 않는다.
- 화면과 API의 `cautionPolicy`에 이 정책을 표시해 FEDIAF 공식값과 구분한다.

이 비율은 영양학적 권장량이나 독성 한계가 아니며, 추천 후보를 보수적으로 거르는 제품 정책이다.

## 기준 없음 처리

- 제품 데이터의 비타민 E는 mg이지만 원문 기준은 IU이므로 물질 형태별 환산 근거 없이 비교하지 않는다.
- 제품 데이터의 `OMEGA3`는 총 오메가3이지만 원문은 ALA와 EPA+DHA를 구분하므로 같은 성분으로 간주하지 않는다.
- 두 성분은 `NO_STANDARD`로 표시하고 추천 점수와 안전 여유 계산에서 제외한다.

## 자동 검증

`tests/test_engine.py`는 위 표의 전체 기준 상수, 비타민 D 환산값, 서비스 주의선 표시, 14주·15 kg·6개월 경계, Ca:P 이탈 후보 제외를 검사한다. 실행 명령은 `pytest -q`다.

## 실행 검증 결과

- 검증일: 2026-10-01
- 공식 원문 대조: Table III-3b, Table III-4b 및 각주 a·b·f·h의 수치·단위·적용 조건과 코드 상수가 일치함
- 백엔드: `pytest -q` — 17개 테스트 통과
- 프론트엔드: `npm run build` — TypeScript 검사 및 Vite 프로덕션 빌드 통과
- 결론: F-12의 공식 기준 대조, 서비스 주의선 구분, 정적 웹·백엔드 일치 및 실행 검증 완료

## Java 기준 데이터 파일 (Issue #1)

- `spring-backend/src/main/resources/standards/fediaf-2025/nutrients.json`은 리팩터링 전 Java 기준값 표(`NutritionService.standards()`)에서 일회성으로 옮겼고, 이제 이 JSON이 원본이다. 값이 같다는 근거는 FEDIAF 골든 테스트(`FediafGoldenTest`) 25/25 통과다. 이 파일을 다시 만드는 생성기는 저장소에 두지 않는다.
- 위 표에서 대조된 값만 `VERIFIED`(verifiedAt 2026-10-01)로 두고, 빈칸은 `value: null`, `NOT_APPLICABLE`, table `N/A`로 보존한다.

### 기준 프로필 구조

| 프로필 | standardVersion | 기준값 | 매개변수 |
| --- | --- | --- | --- |
| `FEDIAF_2025` (기본) | `FEDIAF-2025.09` | `standards/fediaf-2025/nutrients.json` 6개 성분 | `standards/fediaf-2025/parameters.json` (리팩터링 전 Java 상수) |
| `NIAS_V3` | `NIAS-v3` | `standards/nias-v3/nutrients.json` 44개 성분(1차 구현 18개) | `standards/nias-v3/parameters.json` (`docs/v3` 매개변수표) |

- 영양소 마스터는 `standards/nutrients-master.json` 하나다. 성분 ID 1~44와 순서는 `docs/v3/기준값표_매개변수표_v3.xlsx`를 따르며, 상세설계 보고서 부록 B의 번호(예: 칼슘 19, 타우린 44)와 다르다.
- 기존 키 CALCIUM(20), PHOSPHORUS(21), ZINC(31), VITAMIN_D(33), VITAMIN_E(34)는 유지한다. OMEGA3는 FEDIAF 전용 키로 성분 ID가 없고, ALA(18)·EPA+DHA(19)로 자동 매핑하지 않는다.
- 레코드마다 `value`, `unit`, `basis`(per_1000kcal_ME), `sourceDocument`, `table`, `sourceYear`, `verificationStatus`, `verifiedAt`, `valueType`(LITERATURE/POLICY), `note`를 둔다.

### 검증 상태 정책

- `VERIFIED`: 이 문서로 원문과 대조한 값. 현재 FEDIAF 기준값(Ca, P, 비타민 D, 아연)과 FEDIAF 매개변수 중 생애주기 경계·대형견 칼슘 조건·주의 비율·Ca:P 상한이 해당한다.
- `PENDING_VERIFICATION`: 원문 대조 전. NIAS xlsx 값, EU 법정 주의값, AAFCO 주의값은 전부 이 상태다. 표 번호를 확인할 수 없으면 `table: "UNCONFIRMED"`이고, 그 레코드는 반드시 이 상태다(무결성 테스트로 강제).
- `NOT_APPLICABLE`: 빈칸(기준 없음). `value: null`, `table: "N/A"`, 사유는 `note`에 적는다.
- `PENDING_VERIFICATION` 값은 판정·추천에 쓰지 않는다. 해당 성분은 `UNVERIFIED_STANDARD` 상태와 경고로 응답하며 기준값을 공식 기준처럼 노출하지 않는다. `wooaeyoung.standards.allow-unverified=true`는 `test` 프로필에서만 허용되고, 켜면 응답에 표시된다.

### 검증 대기(PENDING_VERIFICATION) 목록

| 항목 | 위치 | 확인할 것 |
| --- | --- | --- |
| NIAS 최소·최대 전 성분 | `nias-v3/nutrients.json` (표 2-17c·d·g·h) | 원문 표의 수치·단위, NIAS 발행 연도(`sourceYear`가 현재 `null`) |
| 개 비타민 A 예외 주의값 62,500 IU | AAFCO Dog and Cat Food Nutrient Profiles (2014) | 표 이름(`table: UNCONFIRMED`) |
| EU 법정 주의값: 구리·아연·철·망간(개·고양이), 셀레늄(고양이) | FEDIAF Nutritional Guidelines 2021 | 건물 100 g당 원값과 표 번호(`table: UNCONFIRMED`) |
| NIAS 매개변수 전체 | `nias-v3/parameters.json` | 표 2-5·2-7a·2-7b·2-8·2-9·2-17c 각주 d 대조 |
| FEDIAF 매개변수: 8주 하한·월 환산(30.4375일), 열량 계수(95/75/100, 254.1/135, 지수), 사료 추정 배수, 추천 가중치 | `fediaf-2025/parameters.json` | 이 문서에 대조 기록 없음. 계산 동작은 바꾸지 않고 응답 `parameterVerification`에만 표시 |
| 1차 구현 18개 목록 | `nias-v3/nutrients.json` `implemented` | 팀 확정(현재는 xlsx "제안") |

## NIAS 성장기 계산 (Issue #2, BE-V3-005~008)

계산 규칙은 `docs/v3/계산명세서_v3.md` 1~5장을 따른다. 값은 모두 `nias-v3/parameters.json`(변환 원본 `docs/v3/기준값표_매개변수표_v3.xlsx` 매개변수표)에서 읽고 코드에 상수로 두지 않는다. 아래 항목은 전부 `PENDING_VERIFICATION`이라 운영 설정(`allow-unverified=false`)에서는 열량·기준선을 계산하지 않는다. 응답 `energyBasis.blocked`와 `blockedReason`에 그 사실이 나온다.

| 항목 | 값 | 출처(xlsx 매개변수표 기재) | parameters.json 섹션 | 상태 |
| --- | --- | --- | --- | --- |
| 나이 경계 | 8주(개·고양이), 개 초기 성장 14주, 고양이 4·9개월, 성체 12개월, 4.345주/개월 | 표 2-7a·b(8주), 표 2-17a·c 각주(14주), 표 2-9(4·9개월). 고양이 8주는 개 값 준용(가정) | `age` | PENDING |
| 강아지 성장곡선 | 체급 S·M·L·XL·XXL별 a, b, 절단 범위 | 표 2-7a | `growthCurve` | PENDING |
| 강아지 k | 254.1 − 135 × (pct ÷ 100) | 표 2-7b | `energy.puppyIntercept/puppySlope` | PENDING |
| 성체 k·지수 | 개 110, 고양이 100, 지수 0.75 / 0.67 | 표 2-5, 표 2-8, 영양표준 2장 | `energy` | PENDING |
| 고양이 성장 배수 | 2.25 / 1.875 / 1.5 | 표 2-9(2.0~2.5, 1.75~2.0 범위의 중간값과 1.5) | `energy.catStage{1,2,3}Multiplier` | PENDING |
| 대형견 성장기 칼슘 | 예상 성체 ≥ 31.75 kg이면 최대 4.5 g/1000 kcal | 표 2-17c 각주 d | `largeBreedCalcium` | PENDING |

- 검산: T1~T8(계산명세서 11장)을 `NiasV3CalculationCasesTest`(test 프로필)로 고정했다. Java 결과는 `docs/v3/계산_참조구현_v3.py` 출력과 상대 차이 0으로 같다(2026-10-22 대조).
- 원문 대조할 것: 표 2-7a 체급별 계수와 절단 범위, 표 2-7b 강아지 k, 표 2-9 고양이 배수(중간값을 쓰기로 한 결정 포함), 표 2-17c 각주 d의 31.75 kg·4.5 g 조건(초과/이상, 초기·후기 성장 모두 적용 여부).
- 대형견 칼슘을 적용하면 응답 `reference.upper`는 `table: "2-17c 각주 d"`, `verificationStatus`는 `largeBreedCalcium` 섹션 상태다. 대체 전 값(6.25)과 적용 근거는 `note`에 남긴다.

## 단위 환산 계수 (Issue #3, BE-V3-009~011)

계수는 `spring-backend/src/main/resources/units/conversion-factors.json` 한곳에만 둔다. 항목마다 `source`에 출처 절을 적는다. 시작 시 표가 불완전하면(원료 형태·표기 형태 누락, 성분과 원료 불일치, 종 구분 중복) 애플리케이션 시작이 실패한다.

검증 상태: 모든 계수는 `docs/v3/영양성분_분석처리기준_v3.md` 7.3과 `docs/v3/영양소별_처리기준_일람_v3.xlsx` "단위 환산" 시트의 값을 그대로 옮겼다. 두 문서의 값은 서로 같다(2026-10-23 대조). **영양표준 표 7-5 원문과는 아직 대조하지 않았다.** 코드는 이 계수로 계산하며, 원문 대조 전이라는 사실을 아래 표에 남긴다.

| 항목 | 계수 | 출처 | 원문 대조 |
| --- | --- | --- | --- |
| 접두 단위 | 1 g = 1,000 mg = 1,000,000 µg, mcg = µg | 처리기준 7.3(2) | 정의값 |
| 라벨 표기 형태 | mg/100 g ÷ 100, % ÷ 100, mg/kg·IU/kg·ppm ÷ 1000, 1000 kcal당 × kcal/g ÷ 1000, 건물 기준 × (1 − 수분) | 처리기준 7.3(3) | 정의값 |
| 비타민 A 레티놀 | 0.3 µg = 1 IU | 처리기준 7.3(4), 영양표준 표 7-5 | 미대조 |
| 비타민 A 아세테이트 / 프로피온산 / 팔미테이트 | 0.344 / 0.359 / 0.55 µg = 1 IU | 처리기준 7.3(4), 영양표준 표 7-5 | 미대조 |
| 비타민 A 레티놀 당량(RE) | 1 µg RE = 3.33 IU | 처리기준 7.3(4), 영양표준 표 7-5 | 미대조 |
| 베타카로틴 | 개 1 mg = 833 IU, 고양이 환산하지 않음 | 처리기준 7.3(4), 영양표준 표 7-5 | 미대조 |
| 비타민 D3 | 1 µg = 40 IU | 처리기준 7.3(4), 영양표준 표 7-5 | 미대조 |
| 비타민 E | dl-α-토코페롤 아세테이트 1.00, d-α-토코페롤 1.49, d-α-토코페롤 아세테이트 1.36, dl-α-토코페롤 1.10 IU/mg | 처리기준 7.3(4), 영양표준 표 7-5 | 미대조 |
| 원료 미상 간주값 | E: dl-α-토코페롤 아세테이트(1 IU/mg), D: D3(40 IU/µg), A: RE(3.33 IU/µg) | E는 처리기준 7.3(4)의 간주 규정. D·A는 처리기준에 간주 규정이 없음 → Issue #3 결정(사용자가 명시적으로 고를 때만) | 해당 없음 |
| EPA+DHA | EPA + DHA, 총 오메가3만 있으면 정보 없음 | 처리기준 7.3(5) | 정의값 |

결정 기록:

- 원료 형태가 없으면 비타민 A·D·E 모두 `INSUFFICIENT_DATA`로 둔다. 처리기준 7.3(4)는 비타민 E 원료 미상을 아세테이트로 간주하라고 하지만, 이슈 완료 조건을 우선했다. 간주는 사용자가 간주값을 명시적으로 고를 때만 한다.
- EPA·DHA 중 한쪽만 표기되면 표기된 값만 EPA+DHA에 넣고 note를 남긴다. **처리기준에 없는 결정이다.**
- 소금(NaCl)·탄산칼슘의 원소 환산(처리기준 7.3(5))은 작업명세 BE-V3-010에 없어 구현하지 않았다.

## NIAS 추천 가중치 (Issue #4, BE-V3-012~013)

추천 규칙은 `docs/v3/계산명세서_v3.md` 10장, `docs/v3/영양성분_분석처리기준_v3.md` 9장(A안)을 따른다. 가중치와 개수는 `nias-v3/parameters.json`의 `recommendation` 섹션에서 읽고 코드에 상수로 두지 않는다(`NiasRecommendationService`가 `StandardParametersLoader`로 직접 읽는다).

| 항목 | 값 | 출처(xlsx 매개변수표 기재) | 의미 | 상태 |
| --- | --- | --- | --- | --- |
| w2 | 10 | "A안 확정. 우선순위 1" | 부족 보완 1개당 가점 | PENDING |
| w3 | 5 | "A안 확정. 보완 1개(10점)를 넘지 않게" | 안전 여유(0~1) 가점. 최대 5점이라 보완 1개를 넘지 않는다 | PENDING |
| w4 | 1 | "A안 확정. 동점 구분용" | 중복 성분 1개당 감점 | PENDING |
| topN | 3 | "확정" | 요청에 `maxItems`가 없을 때의 최대 추천 수 | PENDING |

- 이 값들은 문헌 수치가 아니라 서비스 정책 값이다. 원문 대조 대상은 "xlsx 매개변수표에 적힌 값과 같은가"이고, 2026-10-31에 `parameters.json`이 xlsx와 같음을 `scripts/convert_nias_v3_standards.py --check`로 확인했다. 팀 확정 기록이 문서로 남으면 `VERIFIED`로 바꾼다.
- `fediaf-2025/parameters.json`의 `w3 = 0`(기존 Java 동작 보존)은 바꾸지 않았다. FEDIAF 추천 결과는 그대로다.
- 운영 설정(`allow-unverified=false`)에서는 `recommendation` 섹션이 PENDING이라 NIAS 추천을 계산하지 않는다(`recommendationUnsupportedReason`). `allow-unverified=true`이면 계산하고, 응답 `warnings` 맨 앞에 참고값 경고를 붙인다.
- 검산: T14~T16(계산명세서 11장)을 `NiasRecommendationCasesTest`(test 프로필)로 고정했다. Ca:P 검사를 끈 상태에서 Java 결과가 `docs/v3/계산_참조구현_v3.py` 출력과 1e-9 이내로 같다(2026-10-31 대조).

결정 기록(Issue #4):

- Ca:P 이탈은 탈락시킨다(작업명세 BE-V3-012, Issue #4). 계산명세서 10.2와 참조 구현에는 Ca:P 거르기가 없어 서로 충돌한다. T14·T15는 Ca:P 검사를 끈 상태로 참조 구현과 비교하고, 켠 결과(T14 [비타민 E제], T15 [비타민 E제, 비타민 D제])는 별도 테스트로 둔다. Ca:P를 판정하지 않는 생애주기(개 성장기)는 검사하지 않는다.
- 안전 여유 대상은 "주의선이 있는 성분"이다(작업명세·Issue #4). 계산명세서 10.1과 참조 구현은 "상한이 있는 성분"이라 주의선만 있는 구리·아연·철·망간을 빼지만, 이 서비스는 넣는다. T14·T15 결과는 같다.
- 중복에는 현재 급여 영양제와 이미 고른 제품의 성분을 넣는다(참조 구현). 계산명세서 10.1은 현재 급여 영양제만 적는다.
- 동점이면 `productId` 오름차순이다(참조 구현의 heapq 순서). FEDIAF는 기존처럼 이름 순이다.
- 합계가 `null`인 성분은 부족으로 세지 않고, 그 성분에 주의선·상한이 있는데 후보가 담으면 안전 확인 불가로 탈락시킨다. 후보 쪽 값을 환산하지 못한 성분도 같다. **처리기준에 없는 결정이다.**
- 후보 성분의 환산 상태가 `EXCLUDED`(고양이 베타카로틴처럼 종 규칙으로 더하지 않음)이면 기여가 없는 것으로 보고 탈락 사유로 치지 않는다. `NO_VALUE`(보증값만 표기, 총 오메가3만 표기)는 실제 함량을 알 수 없으므로 환산하지 못한 성분과 같이 처리한다. **처리기준에 없는 결정이다.**
- 데모 데이터 영향: `supp-multi`(데일리 멀티)는 비타민 D(µg)·E(mg)의 원료 형태가 없어 IU로 환산할 수 없고(`INSUFFICIENT_DATA`), 비타민 D에 주의선·상한이 있으므로 NIAS 추천에서 `UNVERIFIABLE`로 탈락한다. 제품 데이터에 원료 형태(`nutrientDetails.form`)를 채우면 후보가 된다.
