import os
from typing import Any

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


@app.post("/api/v1/kakao/skill")
async def kakao_skill(payload: dict[str, Any]):
    """Handle Kakao i Open Builder skill requests."""
    utterance = str(payload.get("userRequest", {}).get("utterance", "")).strip()
    normalized = " ".join(utterance.lower().lstrip("/").split())

    command_messages = {
        "도움말": (
            "사용 가능한 명령어\n"
            "• /상태 - 연결 상태 확인\n"
            "• /이슈 - GitHub 이슈 명령 안내\n"
            "• /배포 - GitHub Actions 실행 안내\n"
            "• /프론트 - 프론트엔드 주소 보기"
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
    }
    aliases = {"help": "도움말", "헬프": "도움말", "메뉴": "도움말"}
    command = aliases.get(normalized, normalized)
    if command.startswith("이슈 "):
        command = "이슈"
    elif command.startswith("배포 "):
        command = "배포"

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

