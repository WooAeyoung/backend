"use strict";

// 현재 UI 는 상대 경로로 같은 오리진의 FastAPI 를 호출하므로 별도 브리지가 필요 없다.
// 후속 기능(파일 저장 대화상자 등)을 위해 최소 표면만 노출한다.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("wooaeyoung", {
  isElectron: true,
  platform: process.platform,
  version: process.env.npm_package_version || "0.2.0",
  win: {
    minimize: () => ipcRenderer.invoke("win:minimize"),
    toggleMaximize: () => ipcRenderer.invoke("win:toggle-maximize"),
    close: () => ipcRenderer.invoke("win:close"),
  },
});
