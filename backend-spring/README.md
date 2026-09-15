# backend-spring — 우애영, Spring Boot 포트

기존 `backend/`(FastAPI, Python)를 Java/Spring Boot로 마이그레이션한 버전입니다.
Python 백엔드는 그대로 두고, 별도 모듈(`backend-spring/`)로 추가했습니다 — 포트도
`8757`로 달리 잡아서 `npm run dev`의 기존 흐름과 충돌하지 않습니다.

## 실행

시스템에 Maven이 없어도 동봉된 Maven Wrapper로 바로 실행 가능:

```bash
cd backend-spring
./mvnw spring-boot:run        # Windows cmd/PowerShell: mvnw.cmd spring-boot:run
```

또는 빌드 후 jar 실행:

```bash
./mvnw -DskipTests package
java -jar target/backend-spring-0.1.0.jar
```

기본 포트는 `8757` (`PORT` 환경변수로 변경 가능). 헬스체크: `GET /health`.

## 이번에 이식한 범위

원본 `backend/api.py`(엔드포인트 수십 개), `database.py`(SQLite CRUD 전체)까지
한 번에 전부 옮기면 검증 없이 코드만 방대해지므로, **핵심 분석 흐름부터** 충실히
포팅했습니다. Spring Boot를 처음 써보는 만큼, 이 정도가 구조를 익히고 나머지를
같은 패턴으로 이어서 확장하기에 적당한 단위라고 판단했습니다.

| 영역 | 원본 | 포트 상태 |
|---|---|---|
| 단위 환산 (F-011) | `units.py` | ✅ `units/Units.java` |
| 성분명 정규화 (F-032) | `nutrients.py` | ✅ `nutrients/Nutrients.java` |
| 섭취량 합산·상태판정·기여도 (F-012/013/016) | `nutrition.py` | ✅ `analyze/NutritionService.java` |
| 계정 인증 (F-028, PBKDF2+세션토큰) | `auth.py` | ✅ `auth/AuthService.java` |
| 카탈로그(제품23종·AAFCO 기준표) | `database.py` 일부 | ✅ `catalog/CatalogService.java` (CSV를 그대로 읽어 메모리에 로드) |
| 즉시 분석 API | `api.py`의 `/api/session/analyze` | ✅ `analyze/AnalyzeController.java` |
| 펫/제품 CRUD·식단 저장·복원 (F-025/026) | `database.py`의 pets/products/product_nutrients/feeding_plans | ✅ `pet/`, `product/`, `feeding/` 패키지 |
| 저장된 펫 기반 분석 API | `api.py`의 `/api/analyze`, `/api/contributions` (쿼리 파라미터형) | ✅ `analyze/PetAnalyzeController.java` (경로 변수형으로 재설계, 아래 참고) |
| **미이식**: 추천(F-019), 최저가(F-021), OCR(F-006/007/008), 즐겨찾기·리뷰·주문(shop.py) | `recommend.py`, `pricing.py`, `shop.py`, `ocr.py`, `label_ocr.py` | ⏳ 다음 단계 — 같은 계층 구조(도메인 record → Service → Controller)를 그대로 따라가면 됩니다 |

## 엔드포인트

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/health` | 헬스체크 |
| POST | `/api/auth/register` | `{email, password, displayName?}` |
| POST | `/api/auth/login` | `{email, password}` → `{token, user}` |
| POST | `/api/auth/logout` | `Authorization: Bearer <token>` |
| GET | `/api/auth/me` | `Authorization: Bearer <token>` |
| GET | `/api/catalog/products` | 제품 23종 |
| GET | `/api/catalog/standards` | AAFCO 환산 기준표 |
| POST | `/api/session/analyze` | `{selections:[{productId, dailyAmountG, active?}]}` → `{summary, intake}` |
| POST | `/api/contributions?nutrient=칼슘` | 성분별 제품 기여도 |
| GET | `/api/units` | 지원 단위 목록 |
| POST | `/api/units/convert` | `{value, unit, nutrient, servingBasisG?, servingsPerPack?}` |
| GET | `/api/pets` | 펫 목록. 로그인 시 본인 것 + 주인 없는 펫만 |
| POST | `/api/pets` | `{name, species?, weightKg?, lifeStage?, birthDate?, breed?, neutered?}` → `{petId}`. 로그인 상태면 해당 사용자에 연결 |
| GET | `/api/pets/{petId}` | 펫 상세 |
| POST | `/api/products` | `{productId, name, category, servingBasisG, monthlyPriceKrw?, nutrients?}` → 저장/갱신(upsert). 데모 카탈로그(`/api/catalog/products`)와 별개 테이블 |
| GET | `/api/products` | 저장된 제품 목록 |
| GET | `/api/products/{productId}` | 제품 상세 |
| POST | `/api/pets/{petId}/feeding` | `{productId, dailyAmountG, active?}` → 식단(급여 계획) 저장 |
| GET | `/api/pets/{petId}/feeding` | 해당 펫의 식단 목록 |
| GET | `/api/pets/{petId}/analyze` | 저장된 식단으로 분석 (`/api/session/analyze`와 응답 형태 동일) |
| GET | `/api/pets/{petId}/analyze/contributions?nutrient=` | 성분별 제품 기여도 |

> 원본 FastAPI는 `/api/analyze?pet_id=`, `/api/contributions?pet_id=&nutrient=`처럼 쿼리 파라미터로
> 폈지만, 이 포트는 이미 있는 `/api/pets/{petId}/feeding`과 일관되게 **경로 변수**로 폈습니다.
> 프론트에서 이 API를 붙일 때는 이 차이를 참고하세요. 제품 저장(`POST /api/products`)도 원본은
> 쿼리 파라미터였는데 이 포트는 다른 엔드포인트와 통일해 JSON 바디로 받습니다.

## 프론트엔드에서 붙여보기

`frontend/`는 원래 `/api`를 8756(FastAPI)으로 프록시합니다. Spring Boot(8757)로
바꿔서 실제로 붙는지 확인하려면 `frontend/vite.config.ts`의 프록시 대상 포트를
잠시 8757로 바꾸거나, `npm run frontend:dev`와 별도로 `curl`로 위 엔드포인트를
직접 호출해보는 쪽이 원본 FastAPI 서버를 건드리지 않아 더 안전합니다.

예:
```bash
curl -X POST http://127.0.0.1:8757/api/session/analyze \
  -H "Content-Type: application/json" \
  -d '{"selections":[{"productId":"food_a","dailyAmountG":100}]}'
```

## 참고

- 데이터베이스: `db/wooaeyoung-spring.db` (SQLite, users/sessions만 — Python 쪽
  `db/wooaeyoung.db`와 별도 파일이라 서로 건드리지 않습니다).
- 카탈로그는 `data/processed/*.csv`를 매번 다시 읽는 대신, 빌드 시점에
  `src/main/resources/data/`로 복사해 넣었습니다. 원본 CSV가 바뀌면 다시
  복사해야 합니다.
- **2026-09-14 검증 완료**: Maven Wrapper(`mvnw`/`mvnw.cmd`, `.mvn/wrapper/`)를 추가해
  시스템에 `mvn`이 없어도 `./mvnw`로 빌드/실행 가능. 이 환경에서 `./mvnw -DskipTests package`
  빌드 성공, jar 실행 후 카탈로그·세션분석·펫/제품/식단 CRUD·저장 기반 분석까지 위 엔드포인트
  전부 실제 호출로 검증함 (Windows, Java 17.0.20 Temurin 기준). 한글 페이로드(제품명·성분명)
  왕복도 확인.
- 검증 중 발견해 고친 버그: `PetService.createPet`이 처음엔 `INSERT` 후 별도 쿼리로
  `SELECT last_insert_rowid()`를 불렀는데, SQLite의 `last_insert_rowid()`는 **커넥션 단위**라
  커넥션 풀에서 다른 커넥션을 받으면 항상 0이 나왔음(`{"petId":0}`). `GeneratedKeyHolder`로
  INSERT와 키 조회를 한 스테이트먼트로 묶어 해결 — SQLite + JdbcTemplate로 자동증가 키를 받을 때
  일반적으로 조심해야 할 함정이니 이후 포팅에도 유의.
