# 결정 로그

| 날짜 | 결정 | 이유 | 고려한 대안 | 영향 |
|---|---|---|---|---|
| 2026-09-02 | 라벨 OCR은 초안만 생성하고 사람이 확인 버튼을 눌러야 조합에 반영 | 안전 범위의 "OCR 자동 확정" 제외 준수, 오인식 값이 판정에 바로 들어가지 않게 | 추출 즉시 자동 추가 | `src/label_ocr.py` 추가, `app.py`에 확인 화면 |
| 2026-09-02 | 이미지 OCR 라이브러리(pytesseract, Pillow)는 선택 의존성, 미설치 시 텍스트 붙여넣기로 대체 | 데모 실행 환경을 streamlit+pandas로 유지 | requirements에 OCR 스택 고정 | 이미지 업로드 실패 시 안내 후 텍스트 입력 |
| 2026-09-02 | OCR로 추가한 제품의 `label_complete` 기본값은 False | 확인 전 초안을 정보 부족으로 보수적 판정 | 기본 True | 확인 화면 체크박스로 사람이 승격 |
| 2026-09-04 | UI를 Streamlit → React(Vite+TS) SPA로 전환, 설치형 데스크톱 앱으로 패키징 | 반복 시연·배포 대상이 "설치형 앱"으로 확정됨, Streamlit은 레이아웃·상태 제어 한계 | Streamlit 유지+CSS, Tauri(Rust 툴체인 없음) | `src/` → `backend/` 로 이동, `frontend/`·`desktop/` 신설, `legacy/app.py` 보존 |
| 2026-09-04 | 셸은 Electron + electron-builder, 백엔드는 PyInstaller 로 동결해 sidecar 로 실행 | Node 툴체인 이미 존재, 대상 PC에 Python 불필요, 진짜 Windows 설치파일(.exe) 산출 | pywebview 단일 exe(경량이나 Python 중심), Tauri | `desktop/main.js` 가 빈 포트 탐색→spawn→`/health` 대기→창 로드 |
| 2026-09-04 | React는 저장 없이 `POST /api/session/analyze` 로 매 조합 변경 시(250ms 디바운스) 재분석 | 화면 상태와 DB를 분리, 슬라이더 조작이 즉시 반영되면서도 계산 로직은 백엔드 단일 소스 | 프론트에서 재계산, 매번 펫 저장 | 기존 `analyze()` 재사용하는 세션 엔드포인트 추가 |
| 2026-09-04 | 차트 색은 dataviz 스킬 레퍼런스 팔레트(범주형 slot 1-4 + 상태 팔레트)를 CSS 토큰으로 고정, 상태는 색+아이콘+라벨 이중 인코딩 | CVD 안전, 라이트/다크 일관, 색 단독 의미 전달 금지 | 임의 색상, 차트 라이브러리 기본 테마 | `frontend/src/styles/tokens.css`, 차트는 경량 div/SVG 자작 |
| 2026-09-04 | 저장 DB 경로는 `WOOAEYOUNG_DB` 환경변수로 주입(패키지: 사용자 데이터 폴더) | 동결된 exe 내부는 읽기 전용, 쓰기 가능한 위치 필요 | exe 옆에 db 생성 | `backend/database.py` 가 `WOOAEYOUNG_DB` 우선, 읽기 자산은 `sys._MEIPASS` 기준 |
| 2026-09-04 | UI를 사이드바 앱 레이아웃으로 전면 개편(스크롤 대시보드 → 좌측 내비 + 화면 전환 + 상태바), 밀도 상향 | "웹 같다"는 피드백. 설치형 앱은 지속 내비게이션·상태 표시가 기본 | 스크롤 유지+시각 압축 | `AppShell`/`Sidebar`/`StatusBar`, 토큰 라운드·간격 축소, `container` 폭 제한 해제 |
| 2026-09-04 | 인증(F-028)은 별도 서비스 없이 기존 FastAPI에 내장, 표준 라이브러리만 사용 | 백엔드가 곧 계정 서버. 의존성 추가 없이 PBKDF2 + 세션 토큰 테이블로 충분 | JWT 라이브러리, 외부 IdP | `backend/auth.py`, `users`/`sessions` 테이블, `pets.user_id` 마이그레이션, `Depends(current_user)` |
| 2026-09-04 | 여러 기기 동기화는 "같은 API 서버 URL 공유"로 해결(앱 설정에서 지정) | 설치형은 각자 로컬 백엔드지만, 팀이 백엔드 하나 호스팅하면 계정·데이터 공유됨 | P2P 동기화, 클라우드 전용 | 프론트 `getApiBase()`/설정 화면, 클라이언트가 base URL + Bearer 토큰 주입 |
| 2026-09-04 | 외부 데이터 의존 기능(F-021 가격·F-023 제품·F-024 기준)은 교체 가능한 어댑터 + 데모 데이터로 구현하고 UI에 "데모" 명시 | 실제 소스 확보 전에도 전체 흐름·계산·화면을 완성해두기 위함. 어댑터만 바꾸면 실데이터 | 기능 보류, 하드코딩 | `pricing.PriceProvider`(CsvPriceProvider), `products.csv` 23종, `nutrient_standards.csv` AAFCO 환산(verified=false) |
| 2026-09-04 | 성분명 정규화(F-032)·다중 단위(F-011)는 신규 모듈로 분리하고 기존 `label_ocr` 는 폴백으로만 확장 | `label_ocr` 의 좁은 동작이 테스트로 고정돼 있어 회귀 위험. 넓은 로직은 분석 입력·전용 엔드포인트에 적용 | `label_ocr` 직접 확장 | `backend/nutrients.py`(24종 사전), `backend/units.py`(%, mg/kg, IU/kg, mg/정), `/api/units/convert` |
