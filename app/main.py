from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from .catalog import NUTRIENTS, PRODUCTS, STANDARD_VERSION
from .engine import AnalysisError, analyze, recommend
from .models import AnalysisRequest, RecommendationRequest

app = FastAPI(title="우애영 API", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:5173"], allow_methods=["*"], allow_headers=["*"])

@app.get("/health")
def health(): return {"status":"ok","standardVersion":STANDARD_VERSION}

@app.get("/api/v1/products")
def products(query: str = Query(default="", max_length=100), type: str | None = None, limit: int = Query(default=10, ge=1, le=20)):
    needle = "".join(query.lower().split())
    items = [p for p in PRODUCTS if (not needle or needle in "".join((p["name"]+p["brand"]).lower().split())) and (not type or p["type"] == type)]
    return {"items":[{k:p[k] for k in ("id","name","brand","type","servingUnit","dataQuality")} for p in items[:limit]]}

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

