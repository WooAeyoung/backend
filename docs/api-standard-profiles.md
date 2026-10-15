# 기준 프로필 API 계약 변경 (프런트엔드용)

기존 요청은 그대로 동작한다. `standardProfile`을 보내지 않으면 FEDIAF 2025로 계산하고, 기존 응답 필드의 이름·값·순서는 바뀌지 않는다. 아래는 **추가**된 항목이다.

## 요청

| 위치 | 필드 | 값 | 비고 |
| --- | --- | --- | --- |
| `POST /api/v1/analyses`, `/recommendations` 본문 | `standardProfile` | `FEDIAF_2025`(기본) · `NIAS_V3` | 그 밖의 값은 422 |
| `GET /health`, `GET /api/v1/nutrients` 쿼리 | `standardProfile` | 같음 | 그 밖의 값은 422 |
| `GET /api/v1/nutrients` 쿼리 | `includeAll` | `true`면 마스터 전체(NIAS 44개, FEDIAF 44 + OMEGA3) | 기본은 해당 프로필이 분석하는 성분 |

- `NIAS_V3`에서 12개월 미만 개는 `profile.adultSize`(S·M·L·XL·XXL)가 필요하다. 없으면 422 `CALCULATION_INPUT_ERROR`. `expectedAdultWeightKg`는 FEDIAF에서만 필수다.
- FEDIAF에서 `expectedAdultWeightKg` 누락 422의 오류 위치(`detail[].loc`)가 `["body","profile"]`에서 `["body"]`로 바뀌었다. 메시지는 같다.

## 응답 (분석)

```jsonc
{
  "standardVersion": "FEDIAF-2025.09",          // 기존. NIAS는 "NIAS-v3"
  "lifeStage": "ADULT",                          // NIAS는 "DOG_ADULT", "CAT_GROWTH_1" 등
  "summary": { "...": 0, "unverifiedStandard": 0 },  // unverifiedStandard는 NIAS에만 있음
  "nutrients": [{
    "status": "ADEQUATE",                        // 새 상태 UNVERIFIED_STANDARD 추가
    "minimum": null, "caution": null, "upper": null,   // 미검증·빈칸은 null (0 아님)
    "reference": {
      "sourceDocument": "FEDIAF Nutritional Guidelines 2025",
      "sourceTable": "Table III-3b", "sourceFootnote": null,
      "unit": "MG", "basis": "per_1000kcal_ME",
      "verificationStatus": "VERIFIED", "verifiedAt": "2026-10-01",
      "appliedInJudgement": true
    }
  }],
  "ratios": { "calciumPhosphorus": { "status": "UNVERIFIED_STANDARD" } },  // NIAS 미검증 시
  "energyFactor": 110, "growth": { "...": "NIAS 강아지만" },             // NIAS에만 있음
  "standard": {
    "id": "FEDIAF_2025", "displayName": "FEDIAF 2025", "version": "FEDIAF-2025.09",
    "documentName": "...", "publisher": "...", "basis": "per_1000kcal_ME",
    "allowUnverifiedStandards": false,
    "verification": { "verified": 4, "unverified": 0, "notApplicable": 2, "unverifiedParameters": ["energyKDogAdult"] },
    "warnings": ["미검증 프로필: ..."]
  }
}
```

- 추천 응답에도 `standard`가 추가된다.
- `GET /health`: `standard`(프로필 요약)와 `availableProfiles`가 추가된다.
- `GET /api/v1/nutrients`: 최상위 `standard`, 항목별 `nameEn`·`category`·`baseUnit`·`prioritySupported`·`analyzed`가 추가된다. NIAS 단위는 G·MG·UG·IU이며 FEDIAF(MG·UG)와 다르다.

## 프런트엔드 작업

1. 분석 기록(localStorage)에 `traceId`와 함께 `standard.id`·`standard.version`을 저장한다. 백엔드에는 분석 기록 저장소가 없다.
2. `UNVERIFIED_STANDARD` 상태 문구와 색을 추가한다. "기준 없음"과 구분해 "원문 확인 전 기준"으로 표시한다.
3. 기준 프로필 선택 UI를 붙인다면 NIAS에서 체급(`adultSize`) 입력을 받는다.
4. 성분 단위·이름은 하드코딩하지 말고 `GET /api/v1/nutrients?standardProfile=`에서 읽는다.
5. `standard.warnings`를 결과 화면에 표시한다.

## Java Spring 서버

Java(`spring-backend`)도 같은 요청·응답 계약을 구현했다. 차이는 다음과 같다.

- FEDIAF의 `ratios.calciumPhosphorus.value`는 인이 0일 때 기존대로 Java는 `0`, Python은 `null`이다(기존 차이 유지). NIAS는 두 서버 모두 `null`이다.
- FEDIAF 추천 점수식과 데모 제품 데이터는 기존부터 두 서버가 다르며 이번 변경에서 바꾸지 않았다.
- `ALLOW_UNVERIFIED_STANDARDS`를 Python은 요청마다, Java는 시작 시 읽는다.
- `standardProfile` 오류: 본문 값 오류는 `VALIDATION_ERROR`(필드 메시지), 쿼리 값 오류는 `{"detail":{"code":"VALIDATION_ERROR"}}`로 둘 다 422다.
