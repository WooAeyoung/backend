# 우애영 Backend

운영 백엔드는 **Java 17 + Spring Boot**입니다. 기존 **Python + FastAPI** 구현은 영양 계산 기준 검증과 전환기 회귀 비교용으로 보존합니다.

```text
React·TypeScript 프론트
          ↓
Java·Spring 운영 API
          ↕ 결과 비교
Python·FastAPI 계산 기준·데이터 검증
```

> 제품 수치는 기능 검증용 데모이며, 영양 기준선은 FEDIAF Nutritional Guidelines 2025를 적용합니다. 이 서비스는 실제 급여 판단이나 수의학적 처방을 대신하지 않습니다.

## Java 운영 서버 실행

```powershell
cd C:\Users\yeoh0\wooaeyoung1\backend\spring-backend
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:+UseSerialGC'
.\mvnw.cmd spring-boot:run
```

기본 주소는 `http://localhost:8080`입니다. 자세한 환경변수와 API는 [spring-backend/README.md](spring-backend/README.md)를 참고하세요.

## Python 기준 서버 실행

```powershell
cd C:\Users\yeoh0\wooaeyoung1\backend
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
uvicorn app.main:app --reload
```

Python 서버의 기본 주소는 `http://localhost:8000`입니다. 운영 기능을 새로 추가할 때는 Java에 구현하고, Python 계산 코드는 기준 수치 검증이 필요할 때만 변경합니다.

## API

- `GET /health`
- `GET /api/v1/products`
- `GET /api/v1/nutrients`
- `POST /api/v1/analyses`
- `POST /api/v1/recommendations`
- `POST /api/v1/kakao/skill`

프론트는 Java 서버의 `VITE_API_URL`을 사용합니다. 카카오 OAuth 자동 등록만 전환 기간 동안 Python 서비스로 연결합니다.

## 테스트

Java API 테스트:

```powershell
cd spring-backend
$env:MAVEN_OPTS='-Xms64m -Xmx256m -XX:+UseSerialGC'
.\mvnw.cmd -DforkCount=0 test
```

Python 기준 테스트:

```powershell
pytest -q
```

두 서버를 각각 8000·8080 포트로 실행한 뒤 결과 일치 확인:

```powershell
python scripts\compare_backends.py
```

## Render 배포

- `render.yaml`: Java Spring 운영 서버
- `render-python-legacy.yaml`: Python 기준·복구 서버

운영 전환 전 Java `/health`, 영양 분석, 추천, 카카오 `/상태`를 확인합니다. Python 서비스는 Java 운영 검증이 끝날 때까지 중단하지 않습니다.

영양 기준 출처: https://europeanpetfood.org/wp-content/uploads/2025/09/FEDIAF-Nutritional-Guidelines_2025-ONLINE.pdf

수치·단위·각주 대조 기록: [docs/standards-validation.md](docs/standards-validation.md)
