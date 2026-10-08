import os
import json
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from .catalog import CATALOG, NUTRIENTS, STANDARD_VERSION
from .engine import AnalysisError, analyze, recommend
from .models import AnalysisRequest, RecommendationRequest

app = FastAPI(title="우애영 API", version="1.0.0")
cors_origins = [
    origin.strip()
    for origin in os.getenv(
        "CORS_ORIGINS",
        "http://localhost:5173,https://wooaeyoung.github.io",
    ).split(",")
    if origin.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/health")
def health(): return {"status":"ok","standardVersion":STANDARD_VERSION}


GITHUB_REPOSITORIES = {
    "backend": "WooAeyoung/backend",
    "frontend": "WooAeyoung/Frontend",
}


def github_request(method: str, path: str, data: dict[str, Any]) -> dict[str, Any] | None:
    token = os.getenv("GITHUB_TOKEN", "").strip()
    if not token:
        raise RuntimeError("GitHub 토큰이 설정되지 않았습니다.")
    request = Request(
        f"https://api.github.com{path}",
        data=json.dumps(data).encode("utf-8"),
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "wooaeyoung-kakao-bot",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urlopen(request, timeout=10) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API 오류({error.code}): {detail[:200]}") from error
    except URLError as error:
        raise RuntimeError("GitHub API에 연결하지 못했습니다.") from error


def kakao_user_is_allowed(user_id: str) -> bool:
    allowed = {
        value.strip()
        for value in os.getenv("KAKAO_ALLOWED_USER_IDS", "").split(",")
        if value.strip()
    }
    return bool(user_id and user_id in allowed)


@app.post("/api/v1/kakao/skill")
def kakao_skill(payload: dict[str, Any]):
    """Handle Kakao i Open Builder skill requests."""
    utterance = str(payload.get("userRequest", {}).get("utterance", "")).strip()
    normalized = " ".join(utterance.lower().lstrip("/").split())
    user_id = str(payload.get("userRequest", {}).get("user", {}).get("id", "")).strip()

    command_messages = {
        "도움말": (
            "사용 가능한 명령어\n"
            "• /상태 - 연결 상태 확인\n"
            "• /이슈 - GitHub 이슈 명령 안내\n"
            "• /배포 - GitHub Actions 실행 안내\n"
            "• /프론트 - 프론트엔드 주소 보기\n"
            "• /내아이디 - 명령 권한 등록용 ID 확인"
        ),
        "상태": "우애영 봇과 Backend가 정상적으로 연결되어 있습니다.",
        "이슈": (
            "이슈 명령 형식: /이슈 backend 제목 또는 /이슈 frontend 제목\n"
            "대상: WooAeyoung/backend, WooAeyoung/Frontend\n"
            "GitHub 토큰을 연결하면 실제 이슈 생성까지 실행할 수 있습니다."
        ),
        "배포": (
            "배포 명령 형식: /배포 backend 또는 /배포 frontend\n"
            "frontend는 WooAeyoung/Frontend의 GitHub Pages 배포를 뜻합니다.\n"
            "GitHub 토큰을 연결하면 Actions 실행까지 지원할 수 있습니다."
        ),
        "프론트": (
            "우애영 프론트엔드\n"
            "서비스: https://wooaeyoung.github.io/Frontend/\n"
            "저장소: https://github.com/WooAeyoung/Frontend"
        ),
        "내아이디": f"내 카카오 사용자 ID: {user_id or '확인할 수 없음'}",
    }
    aliases = {"help": "도움말", "헬프": "도움말", "메뉴": "도움말"}
    command = aliases.get(normalized, normalized)
    if command.startswith("이슈 "):
        parts = normalized.split(" ", 2)
        if len(parts) < 3 or parts[1] not in GITHUB_REPOSITORIES:
            message = command_messages["이슈"]
        elif not kakao_user_is_allowed(user_id):
            message = "실행 권한이 없습니다. /내아이디를 보내고 표시된 ID를 관리자에게 등록해 주세요."
        else:
            repository = GITHUB_REPOSITORIES[parts[1]]
            try:
                result = github_request(
                    "POST",
                    f"/repos/{repository}/issues",
                    {"title": parts[2], "body": "카카오톡 우애영 봇에서 생성된 이슈입니다."},
                )
                message = f"이슈를 만들었습니다: {result['html_url']}"
            except RuntimeError as error:
                message = f"이슈 생성에 실패했습니다. {error}"
    elif command.startswith("배포 "):
        parts = normalized.split()
        if len(parts) != 2 or parts[1] not in GITHUB_REPOSITORIES:
            message = command_messages["배포"]
        elif parts[1] == "backend":
            message = "Backend는 Render 서비스라 GitHub Actions 배포 대상이 아닙니다. 현재는 /배포 frontend를 지원합니다."
        elif not kakao_user_is_allowed(user_id):
            message = "실행 권한이 없습니다. /내아이디를 보내고 표시된 ID를 관리자에게 등록해 주세요."
        else:
            try:
                github_request(
                    "POST",
                    "/repos/WooAeyoung/Frontend/actions/workflows/deploy-pages.yml/dispatches",
                    {"ref": "main"},
                )
                message = "Frontend GitHub Pages 배포를 시작했습니다."
            except RuntimeError as error:
                message = f"배포 실행에 실패했습니다. {error}"
    else:
        message = command_messages.get(
            command,
            "명령어를 알아듣지 못했어요. 아래 버튼을 누르거나 /도움말을 입력해 주세요.",
        )

    return {
        "version": "2.0",
        "template": {
            "outputs": [
                {"simpleText": {"text": message}},
            ],
            "quickReplies": [
                {"action": "message", "label": "도움말", "messageText": "/도움말"},
                {"action": "message", "label": "연결 상태", "messageText": "/상태"},
                {"action": "message", "label": "이슈 만들기", "messageText": "/이슈"},
                {"action": "message", "label": "배포하기", "messageText": "/배포"},
                {"action": "message", "label": "프론트 열기", "messageText": "/프론트"},
                {"action": "message", "label": "내 ID", "messageText": "/내아이디"},
            ],
        },
    }

@app.get("/api/v1/products")
def products(query: str = Query(default="", max_length=100), type: str | None = None, limit: int = Query(default=10, ge=1, le=20)):
    items = CATALOG.search(query=query, product_type=type, limit=limit)
    public_fields = ("id", "barcode", "name", "brand", "type", "servingUnit", "dataQuality")
    return {"items":[{key:product[key] for key in public_fields if key in product} for product in items]}

@app.get("/api/v1/nutrients")
def nutrients(): return {"items":[{"id":key,**value} for key,value in NUTRIENTS.items()]}

@app.post("/api/v1/analyses")
def analyses(request: AnalysisRequest):
    try: return analyze(request)
    except AnalysisError as error: raise HTTPException(status_code=422, detail={"code":"CALCULATION_INPUT_ERROR","message":str(error)}) from error

@app.post("/api/v1/recommendations")
def recommendations(request: RecommendationRequest):
    try: return recommend(request)
    except AnalysisError as error: raise HTTPException(status_code=422, detail={"code":"CALCULATION_INPUT_ERROR","message":str(error)}) from error

