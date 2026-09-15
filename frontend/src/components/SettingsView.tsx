import { useState } from "react";
import { getApiBase, setApiBase } from "../api/client";
import { useAuth } from "../state/auth";
import { useSession } from "../state/session";
import { Button, Field, Notice, Section } from "./ui";

type Theme = "light" | "dark" | "system";

function currentTheme(): Theme {
  try {
    return (localStorage.getItem("wooaeyoung-theme") as Theme) || "system";
  } catch {
    return "system";
  }
}
function applyTheme(t: Theme) {
  const root = document.documentElement;
  if (t === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", t);
  try {
    localStorage.setItem("wooaeyoung-theme", t);
  } catch {
    /* ignore */
  }
}

export function SettingsView() {
  const { user, logout } = useAuth();
  const { state, dispatch } = useSession();
  const p = state.profile;
  const setP = (patch: Partial<typeof p>) => dispatch({ type: "profile", patch });
  const [theme, setTheme] = useState<Theme>(currentTheme());
  const [apiBase, setApiBaseInput] = useState(getApiBase());
  const [saved, setSaved] = useState(false);

  return (
    <div className="stack">
      <Section title="우리 아이 프로필" sub="이름·나이·체중은 판정에 참고됩니다">
        <div className="grid-2" style={{ gap: "var(--sp-3)" }}>
          <Field label="이름">
            <input className="input" value={p.name} onChange={(e) => setP({ name: e.target.value })} />
          </Field>
          <Field label="품종">
            <input
              className="input"
              placeholder="예: 말티즈"
              value={p.breed}
              onChange={(e) => setP({ breed: e.target.value })}
            />
          </Field>
          <Field label="나이 (세)">
            <input
              className="input"
              type="number"
              min={1}
              max={25}
              value={p.age}
              onChange={(e) => setP({ age: Number(e.target.value) })}
            />
          </Field>
          <Field label="체중 (kg)">
            <input
              className="input"
              type="number"
              min={1}
              max={80}
              step={0.1}
              value={p.weight}
              onChange={(e) => setP({ weight: Number(e.target.value) })}
            />
          </Field>
        </div>
      </Section>

      <Section title="계정">
        <div className="row spread">
          <div>
            <div style={{ fontWeight: 650 }}>{user?.display_name || user?.email}</div>
            <div className="section-sub">{user?.email}</div>
          </div>
          <Button onClick={() => logout()}>로그아웃</Button>
        </div>
      </Section>

      <Section title="테마">
        <div className="chip-toggle" style={{ alignSelf: "flex-start" }}>
          {(["light", "system", "dark"] as Theme[]).map((t) => (
            <button
              key={t}
              aria-pressed={theme === t}
              onClick={() => {
                setTheme(t);
                applyTheme(t);
              }}
            >
              {t === "light" ? "밝게" : t === "dark" ? "어둡게" : "시스템"}
            </button>
          ))}
        </div>
      </Section>

      <Section title="서버 주소" sub="비우면 이 앱에 내장된 로컬 분석 서버를 사용합니다">
        <div className="stack" style={{ gap: "var(--sp-3)" }}>
          <Field label="API 서버 URL">
            <input
              className="input"
              placeholder="예: https://wooaeyoung.example.com"
              value={apiBase}
              onChange={(e) => setApiBaseInput(e.target.value)}
            />
          </Field>
          <div className="row">
            <Button
              variant="primary"
              onClick={() => {
                setApiBase(apiBase.trim());
                setSaved(true);
                setTimeout(() => window.location.reload(), 500);
              }}
            >
              저장하고 재시작
            </Button>
            {saved && <span className="badge badge-ok">저장됨 · 새로고침 중…</span>}
          </div>
          <Notice tone="info">
            서버 주소를 바꾸면 로그인 세션이 초기화됩니다. 같은 주소를 쓰는 기기끼리
            계정·데이터가 공유됩니다.
          </Notice>
        </div>
      </Section>
    </div>
  );
}
