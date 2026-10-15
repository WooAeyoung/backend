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

## 기준 프로필 구조 (2026-10-15 추가)

FEDIAF 2025와 NIAS v3를 서로 섞이지 않는 **기준 프로필**로 분리했다. 기준값과 매개변수는 코드가 아니라 `data/standards/`에서 읽고, 시작 시 한 번 검증해 불변 객체로 보관한다.

```text
data/standards/
  nutrients.json              영양소 마스터 44개 + 프로필 전용 성분(FEDIAF OMEGA3) + 데모 제품 함량 단위
  fediaf_2025/profile.json    표시명·발행기관·문서명·버전·계산 매개변수(각각 검증 상태)·생애주기 매핑
  fediaf_2025/records.csv     기준값 레코드 (profile, nutrientId, lifeStage) 30행
  nias_v3/profile.json
  nias_v3/records.csv         44개 성분 × 기준표 생애주기 5개 = 220행
app/standards/
  models.py                   Pydantic frozen 모델, StandardProfileId·VerificationStatus
  loader.py                   CSV 빈 셀 → None, 무결성 검사(실패 시 서버 시작 중단)
  registry.py                 PROFILES(읽기 전용), get_profile, ALLOW_UNVERIFIED_STANDARDS
```

- 레코드 필드: `min`, `max`(빈칸 = null), `unit`, `basis`, `sourceDocument`, `sourceTable`, `sourceFootnote`, `verificationStatus`(VERIFIED·UNVERIFIED·NOT_APPLICABLE), `verifiedBy`, `verifiedAt`.
- 기준값은 양수만 허용한다. 0·음수·NaN·무한대가 들어오면 적재 단계에서 실패하므로 빈칸이 0으로 바뀌는 경로가 없다.
- 생애주기는 계산 단계(`lifeStages[].id`)와 기준표 열(`recordStage`)을 분리했다. NIAS 고양이 성장 1·2·3단계는 모두 `CAT_GROWTH` 레코드를 쓴다(매핑 확정 필요).
- FEDIAF 프로필의 기존 6개 성분 ID와 단위(mg·µg)는 바꾸지 않았다. 총 오메가3(`OMEGA3`)는 FEDIAF 전용이며 NIAS의 ALA·EPA+DHA로 매핑하지 않는다.
- 회귀 방지: `tests/golden/fediaf_2025.json`은 리팩터링 전 코드로 만든 응답 스냅샷이다. `tests/test_fediaf_golden.py`가 부동소수점까지 같은지 검사하며, 새 필드 추가만 허용한다.

## 검증 상태 정책

- `VERIFIED`: 원문 대조 기록이 있는 값. `sourceTable`과 `verifiedAt`이 필수다.
- `UNVERIFIED`: 원문 대조 전 값. `ALLOW_UNVERIFIED_STANDARDS`(기본 false)가 꺼져 있으면 판정·추천 점수·Ca:P 판정에 쓰지 않는다. 해당 성분은 `UNVERIFIED_STANDARD` 상태로 반환하고 기준선은 null이다.
- `NOT_APPLICABLE`: 기준을 적용하지 않는 항목(FEDIAF 비타민 E·오메가3)과 서비스 정책 값(주의선 비율, 단위 환산 상수).
- 미검증 에너지·생애주기 매개변수만 있는 경우에도 분석은 실행한다. 응답 `standard.verification.unverifiedParameters`에 이름을 남기고 `standard.warnings`에 "미검증 프로필" 경고를 넣는다. FEDIAF는 기존 `warnings` 목록을 바꾸지 않기 위해 `standard.warnings`에만 넣고, NIAS는 `warnings`에도 넣는다.
- `ALLOW_UNVERIFIED_STANDARDS=true`는 테스트·개발용이다. 켜져 있으면 `standard.allowUnverifiedStandards: true`와 경고를 응답에 표시한다.

## 미검증 목록 (2026-10-15 기준)

| 프로필 | 항목 | 상태 | 비고 |
| --- | --- | --- | --- |
| NIAS_V3 | 기준값 레코드 220개 전부 | UNVERIFIED | min·max 모두 null. 원문 확인 값을 받으면 채운다. 표 번호(2-17c·d·g·h)는 v3 기준값표 안내 시트에서 옮김 |
| NIAS_V3 | 에너지 k(110·100·225·187.5·150), 강아지 k식(254.1 − 1.35 × 비율), 대사체중 지수 | UNVERIFIED | 설계안 값 |
| NIAS_V3 | 경계 8주·14주·4·9·12개월 | UNVERIFIED | 설계안 값 |
| NIAS_V3 | 성장곡선 S~XXL a·b | UNVERIFIED | 기준값표_매개변수표_v3.xlsx 표 2-7a 설계안 값 |
| NIAS_V3 | 대형견 31.75 kg·칼슘 상한 4.5, Ca:P 1~2 | UNVERIFIED | 기준값 성격이라 플래그가 꺼져 있으면 판정에 쓰지 않음 |
| NIAS_V3 | 사료 3,600 kcal/kg | UNVERIFIED | 출처 미확인, 현재 계산에 사용하지 않음 |
| FEDIAF_2025 | 에너지 k(95·75·100), 강아지 k식(254.1 − 135 × 비율), 대사체중 지수 | UNVERIFIED | 이 문서에 원문 대조 기록이 없음. 계산은 기존과 동일 |
| FEDIAF_2025 | 기준값 레코드 | VERIFIED 20 / NOT_APPLICABLE 10 | 2026-10-01 대조. 검증자(`verifiedBy`)는 기록이 없어 null |
