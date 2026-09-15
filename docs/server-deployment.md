# 우애영 웹 서버 배포

React 화면과 FastAPI를 하나의 서버에서 제공합니다. Claude Artifact 공유 설정은 필요하지 않습니다. 공개 사이트에서 분석/카탈로그를 이용할 수 있고, 저장·복원은 로그인한 계정별로 제한됩니다. 결제는 기존과 동일하게 실제 청구가 없는 데모입니다.

## Windows에서 임시 공유

`scripts/start-share.ps1`은 공개 서버 모드로 앱을 실행하고 Cloudflare Quick Tunnel HTTPS 주소를 만듭니다. 공식 cloudflared 실행 파일은 `.runtime/tools/cloudflared.exe`에 있어야 합니다. 웹 빌드 후 PowerShell에서 `& .\scripts\start-share.ps1`을 실행하세요. 종료는 `& .\scripts\stop-share.ps1`입니다.

- 링크를 가진 누구나 앱에 접속할 수 있습니다. 로그인 없이 저장 데이터에는 접근할 수 없습니다.
- PC가 켜져 있고 서버와 터널 프로세스가 실행 중이어야 합니다. 재시작하면 공유 주소가 바뀝니다. 이 방식은 상시 호스팅 배포가 아닙니다.
- 데이터는 `.runtime/share/wooaeyoung.db`에 따로 저장하며, 기존 데스크톱 DB를 공개하거나 복사하지 않습니다. 공유 중지 후에도 이 DB는 유지됩니다.
- 임시 공유 프로세스에는 유료 AI API 키를 전달하지 않습니다. 기본 OCR/텍스트 입력을 사용합니다.
- `.runtime/share/state.json`에 현재 주소와 프로세스 정보가 기록됩니다. 서버 상태 확인은 공유 주소의 `/health`입니다.

공식 설명: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/

## 기존 Docker 서버

1. Dockerfile, compose.yaml, requirements-server.txt, backend/, frontend/, data/processed/를 서버에 복사합니다. 기존 로컬 DB와 .env를 함께 업로드하지 마세요.
2. 프로젝트 디렉터리에서 `docker compose up -d --build`를 실행합니다.
3. `curl --fail http://127.0.0.1:8756/health`에서 `{"status":"ok"}`를 확인합니다.
4. 서버의 기존 HTTPS 리버스 프록시가 이 앱의 `127.0.0.1:8756`으로 전달하도록 연결합니다. 웹 화면과 `/api/*` 요청 모두 같은 대상에 전달해야 합니다. 프록시가 별도 컨테이너이면 공통 Docker 네트워크에서 `app:8756`으로 연결합니다.
5. 외부 HTTPS 주소에서 회원가입, 분석, 저장, 로그아웃/로그인, 복원을 확인합니다. 이 주소가 공유 링크입니다.

SQLite는 `wooaeyoung-data` 볼륨에 보관되어 컨테이너 재생성 후에도 유지됩니다. `docker compose down -v`는 저장 데이터까지 삭제하므로 사용하지 마세요. 여러 서버 복제본이 같은 SQLite 파일에 접근하는 구성은 지원하지 않습니다. 백업은 서버를 잠시 정지한 상태에서 볼륨을 백업하거나 SQLite 온라인 백업 API를 사용합니다.

## Docker를 지원하는 관리형 호스팅

- 저장소 루트의 Dockerfile로 빌드합니다.
- `/app/storage`에 영구 디스크를 연결하고 UID/GID 10001이 쓸 수 있도록 설정합니다.
- 상태 검사 경로는 `/health`입니다. 플랫폼의 `PORT` 환경변수가 있으면 서버가 자동으로 사용합니다. 없으면 8756입니다.
- WOOAEYOUNG_PUBLIC_SERVER=1, WOOAEYOUNG_HOST=0.0.0.0, WOOAEYOUNG_DB=/app/storage/wooaeyoung.db를 유지합니다.
- 호스팅의 HTTPS 도메인을 연결합니다. 영구 디스크가 없는 인스턴스에는 계정·식단 데이터를 저장하지 마세요.

## 환경변수

- ANTHROPIC_API_KEY: 선택 사항. 호스팅의 비밀값 설정에 입력합니다. 미설정 시 기본 OCR/텍스트 입력을 사용할 수 있습니다.
- VISION_MODEL: 선택 사항. 사용 가능한 모델 이름을 지정합니다.
- WOOAEYOUNG_CORS_ORIGINS: 동일 주소에서 화면과 API를 제공하면 비워 둡니다. 별도 웹 주소가 필요한 경우 정확한 HTTPS origin을 쉼표로 구분합니다.

공개 서버 모드에서는 URL로 DB/CSV 경로를 바꾸는 기능, 로컬용 제품 편집 및 펫 상세 API를 차단합니다. 저장할 때 제품 데이터를 세션별로 분리하여 다른 사용자의 제품 수정이 기존 저장 내용에 반영되지 않도록 합니다. 기존 사용자 미지정 로컬 기록은 공개 서버에서 노출하지 않습니다.

## 검증

`npm --prefix frontend run build`

`python -m pytest tests`

`docker compose config --quiet`

Docker와 실제 호스팅이 준비된 환경에서 이미지 빌드, 재시작 후 복원, 외부 HTTPS 접속도 확인해야 배포가 완료됩니다.
