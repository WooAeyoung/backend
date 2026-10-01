# 우애영 Backend

사료와 영양제의 하루 성분량을 합산해 FEDIAF 2025 생애주기별 기준과 비교하고, 적용 가능한 기준에서 주의·과다와 Ca:P 비율 이탈이 없는 후보를 추천하는 FastAPI MVP입니다.

> 제품과 영양 기준 수치는 기능 검증용 데모입니다. 실제 급여 판단이나 수의학적 처방에 사용할 수 없습니다.

## 실행

```powershell
cd C:\Users\yeoh0\wooaeyoung1\backend
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
uvicorn app.main:app --reload
```

API 문서: `http://localhost:8000/docs`

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

