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
- `GET /api/v1/nutrients`
- `POST /api/v1/analyses`
- `POST /api/v1/recommendations`
- `POST /api/v1/kakao/skill`

프론트엔드는 `VITE_API_URL=http://localhost:8080`으로 연결합니다. Python 서버와 응답을 비교한 뒤 Render 실행 대상을 이 프로젝트로 전환하세요.

카카오 스킬의 상태·이슈 생성·프론트 배포 명령도 Java에 구현했습니다. 전환 기간의 `/로그인` 명령은 `KAKAO_OAUTH_BASE_URL`로 지정한 기존 Python OAuth 주소를 사용합니다. GitHub 변경 명령은 `GITHUB_TOKEN`과 `KAKAO_ALLOWED_USER_IDS`가 모두 설정된 경우에만 실행됩니다.

테스트는 다음 명령으로 실행합니다.

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:+UseSerialGC'
.\mvnw.cmd -DforkCount=0 test
```

## 기준 프로필 (FEDIAF_2025 · NIAS_V3)

Python 서버와 같은 기준 프로필 구조를 Java에도 구현했습니다. 계약은 [../docs/api-standard-profiles.md](../docs/api-standard-profiles.md)를 따릅니다.

- 데이터: `src/main/resources/standards/` (Render Docker 빌드가 `spring-backend`만 쓰므로 `../data/standards`의 복사본). `StandardsIntegrityTest`가 두 복사본이 같은지 검사하므로 기준값을 바꿀 때는 두 곳을 함께 고칩니다.
- 코드: `domain/standard/` (모델·로더·무결성 검사·레지스트리), `NutritionService` (프로필별 계산).
- 미검증 기준값: `ALLOW_UNVERIFIED_STANDARDS`(기본 false). Java는 시작 시 한 번 읽습니다.
- FEDIAF 회귀: `src/test/resources/golden/fediaf_2025.json`은 리팩터링 전 Java 응답입니다. 의도적으로 기준을 바꿀 때만 다시 만듭니다.

```powershell
.\mvnw.cmd -DforkCount=0 "-Dgolden.generate=true" "-Dgolden.force=true" -Dtest=FediafGoldenTest test
```
