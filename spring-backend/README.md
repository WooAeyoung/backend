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
