# 우애영 Backend

사료와 영양제의 하루 성분량을 합산해 FEDIAF 2025 생애주기별 기준과 비교하고, 적용 가능한 기준에서 주의·과다와 Ca:P 비율 이탈이 없는 후보를 추천하는 FastAPI MVP입니다.

> 제품 수치는 기능 검증용 데모이며, 영양 기준선은 FEDIAF Nutritional Guidelines 2025를 적용합니다. 이 서비스는 실제 급여 판단이나 수의학적 처방을 대신하지 않습니다.

## 실행

```powershell
cd C:\Users\yeoh0\wooaeyoung1\backend
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
uvicorn app.main:app --reload
```

API 문서: `http://localhost:8000/docs`

카카오 챗봇 스킬 URL: `https://<배포-호스트>/api/v1/kakao/skill`

지원 명령어: `/도움말`, `/상태`, `/이슈`, `/배포`, `/백엔드`, `/프론트`, `/내아이디`
슬래시 없이 `도움말`, `상태`처럼 입력해도 같은 명령으로 처리합니다.

GitHub 쓰기 명령은 Render 환경변수 `GITHUB_TOKEN`과
`KAKAO_ALLOWED_USER_IDS`(쉼표로 구분한 카카오 사용자 ID)가 모두 설정된 사용자만 실행할 수 있습니다.

GitHub push/issue 카카오 알림은 두 저장소의 Actions에 `KAKAO_REST_API_KEY`,
`KAKAO_CLIENT_SECRET`, `KAKAO_REFRESH_TOKEN`, `KAKAO_SECRET_UPDATER_TOKEN` 비밀값이 필요합니다.

## Render 배포

저장소 루트의 `render.yaml`을 Blueprint로 등록하면 FastAPI 서비스가 생성됩니다.
배포 후 `/health`가 `200 OK`인지 확인하고 카카오 챗봇 관리자센터의 스킬 URL에
`/api/v1/kakao/skill` 엔드포인트를 등록합니다.

## 테스트

```powershell
pytest -q
```

## 구현 범위

- 프로필 검증과 생애주기 판정
- 제품 검색과 데모 카탈로그
- JSON 제품 파일 시작 시 메모리 적재, 직접 구현한 ID·바코드 해시 조회, 제품명·브랜드 트라이 자동완성
- 고정 성분 순서 배열을 이용한 사료 실제값/추정값, 영양제 및 수동 제품 합산
- 기준 열량, 하한·주의선·상한, 상태 판정
- 개의 칼슘:인 비율
- 직접 구현한 최대 힙과 선택 후 재평가를 이용한 최대 3개 추천

영양 기준 출처: https://europeanpetfood.org/wp-content/uploads/2025/09/FEDIAF-Nutritional-Guidelines_2025-ONLINE.pdf

수치·단위·각주 대조 기록: [docs/standards-validation.md](docs/standards-validation.md)

