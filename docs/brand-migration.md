# 우애영 이름 변경과 데이터 이전

서비스 표시 이름은 **우애영**, 코드·파일·패키지에는 `wooaeyoung`을 사용합니다.

## 현재 연결 설정

- Python DB: `backend/db/wooaeyoung.db`, 환경변수 `WOOAEYOUNG_DB`.
- Spring DB: `db/wooaeyoung-spring.db`, 환경변수 `WOOAEYOUNG_DB_SPRING`.
- Spring 설정 키: `wooaeyoung.db-path`, Java 패키지: `ai.wooaeyoung`.
- Android 앱 ID: `ai.wooaeyoung.app`, Electron 앱 ID: `ai.wooaeyoung.desktop`.
- Docker 저장 볼륨: `wooaeyoung-data`, DB 파일: `/app/storage/wooaeyoung.db`.
- 기존 `PB_` 환경변수는 동일한 접미사의 `WOOAEYOUNG_`로 변경합니다.
- PostgreSQL 연결은 표준 환경변수 `DATABASE_URL`을 사용합니다. 실제 서버의 DB 이름을 변경할 때는 해당 서버에서 DB를 이전한 뒤 발급한 연결 문자열을 사용해야 합니다. 주소나 비밀번호를 문자열 치환하지 마세요.

## SQLite 데이터 이전

앱과 서버를 종료한 뒤 실행하세요. SQLite 온라인 백업으로 데이터와 스키마를 복사하고 무결성을 검사합니다. 원본은 되돌리기용으로 보존하며, 이미 있는 대상 DB는 덮어쓰지 않습니다.

```powershell
python scripts/migrate-brand-db.py
```

저장소 내 Python·Spring·공유 서버 DB를 새 이름으로 복사합니다. 사용자 지정 경로는 다음과 같이 이전합니다.

```powershell
python scripts/migrate-brand-db.py --source "기존.db" --destination "wooaeyoung.db"
```

기존 설치형 앱 데이터는 앱을 종료하고 기존 사용자 데이터 폴더의 DB를 `%APPDATA%/우애영/wooaeyoung.db`로 복사하세요. Docker를 이미 사용했다면 기존 볼륨을 백업하고 새 `wooaeyoung-data` 볼륨에 DB를 이전한 후 새 서버를 시작하세요. 새 볼륨은 자동으로 기존 볼륨 데이터를 가져오지 않습니다.

## 웹·Android 서버 주소

실제로 배포된 HTTPS 주소를 Capacitor 실행 환경에 설정하세요. 서버 주소가 없으면 모바일 설정 단계에서 오류를 내어 연결되지 않는 앱이 만들어지는 것을 방지합니다. `.env.example`만 수정하면 PowerShell 환경변수로 자동 반영되지는 않습니다.

```powershell
$env:WOOAEYOUNG_SERVER_URL = 'https://실제-배포-주소'
cd frontend
npx cap sync android
```

Vercel 프로젝트명·도메인 변경은 Vercel 계정에서 별도로 적용해야 합니다. 로컬 소스 수정은 외부 배포나 DB 서비스 이름을 바꾸지 않습니다.

Android 앱 ID가 달라져 기존 앱과 별도로 설치됩니다. 기존 설치 앱의 내부 데이터는 자동으로 이동하지 않습니다.

## 브라우저 데이터·인수인계

같은 브라우저 origin에서는 기존 설정·토큰·장바구니·식단을 새 저장 키로 옮깁니다. 새 키의 데이터가 있으면 우선하며, 저장 실패 시 기존 값은 보존합니다. 도메인이 변경되면 다른 origin이므로 로그인과 서버 설정이 다시 필요합니다.

새 인수인계 코드는 `wooaeyoung-handoff`를 사용하며, 이전 서비스에서 발급한 코드도 읽을 수 있습니다.
