"use strict";

const { app, BrowserWindow, ipcMain, session, shell, dialog } = require("electron");
const { spawn } = require("node:child_process");
const http = require("node:http");
const net = require("node:net");
const path = require("node:path");
const fs = require("node:fs");

const isDev = !app.isPackaged;
const REPO_ROOT = path.join(__dirname, "..");

let backendProc = null;
let backendPort = 0;

function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

/** dev: `python -m backend`  ·  packaged: 번들된 실행파일 */
function backendCommand(port) {
  const env = { ...process.env, PB_PORT: String(port), PB_HOST: "127.0.0.1" };
  if (isDev) {
    const py = process.env.PB_PYTHON || (process.platform === "win32" ? "python" : "python3");
    return {
      cmd: py,
      args: ["-m", "backend", "--port", String(port)],
      opts: { cwd: REPO_ROOT, env },
    };
  }
  const exe =
    process.platform === "win32" ? "petbalance-backend.exe" : "petbalance-backend";
  const bin = path.join(process.resourcesPath, "backend", exe);
  env.PB_DB = path.join(app.getPath("userData"), "petbalance.db");
  return { cmd: bin, args: ["--port", String(port)], opts: { env } };
}

function startBackend(port) {
  const { cmd, args, opts } = backendCommand(port);
  const proc = spawn(cmd, args, { ...opts, stdio: ["ignore", "pipe", "pipe"] });
  proc.stdout.on("data", (d) => process.stdout.write(`[backend] ${d}`));
  proc.stderr.on("data", (d) => process.stderr.write(`[backend] ${d}`));
  proc.on("exit", (code) => {
    if (code && code !== 0 && !app.isQuiting) {
      dialog.showErrorBox(
        "백엔드 종료",
        `분석 서버가 예기치 않게 종료되었습니다 (code ${code}).`,
      );
    }
  });
  return proc;
}

function waitForHealth(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get(
        { host: "127.0.0.1", port, path: "/health", timeout: 1500 },
        (res) => {
          res.resume();
          if (res.statusCode === 200) return resolve();
          retry();
        },
      );
      req.on("error", retry);
      req.on("timeout", () => {
        req.destroy();
        retry();
      });
    };
    const retry = () => {
      if (Date.now() > deadline) return reject(new Error("health check timeout"));
      setTimeout(tick, 400);
    };
    tick();
  });
}

async function createWindow() {
  const win = new BrowserWindow({
    width: 1240,
    height: 860,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: "#f4f5f3",
    title: "우애영",
    icon: resolveIcon(),
    frame: false,
    titleBarStyle: "hidden",
    trafficLightPosition: { x: 12, y: 11 },
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  ipcMain.handle("win:minimize", () => win.minimize());
  ipcMain.handle("win:toggle-maximize", () =>
    win.isMaximized() ? win.unmaximize() : win.maximize(),
  );
  ipcMain.handle("win:close", () => win.close());

  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });

  // 라벨 촬영용 카메라 권한 허용 (로컬 앱, 자체 UI 한정)
  session.defaultSession.setPermissionRequestHandler((_wc, permission, cb) => {
    cb(permission === "media" || permission === "camera");
  });

  if (isDev && process.env.PB_VITE === "1") {
    // 선택: Vite 개발 서버(HMR)로 UI 로드. API 는 프록시로 backendPort 전달.
    await win.loadURL("http://127.0.0.1:5173");
    win.webContents.openDevTools({ mode: "detach" });
  } else {
    await win.loadURL(`http://127.0.0.1:${backendPort}/`);
  }
}

function resolveIcon() {
  const p = path.join(__dirname, "icon.png");
  return fs.existsSync(p) ? p : undefined;
}

app.whenReady().then(async () => {
  try {
    backendPort = await findFreePort();
    backendProc = startBackend(backendPort);
    await waitForHealth(backendPort);
  } catch (err) {
    dialog.showErrorBox(
      "시작 실패",
      `분석 서버를 시작하지 못했습니다.\n\n${err.message}\n\n` +
        (isDev
          ? "개발 모드: `pip install -r requirements.txt` 후 다시 시도하세요."
          : ""),
    );
    app.quit();
    return;
  }
  await createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

function stopBackend() {
  if (backendProc && !backendProc.killed) {
    if (process.platform === "win32") {
      try {
        spawn("taskkill", ["/pid", String(backendProc.pid), "/f", "/t"]);
      } catch {
        backendProc.kill();
      }
    } else {
      backendProc.kill("SIGTERM");
    }
  }
}

app.on("before-quit", () => {
  app.isQuiting = true;
  stopBackend();
});
app.on("window-all-closed", () => {
  stopBackend();
  if (process.platform !== "darwin") app.quit();
});
process.on("exit", stopBackend);
