# 우애영

FastAPI backend for 우애영. It provides pet profiles, product data,
nutrition analysis, recommendations, pricing, OCR, and optional vision OCR.

## Quick start

Requirements: Python 3.11 or newer.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m backend --port 8756
```

The API is available at `http://127.0.0.1:8756`. Interactive API docs are at
`http://127.0.0.1:8756/docs` and the health check is `/health`.

## Configuration

| Variable | Purpose | Default |
| --- | --- | --- |
| `WOOAEYOUNG_HOST` | Bind address | `127.0.0.1` |
| `WOOAEYOUNG_PORT` or `PORT` | Bind port | `8756` |
| `WOOAEYOUNG_DB` | SQLite database path | `backend/db/wooaeyoung.db` |
| `WOOAEYOUNG_PUBLIC_SERVER` | Enable restricted CORS behavior | `0` |
| `WOOAEYOUNG_CORS_ORIGINS` | Comma-separated allowed origins | empty |
| `DATABASE_URL` | Optional PostgreSQL connection | unset |
| `ANTHROPIC_API_KEY` | Optional vision OCR credential | unset |
| `VISION_MODEL` | Vision OCR model | `claude-opus-5` |

The SQLite database is created automatically. The demo product and nutrient
data are read from `data/processed/`.

## Git workflow

This repository uses a small, conventional Git workflow:

```bash
# Clone the repository
git clone https://github.com/WooAeyoung/backend.git
cd backend

# Create a branch for a change
git switch -c feat/my-change

# Review and save the change
git status
git add .
git commit -m "feat: describe the change"

# Publish the branch
git push -u origin feat/my-change
```

Use pull requests to merge changes into `main`. Do not commit `.env` files,
local databases, virtual environments, or Python cache files.

## API checks

```bash
python -m pytest
```

For a quick smoke check after starting the server:

```bash
curl http://127.0.0.1:8756/health
```

## Layout

```text
backend/                 FastAPI package
data/processed/          Demo products, prices, and nutrient standards
requirements.txt         Runtime dependencies
```
