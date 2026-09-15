FROM node:22-bookworm-slim AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM python:3.13-slim-bookworm AS server
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    WOOAEYOUNG_HOST=0.0.0.0 \
    WOOAEYOUNG_PORT=8756 \
    WOOAEYOUNG_PUBLIC_SERVER=1 \
    WOOAEYOUNG_DB=/app/storage/wooaeyoung.db
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-kor \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 wooaeyoung \
    && useradd --uid 10001 --gid wooaeyoung --no-create-home wooaeyoung \
    && mkdir /app/storage \
    && chown wooaeyoung:wooaeyoung /app/storage
COPY requirements-server.txt ./
RUN pip install --no-cache-dir -r requirements-server.txt
COPY backend/ ./backend/
COPY data/processed/products.csv data/processed/nutrient_standards.csv data/processed/prices.csv ./data/processed/
COPY --from=frontend /build/dist/ ./frontend/dist/
USER wooaeyoung
EXPOSE 8756
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+str(os.environ.get('PORT') or os.environ.get('WOOAEYOUNG_PORT','8756'))+'/health',timeout=4)"
CMD ["python", "-m", "backend"]
