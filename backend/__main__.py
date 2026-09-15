"""`python -m backend` — 분석 API 서버를 실행한다.

설치형 데스크톱 셸(Electron)이 이 진입점을 자식 프로세스로 띄우고,
`/health` 응답을 확인한 뒤 창을 연다. PyInstaller 로 동결할 때도 이 파일을
엔트리로 사용한다(`pyinstaller -m backend`).
"""

from __future__ import annotations

import argparse
import os

import uvicorn


def main() -> None:
    parser = argparse.ArgumentParser(prog="wooaeyoung-backend")
    parser.add_argument("--host", default=os.environ.get("WOOAEYOUNG_HOST", "127.0.0.1"))
    parser.add_argument(
        "--port", type=int, default=int(os.environ.get("PORT") or os.environ.get("WOOAEYOUNG_PORT", "8756"))
    )
    args = parser.parse_args()
    uvicorn.run(
        "backend.api:build_app",
        host=args.host,
        port=args.port,
        factory=True,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
