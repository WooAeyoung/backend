import { useState } from "react";
import { useSession } from "../state/session";
import { useAuth } from "../state/auth";
import { Icon, type IconName } from "./Icon";
import { DietBoard } from "./DietBoard";
import { HandoffImport } from "./HandoffImport";
import { ProfilePanel } from "./ProfilePanel";
import { ProductAddPanel } from "./ProductAddPanel";
import { Notice } from "./ui";

type View = "profile" | "diet" | "analysis" | "products";
const NAV: { id: View; label: string; title: string; icon: IconName }[] = [
  { id: "profile", label: "프로필", title: "반려동물 프로필", icon: "paw" },
  { id: "diet", label: "급여조합", title: "급여조합", icon: "bowl" },
  { id: "analysis", label: "영양소 분석", title: "영양소 분석", icon: "list" },
  { id: "products", label: "제품 추가", title: "제품 추가", icon: "plus" },
];
const wooaeyoung = (window as unknown as { wooaeyoung?: {
  isElectron?: boolean; platform?: string;
  win?: { minimize(): void; toggleMaximize(): void; close(): void };
} }).wooaeyoung;

export function AppShell() {
  const [view, setView] = useState<View>("diet");
  const { state } = useSession();
  const { logout } = useAuth();
  const p = state.profile;
  const current = NAV.find((item) => item.id === view)!;
  return (
    <div className="appwin">
      <div className={"titlebar" + (wooaeyoung?.platform === "darwin" ? " mac" : "")}>
        <span className="tb-brand"><span className="tb-logo" aria-hidden><Icon name="paw" size={12} /></span>우애영</span>
        <span className="tb-spacer" />
        {wooaeyoung?.isElectron && wooaeyoung.platform !== "darwin" && <div className="win-btns no-print">
          <button aria-label="최소화" onClick={() => wooaeyoung.win?.minimize()}>─</button>
          <button aria-label="최대화" onClick={() => wooaeyoung.win?.toggleMaximize()}>▢</button>
          <button className="close" aria-label="닫기" onClick={() => wooaeyoung.win?.close()}>✕</button>
        </div>}
      </div>
      <div className="appbody">
        <section className="workspace">
          <div className="toolbar"><h2>{current.title}</h2></div>
          <main className="canvas" key={view}>
            <div className="mobile-pet">
              <Icon name="paw" size={28} />
              <div><strong>{p.name || "우리 아이"}의 건강한 하루</strong><p>{p.age}세 · {(+p.weight).toFixed(1)}kg{p.breed ? ` · ${p.breed}` : ""}</p></div>
            </div>
            {state.loadError && <Notice tone="warn">제품 정보를 불러오지 못했습니다: {state.loadError}</Notice>}
            {view === "profile" && <div className="stack"><HandoffImport /><ProfilePanel /><button className="btn btn-primary" onClick={() => setView("diet")}>급여조합 설정하기</button><button className="btn btn-ghost" onClick={() => logout()}>로그아웃</button></div>}
            {view === "diet" && <div className="stack"><DietBoard mode="diet" onAdd={() => setView("products")} /><button className="btn btn-primary" onClick={() => setView("analysis")}>영양소 분석 보기</button></div>}
            {view === "analysis" && <DietBoard mode="analysis" onAdd={() => setView("products")} />}
            {view === "products" && <ProductAddPanel />}
          </main>
        </section>
      </div>
      <nav className="mobile-tabs no-print" aria-label="주요 메뉴">
        {NAV.map((item) => <button key={item.id} aria-current={view === item.id ? "page" : undefined} onClick={() => setView(item.id)}><Icon name={item.icon} size={23} /><span>{item.label}</span></button>)}
      </nav>
    </div>
  );
}

