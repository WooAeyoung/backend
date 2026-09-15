import { useState } from "react";
import { useAuth } from "../state/auth";
import { getApiBase, setApiBase } from "../api/client";
import { Button, Field, Notice } from "./ui";

export function AuthGate() {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [apiBase, setApiBaseInput] = useState(getApiBase());
  const [showApi, setShowApi] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    setApiBase(apiBase.trim());
    try {
      if (mode === "login") await login(email.trim(), password);
      else await register(email.trim(), password, name.trim() || undefined);
    } catch (e2) {
      setErr(String((e2 as Error).message || e2));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-wrap">
      <form className="auth-card stack" style={{ gap: "var(--sp-4)" }} onSubmit={submit}>
        <div className="row" style={{ gap: "var(--sp-2)" }}>
          <span
            aria-hidden
            style={{
              width: 30,
              height: 30,
              borderRadius: 9,
              background: "var(--brand)",
              color: "#fff",
              display: "grid",
              placeItems: "center",
              fontSize: 16,
            }}
          >
            🐾
          </span>
          <strong style={{ fontSize: 16, letterSpacing: "-0.02em" }}>우애영</strong>
        </div>

        <div className="chip-toggle" style={{ alignSelf: "flex-start" }}>
          <button type="button" aria-pressed={mode === "login"} onClick={() => setMode("login")}>
            로그인
          </button>
          <button
            type="button"
            aria-pressed={mode === "register"}
            onClick={() => setMode("register")}
          >
            회원가입
          </button>
        </div>

        {mode === "register" && (
          <Field label="표시 이름 (선택)">
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
        )}
        <Field label="이메일">
          <input
            className="input"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>
        <Field label="비밀번호 (8자 이상)">
          <input
            className="input"
            type="password"
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>

        {err && <Notice tone="warn">{err}</Notice>}

        <Button variant="primary" type="submit" disabled={busy}>
          {busy ? "처리 중…" : mode === "login" ? "로그인" : "가입하고 시작"}
        </Button>

        <button
          type="button"
          className="btn btn-ghost btn-sm"
          style={{ alignSelf: "flex-start" }}
          onClick={() => setShowApi((v) => !v)}
        >
          {showApi ? "▾" : "▸"} 서버 주소 설정
        </button>
        {showApi && (
          <Field label="API 서버 URL (비우면 이 앱 내장 서버)">
            <input
              className="input"
              placeholder="예: https://petbalance.mycompany.com"
              value={apiBase}
              onChange={(e) => setApiBaseInput(e.target.value)}
            />
          </Field>
        )}
        <p className="section-sub">
          계정 데이터는 위 서버에 저장됩니다. 같은 서버 주소를 쓰면 여러 기기에서 같은
          계정으로 접속합니다.
        </p>
      </form>
    </div>
  );
}
