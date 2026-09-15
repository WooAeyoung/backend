# -*- mode: python ; coding: utf-8 -*-
"""우애영 분석 백엔드를 단일 폴더 실행파일로 동결한다.

  npm run backend:freeze      # → dist/wooaeyoung-backend/wooaeyoung-backend(.exe)

electron-builder 가 이 폴더를 앱 리소스의 resources/backend/ 로 복사하고,
desktop/main.js 가 자식 프로세스로 실행한다.
"""

from pathlib import Path

from PyInstaller.utils.hooks import collect_submodules

ROOT = Path(SPECPATH).parent

datas = [
    (str(ROOT / "data" / "processed"), "data/processed"),
    (str(ROOT / "frontend" / "dist"), "frontend/dist"),
]

hiddenimports = (
    collect_submodules("uvicorn")
    + collect_submodules("sqlalchemy.dialects.sqlite")
    + ["anyio", "click", "h11"]
)

a = Analysis(
    [str(ROOT / "backend" / "__main__.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["streamlit", "tkinter", "matplotlib", "pytest"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="wooaeyoung-backend",
    console=True,
    disable_windowed_traceback=False,
    icon=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="wooaeyoung-backend",
)
