import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { migrateBrandStorage } from "./lib/brand-storage";
import "./styles/base.css";
import "./styles/mobile.css";

try { migrateBrandStorage(window.localStorage); } catch { /* Storage may be unavailable. */ }

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
