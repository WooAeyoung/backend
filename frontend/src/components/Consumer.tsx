import { useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import type { Product } from "../api/types";
import { useAuth } from "../state/auth";
import { useSession } from "../state/session";
import { catEmoji, useShop } from "../state/shop";
import { won, mg, statusMeta } from "../lib/format";

/* ------------------------------------------------------------------ */
/* 공통 조각                                                          */
/* ------------------------------------------------------------------ */
function Stars({ value, size }: { value: number; size?: number }) {
  const full = Math.round(value);
  return (
    <span className="stars" style={size ? { fontSize: size } : undefined} aria-label={`별점 ${value}`}>
      {[1, 2, 3, 4, 5].map((i) => (
        <span key={i} className={i <= full ? "" : "empty"}>
          ★
        </span>
      ))}
    </span>
  );
}

function priceOf(p: Product) {
  return p.monthly_price_krw || 0;
}

function ProductCard({
  p,
  onOpen,
  wide,
}: {
  p: Product;
  onOpen: (pid: string) => void;
  wide?: boolean;
}) {
  const { favorites, toggleFavorite, ratings } = useShop();
  const fav = favorites.has(p.product_id);
  const r = ratings[p.product_id];
  return (
    <div className={"pcard" + (wide ? " card-w" : "")} onClick={() => onOpen(p.product_id)}>
      <div className="pc-thumb">
        {catEmoji(p.category)}
        <button
          className={"pc-fav" + (fav ? " on" : "")}
          aria-label="즐겨찾기"
          onClick={(e) => {
            e.stopPropagation();
            toggleFavorite(p.product_id);
          }}
        >
          {fav ? "♥" : "♡"}
        </button>
      </div>
      <div className="pc-body">
        <span className="pc-brand">{p.brand || "브랜드"}</span>
        <span className="pc-name">{p.name}</span>
        <span className="pc-meta">
          {r && r.count > 0 ? (
            <span className="rate-line">
              <Stars value={r.avg} /> {r.avg.toFixed(1)}·{r.count}
            </span>
          ) : (
            <span className="rate-line">리뷰 없음</span>
          )}
        </span>
        <span className="pc-price">{won(priceOf(p))}</span>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 온보딩                                                             */
/* ------------------------------------------------------------------ */
const GOALS = ["체중 관리", "피부·모질", "관절 건강", "소화·장 건강", "노령 케어", "알러지"];

export function Onboarding({ onDone }: { onDone: () => void }) {
  const { dispatch } = useSession();
  const [step, setStep] = useState(0);
  const [name, setName] = useState("");
  const [weight, setWeight] = useState(8);
  const [age, setAge] = useState(3);
  const [breed, setBreed] = useState("");
  const [goals, setGoals] = useState<string[]>([]);

  function finish() {
    dispatch({ type: "profile", patch: { name: name.trim() || "우리 아이", weight, age, breed } });
    try {
      localStorage.setItem("wooaeyoung-goals", JSON.stringify(goals));
      localStorage.setItem("wooaeyoung-onboarded", "1");
    } catch {
      /* ignore */
    }
    onDone();
  }

  return (
    <div className="appbg">
      <div className="phone">
        <div className="pbody" style={{ padding: "var(--sp-6) var(--sp-4)" }}>
          <div className="hero" style={{ borderRadius: "var(--radius-lg)", margin: "0 0 var(--sp-5)" }}>
            <div className="greet">STEP {step + 1} / 3</div>
            <div className="headline">
              {step === 0 && "반가워요! 아이 이름을 알려주세요"}
              {step === 1 && "몇 살, 몇 kg인가요?"}
              {step === 2 && "요즘 신경 쓰는 부분이 있나요?"}
            </div>
          </div>

          {step === 0 && (
            <div className="field-c">
              <label>반려견 이름</label>
              <input
                className="input-c"
                autoFocus
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="예: 몽이"
              />
            </div>
          )}
          {step === 1 && (
            <div style={{ display: "flex", flexDirection: "column", gap: "var(--sp-4)" }}>
              <div className="field-c">
                <label>나이 (세)</label>
                <input
                  className="input-c"
                  type="number"
                  value={age}
                  onChange={(e) => setAge(+e.target.value)}
                />
              </div>
              <div className="field-c">
                <label>체중 (kg)</label>
                <input
                  className="input-c"
                  type="number"
                  step="0.1"
                  value={weight}
                  onChange={(e) => setWeight(+e.target.value)}
                />
              </div>
              <div className="field-c">
                <label>품종 (선택)</label>
                <input
                  className="input-c"
                  value={breed}
                  onChange={(e) => setBreed(e.target.value)}
                  placeholder="예: 말티즈"
                />
              </div>
            </div>
          )}
          {step === 2 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {GOALS.map((g) => (
                <button
                  key={g}
                  className="chip"
                  aria-pressed={goals.includes(g)}
                  onClick={() =>
                    setGoals((gs) => (gs.includes(g) ? gs.filter((x) => x !== g) : [...gs, g]))
                  }
                >
                  {g}
                </button>
              ))}
            </div>
          )}

          <div style={{ marginTop: "var(--sp-6)", display: "flex", gap: 10 }}>
            {step > 0 && (
              <button className="bigbtn ghost" onClick={() => setStep((s) => s - 1)}>
                이전
              </button>
            )}
            {step < 2 ? (
              <button
                className="bigbtn"
                disabled={step === 0 && !name.trim()}
                onClick={() => setStep((s) => s + 1)}
              >
                다음
              </button>
            ) : (
              <button className="bigbtn" onClick={finish}>
                시작하기
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 셸                                                                 */
/* ------------------------------------------------------------------ */
type Tab = "diet" | "explore" | "fav" | "my";
type Overlay =
  | null
  | { kind: "product"; pid: string }
  | { kind: "cart" }
  | { kind: "checkout" }
  | { kind: "notif" }
  | { kind: "orders" };

export function ConsumerApp() {
  const [tab, setTab] = useState<Tab>("diet");
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [toast, setToast] = useState<string | null>(null);
  const { cartCount, analysis } = useShop();

  const notify = (msg: string) => {
    setToast(msg);
    window.setTimeout(() => setToast(null), 1600);
  };
  const openProduct = (pid: string) => setOverlay({ kind: "product", pid });

  const warnCount =
    analysis.data?.summary.filter((r) => ["중복 가능", "기준 초과 가능"].includes(r.status))
      .length ?? 0;

  const TABS: { id: Tab; label: string; icon: string }[] = [
    { id: "diet", label: "식단", icon: "🍽️" },
    { id: "explore", label: "제품", icon: "⌕" },
    { id: "fav", label: "찜", icon: "♥" },
    { id: "my", label: "마이", icon: "☺" },
  ];

  return (
    <div className="appbg">
      <div className="phone">
        <Appbar tab={tab} onBell={() => setOverlay({ kind: "notif" })} onCart={() => setOverlay({ kind: "cart" })} cartCount={cartCount} />

        <div className="pbody">
          {tab === "diet" && <DietHome openProduct={openProduct} goExplore={() => setTab("explore")} />}
          {tab === "explore" && <ExploreScreen openProduct={openProduct} />}
          {tab === "fav" && <FavScreen openProduct={openProduct} goExplore={() => setTab("explore")} />}
          {tab === "my" && <MyScreen onOrders={() => setOverlay({ kind: "orders" })} />}
        </div>

        <nav className="tabbar">
          {TABS.map((t) => (
            <button key={t.id} className="tabbtn" aria-current={tab === t.id} onClick={() => setTab(t.id)}>
              <span style={{ fontSize: 17, position: "relative" }}>
                {t.icon}
                {t.id === "diet" && warnCount > 0 && <span className="tb-badge">{warnCount}</span>}
              </span>
              {t.label}
            </button>
          ))}
        </nav>

        {overlay?.kind === "product" && (
          <ProductSheet pid={overlay.pid} onClose={() => setOverlay(null)} notify={notify} />
        )}
        {overlay?.kind === "cart" && (
          <CartSheet
            onClose={() => setOverlay(null)}
            onCheckout={() => setOverlay({ kind: "checkout" })}
            openProduct={openProduct}
          />
        )}
        {overlay?.kind === "checkout" && (
          <CheckoutSheet onClose={() => setOverlay(null)} notify={notify} />
        )}
        {overlay?.kind === "notif" && <NotifSheet onClose={() => setOverlay(null)} goDiet={() => { setOverlay(null); setTab("diet"); }} />}
        {overlay?.kind === "orders" && <OrdersSheet onClose={() => setOverlay(null)} />}

        {toast && <div className="toast">{toast}</div>}
      </div>
    </div>
  );
}

function Appbar({
  tab,
  onBell,
  onCart,
  cartCount,
}: {
  tab: Tab;
  onBell: () => void;
  onCart: () => void;
  cartCount: number;
}) {
  const title = { diet: "우리 아이 식단", explore: "제품 찾기", fav: "찜한 제품", my: "마이" }[tab];
  return (
    <header className="appbar">
      <h1>{title}</h1>
      <div className="ab-right">
        <button className="iconbtn" onClick={onBell} aria-label="알림">
          🔔<span className="dot" />
        </button>
        <button className="iconbtn" onClick={onCart} aria-label="장바구니" style={{ position: "relative" }}>
          🛒
          {cartCount > 0 && (
            <span
              style={{
                position: "absolute",
                top: 3,
                right: 2,
                background: "var(--critical)",
                color: "#fff",
                fontSize: 9,
                fontWeight: 700,
                minWidth: 14,
                height: 14,
                borderRadius: 999,
                display: "grid",
                placeItems: "center",
              }}
            >
              {cartCount}
            </span>
          )}
        </button>
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------ */
/* 식단 (홈) — 먹는 양 입력 · 영양소 충분/주의 · 지금 먹이는 성분     */
/* ------------------------------------------------------------------ */
const STATUS_WORD: Record<string, { w: string; cls: string }> = {
  "정보 충분": { w: "충분", cls: "ok" },
  "기준 초과 가능": { w: "주의·많음", cls: "crit" },
  "중복 가능": { w: "주의·중복", cls: "warn" },
  "참고 범위 미만": { w: "부족", cls: "info" },
  "정보 부족": { w: "정보 없음", cls: "neutral" },
};

function GramInput({ value, onChange }: { value: number; onChange: (g: number) => void }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
      <button className="g-step" onClick={() => onChange(value - 10)} aria-label="10g 감소">
        −10
      </button>
      <div style={{ position: "relative" }}>
        <input
          className="input-c g-field"
          type="number"
          inputMode="numeric"
          min={0}
          value={value}
          onChange={(e) => onChange(Math.max(0, Number(e.target.value)))}
        />
        <span className="g-unit">g/일</span>
      </div>
      <button className="g-step" onClick={() => onChange(value + 10)} aria-label="10g 증가">
        +10
      </button>
    </div>
  );
}

function DietHome({
  openProduct,
  goExplore,
}: {
  openProduct: (pid: string) => void;
  goExplore: () => void;
}) {
  const { state } = useSession();
  const { dietProducts, diet, setDietG, toggleDietActive, removeFromDiet, analysis } = useShop();
  const s = analysis.data?.summary ?? [];
  const activeCount = dietProducts.filter((p) => diet[p.product_id]?.active).length;
  const warns = s.filter((r) => ["중복 가능", "기준 초과 가능"].includes(r.status));
  const lacks = s.filter((r) => r.status === "참고 범위 미만");
  const fed = s.filter((r) => r.total_mg > 0);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "var(--sp-4)" }}>
      {/* 아이 카드 */}
      <div className="c-card" style={{ display: "flex", alignItems: "center", gap: 12, padding: "12px 14px" }}>
        <span
          style={{
            width: 44,
            height: 44,
            borderRadius: 14,
            background: "var(--brand-wash)",
            display: "grid",
            placeItems: "center",
            fontSize: 22,
            flex: "none",
          }}
          aria-hidden
        >
          🐶
        </span>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 15, fontWeight: 800, letterSpacing: "-0.02em" }}>
            {state.profile.name}
          </div>
          <div className="c-sub">
            {state.profile.age}세 · {(+state.profile.weight).toFixed(1)}kg
            {state.profile.breed ? ` · ${state.profile.breed}` : ""}
          </div>
        </div>
      </div>

      {/* 요약 배너 */}
      {dietProducts.length === 0 ? (
        <div className="hero" style={{ borderRadius: "var(--radius-lg)" }}>
          <div className="greet">{state.profile.name}의 식단</div>
          <div className="headline">먹이는 사료·간식·영양제를 추가하고 하루에 몇 g 주는지 입력해 보세요</div>
          <button className="bigbtn" style={{ marginTop: 14, width: "auto", padding: "10px 18px" }} onClick={goExplore}>
            먹이는 제품 추가
          </button>
        </div>
      ) : (
        <div
          className="c-card"
          style={{
            background:
              warns.length > 0
                ? "var(--critical-wash)"
                : lacks.length > 0
                  ? "var(--info-wash)"
                  : "var(--ok-wash)",
            borderColor: "transparent",
          }}
        >
          <div style={{ fontSize: 15, fontWeight: 800, letterSpacing: "-0.02em" }}>
            {warns.length > 0
              ? `주의가 필요한 영양소 ${warns.length}개`
              : lacks.length > 0
                ? `참고 범위보다 부족한 영양소 ${lacks.length}개`
                : "지금 급여량 기준으로 충분해요 👍"}
          </div>
          <div className="c-sub" style={{ marginTop: 4 }}>
            {warns.length > 0
              ? warns.map((w) => w.nutrient).join(", ") + " — 아래에서 확인하세요"
              : "이 판정은 실제 영양 적합성을 보장하지 않습니다."}
          </div>
        </div>
      )}

      {/* 먹는 양 입력 */}
      <div className="c-card">
        <div className="c-hd">
          지금 먹이는 것 {activeCount > 0 && <span className="c-sub">· {activeCount}개 급여 중</span>}
          {analysis.loading && <span className="c-sub" style={{ marginLeft: "auto" }}>계산 중…</span>}
        </div>
        {dietProducts.map((p) => {
          const d = diet[p.product_id];
          return (
            <div key={p.product_id} className="listrow" style={{ alignItems: "flex-start" }}>
              <span className="lr-emoji">{catEmoji(p.category)}</span>
              <div className="lr-main">
                <div className="lr-name" onClick={() => openProduct(p.product_id)} style={{ cursor: "pointer" }}>
                  {p.name}
                </div>
                <div className="lr-sub">{p.brand} · {p.category}</div>
                <div style={{ marginTop: 8 }}>
                  <GramInput value={d.g} onChange={(g) => setDietG(p.product_id, g)} />
                </div>
                <div style={{ marginTop: 8, display: "flex", alignItems: "center", gap: 8 }}>
                  <button
                    className="chip"
                    aria-pressed={d.active}
                    onClick={() => toggleDietActive(p.product_id)}
                    style={{ padding: "5px 11px" }}
                  >
                    {d.active ? "급여 중" : "잠시 중단"}
                  </button>
                  <button
                    className="iconbtn"
                    onClick={() => removeFromDiet(p.product_id)}
                    aria-label="빼기"
                    style={{ marginLeft: "auto" }}
                  >
                    ✕
                  </button>
                </div>
              </div>
            </div>
          );
        })}
        <button
          className="bigbtn ghost"
          style={{ marginTop: 12, fontSize: 13, padding: 11 }}
          onClick={goExplore}
        >
          + 먹이는 제품 추가
        </button>
      </div>

      {/* 영양소 현황 */}
      {s.length > 0 && (
        <div className="c-card">
          <div className="c-hd">영양소 현황</div>
          <div className="c-sub" style={{ marginBottom: 8 }}>
            하루 급여량 기준으로 계산한 성분별 총량과 참고 범위입니다.
          </div>
          {s.map((r) => {
            const sw = STATUS_WORD[r.status] ?? { w: r.status, cls: "neutral" };
            const meta = statusMeta(r.status);
            const kv: Record<string, string> = {
              ok: "var(--ok)",
              warning: "var(--warning)",
              critical: "var(--critical)",
              info: "var(--info)",
              neutral: "var(--text-3)",
            };
            const axisMax = Math.max(r.total_mg, r.demo_max_mg, 1e-9) * 1.15;
            const w = (v: number) => Math.min(100, (v / axisMax) * 100) + "%";
            return (
              <div key={r.nutrient} style={{ padding: "9px 0", borderBottom: "1px solid var(--border)" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <b style={{ fontSize: 13 }}>{r.nutrient}</b>
                  <span className={"pill " + sw.cls}>{sw.w}</span>
                  <span style={{ marginLeft: "auto", fontSize: 12, fontVariantNumeric: "tabular-nums", fontWeight: 600 }}>
                    {mg(r.total_mg)} mg
                  </span>
                </div>
                <div style={{ position: "relative", height: 12, background: "var(--surface-3)", borderRadius: 4, marginTop: 6 }}>
                  <span
                    style={{
                      position: "absolute",
                      top: 0,
                      bottom: 0,
                      left: w(r.demo_min_mg),
                      width: `calc(${w(r.demo_max_mg)} - ${w(r.demo_min_mg)})`,
                      background: "var(--border)",
                    }}
                  />
                  <span style={{ position: "absolute", inset: 0, width: w(r.total_mg), background: kv[meta.kind], borderRadius: 4 }} />
                </div>
                <div className="c-sub" style={{ marginTop: 3, fontSize: 10.5 }}>
                  참고 {mg(r.demo_min_mg)}–{mg(r.demo_max_mg)} mg
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 지금 먹이고 있는 성분 */}
      {fed.length > 0 && (
        <div className="c-card">
          <div className="c-hd">지금 먹이고 있는 성분</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 7 }}>
            {fed.map((r) => {
              const sw = STATUS_WORD[r.status] ?? { w: "", cls: "neutral" };
              return (
                <span
                  key={r.nutrient}
                  className={"pill " + sw.cls}
                  style={{ fontWeight: 600, padding: "5px 10px", fontSize: 11.5 }}
                >
                  {r.nutrient} {mg(r.total_mg)}mg
                </span>
              );
            })}
          </div>
          <div className="c-sub" style={{ marginTop: 8 }}>
            참고 기준에 있는 성분 중 아직 안 먹이는 것:{" "}
            {s.filter((r) => r.total_mg === 0).map((r) => r.nutrient).join(", ") || "없음"}
          </div>
        </div>
      )}

      {/* 경고 상세 */}
      {s
        .filter((r) => ["중복 가능", "기준 초과 가능", "정보 부족"].includes(r.status))
        .map((r) => {
          const cs = analysis.data?.contributions[r.nutrient] ?? [];
          return (
            <div
              key={r.nutrient}
              className="c-card"
              style={{ borderColor: "color-mix(in srgb, var(--warning) 45%, transparent)" }}
            >
              <b style={{ fontSize: 13 }}>
                {r.nutrient} · {STATUS_WORD[r.status]?.w ?? r.status}
              </b>
              <div className="c-sub" style={{ marginTop: 4 }}>
                {r.status === "정보 부족"
                  ? "라벨에 이 성분이 표기되지 않은 제품이 있어 정확히 알 수 없어요. 0으로 계산하지 않았습니다."
                  : `하루 약 ${mg(r.total_mg)} mg. ${cs
                      .map((c) => `${c.product_name} ${Math.round(c.share_pct)}%`)
                      .join(" + ")} 에서 나와요.`}
              </div>
            </div>
          );
        })}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 탐색                                                               */
/* ------------------------------------------------------------------ */
function ExploreScreen({ openProduct }: { openProduct: (pid: string) => void }) {
  const { catalog } = useShop();
  const [q, setQ] = useState("");
  const [cat, setCat] = useState("전체");
  const [sort, setSort] = useState<"reco" | "price" | "rating">("reco");
  const { ratings } = useShop();

  const list = useMemo(() => {
    let l = catalog;
    if (cat !== "전체") l = l.filter((p) => p.category === cat);
    const s = q.trim().toLowerCase();
    if (s) l = l.filter((p) => p.name.toLowerCase().includes(s) || (p.brand || "").toLowerCase().includes(s));
    if (sort === "price") l = [...l].sort((a, b) => (a.monthly_price_krw || 0) - (b.monthly_price_krw || 0));
    if (sort === "rating")
      l = [...l].sort((a, b) => (ratings[b.product_id]?.avg || 0) - (ratings[a.product_id]?.avg || 0));
    return l;
  }, [catalog, cat, q, sort, ratings]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "var(--sp-3)" }}>
      <input
        className="input-c"
        placeholder="사료·간식·영양제 검색"
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />
      <div className="chips">
        {["전체", "주식", "간식", "영양제"].map((c) => (
          <button key={c} className="chip" aria-pressed={cat === c} onClick={() => setCat(c)}>
            {c}
          </button>
        ))}
      </div>
      <div className="segmented">
        {([
          ["reco", "추천순"],
          ["price", "가격순"],
          ["rating", "평점순"],
        ] as const).map(([k, l]) => (
          <button key={k} aria-pressed={sort === k} onClick={() => setSort(k)}>
            {l}
          </button>
        ))}
      </div>
      <div className="c-sub">{list.length}개 제품</div>
      <div className="grid-cards">
        {list.map((p) => (
          <ProductCard key={p.product_id} p={p} onOpen={openProduct} />
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 제품 상세 시트                                                     */
/* ------------------------------------------------------------------ */
function ProductSheet({
  pid,
  onClose,
  notify,
}: {
  pid: string;
  onClose: () => void;
  notify: (m: string) => void;
}) {
  const { byId, favorites, toggleFavorite, addToCart, addToDiet, inDiet } = useShop();
  const p = byId[pid];
  const [rev, setRev] = useState<Awaited<ReturnType<typeof api.reviews>> | null>(null);
  const [writing, setWriting] = useState(false);
  const [myRating, setMyRating] = useState(5);
  const [myBody, setMyBody] = useState("");
  const load = () => api.reviews(pid).then(setRev).catch(() => {});
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pid]);
  if (!p) return null;
  const fav = favorites.has(pid);

  async function submit() {
    try {
      await api.addReview({ product_id: pid, rating: myRating, body: myBody.trim() });
      setWriting(false);
      setMyBody("");
      notify("리뷰가 등록되었어요");
      load();
    } catch (e) {
      notify(String((e as Error).message || e));
    }
  }

  return (
    <div className="sheet">
      <header className="appbar">
        <button className="iconbtn" onClick={onClose} aria-label="뒤로">
          ‹
        </button>
        <h1 style={{ fontSize: 14 }}>{p.brand || "제품"}</h1>
        <div className="ab-right">
          <button className="iconbtn" onClick={() => toggleFavorite(pid)} style={{ color: fav ? "var(--critical)" : undefined }}>
            {fav ? "♥" : "♡"}
          </button>
        </div>
      </header>

      <div className="pbody" style={{ padding: 0 }}>
        <div style={{ aspectRatio: "1.6", background: "var(--brand-wash)", display: "grid", placeItems: "center", fontSize: 64 }}>
          {catEmoji(p.category)}
        </div>
        <div style={{ padding: "var(--sp-4)", display: "flex", flexDirection: "column", gap: "var(--sp-4)" }}>
          <div>
            <div className="c-sub">{p.brand || "브랜드"} · {p.category}</div>
            <div style={{ fontSize: 17, fontWeight: 800, letterSpacing: "-0.02em", marginTop: 3 }}>{p.name}</div>
            <div style={{ marginTop: 6, display: "flex", alignItems: "center", gap: 8 }}>
              {rev && rev.summary.count > 0 ? (
                <>
                  <Stars value={rev.summary.avg} size={13} />
                  <b>{rev.summary.avg.toFixed(1)}</b>
                  <span className="c-sub">리뷰 {rev.summary.count}</span>
                </>
              ) : (
                <span className="c-sub">아직 리뷰가 없어요</span>
              )}
            </div>
            <div style={{ fontSize: 20, fontWeight: 800, marginTop: 8 }}>{won(priceOf(p))}<span className="c-sub" style={{ fontWeight: 500 }}> / 월</span></div>
          </div>

          <div style={{ display: "flex", gap: 10 }}>
            <button
              className="bigbtn ghost"
              onClick={() => {
                if (!inDiet(pid)) addToDiet(pid);
                notify("내 식단에 추가했어요");
              }}
            >
              내 식단에 추가
            </button>
            <button
              className="bigbtn"
              onClick={() => {
                addToCart(pid);
                notify("장바구니에 담았어요");
              }}
            >
              장바구니
            </button>
          </div>

          <div className="c-card">
            <div className="c-hd">영양성분 (라벨 {p.serving_basis_g}g 기준)</div>
            <table style={{ width: "100%", fontSize: 12.5 }}>
              <tbody>
                {p.nutrients.map((n) => (
                  <tr key={n.nutrient}>
                    <td style={{ padding: "5px 0", color: "var(--text-secondary)" }}>{n.nutrient}</td>
                    <td style={{ padding: "5px 0", textAlign: "right", fontVariantNumeric: "tabular-nums" }}>
                      {mg(n.amount_mg)} mg
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!p.label_complete && <div className="c-sub" style={{ marginTop: 6 }}>일부 영양소 미표기 · 분석 신뢰도 제한</div>}
          </div>

          <div className="c-card">
            <div className="c-hd">
              리뷰 {rev?.summary.count ?? 0}
              <button className="more" onClick={() => setWriting((v) => !v)}>
                {writing ? "취소" : "리뷰 쓰기"}
              </button>
            </div>

            {writing && (
              <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 12 }}>
                <div style={{ display: "flex", gap: 4, fontSize: 22, color: "var(--warning)" }}>
                  {[1, 2, 3, 4, 5].map((i) => (
                    <button
                      key={i}
                      onClick={() => setMyRating(i)}
                      style={{ border: 0, background: "none", cursor: "pointer", color: i <= myRating ? "var(--warning)" : "var(--border-strong)" }}
                    >
                      ★
                    </button>
                  ))}
                </div>
                <textarea
                  className="input-c"
                  rows={3}
                  placeholder="아이가 잘 먹었나요? 솔직한 후기를 남겨주세요"
                  value={myBody}
                  onChange={(e) => setMyBody(e.target.value)}
                />
                <button className="bigbtn" onClick={submit}>
                  등록
                </button>
              </div>
            )}

            {rev?.reviews.length ? (
              rev.reviews.map((rv) => (
                <div key={rv.review_id} style={{ padding: "10px 0", borderTop: "1px solid var(--border)" }}>
                  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                    <Stars value={rv.rating} />
                    <b style={{ fontSize: 12 }}>{rv.author}</b>
                    <span className="c-sub" style={{ marginLeft: "auto" }}>
                      {new Date(rv.created_at).toLocaleDateString("ko-KR")}
                    </span>
                  </div>
                  {rv.body && <div style={{ fontSize: 12.5, marginTop: 5, color: "var(--text-secondary)" }}>{rv.body}</div>}
                </div>
              ))
            ) : (
              <div className="c-sub">첫 리뷰를 남겨보세요.</div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}


/* ------------------------------------------------------------------ */
/* 찜                                                                 */
/* ------------------------------------------------------------------ */
function FavScreen({ openProduct, goExplore }: { openProduct: (pid: string) => void; goExplore: () => void }) {
  const { favorites, byId } = useShop();
  const list = [...favorites].map((id) => byId[id]).filter(Boolean);
  if (list.length === 0)
    return (
      <div style={{ textAlign: "center", padding: "60px 20px", color: "var(--text-muted)" }}>
        <div style={{ fontSize: 40 }}>♡</div>
        <p style={{ margin: "12px 0 16px", fontSize: 13 }}>마음에 드는 제품을 하트로 저장해 두세요.</p>
        <button className="bigbtn" style={{ width: "auto", padding: "10px 18px" }} onClick={goExplore}>
          제품 둘러보기
        </button>
      </div>
    );
  return (
    <div className="grid-cards">
      {list.map((p) => (
        <ProductCard key={p.product_id} p={p} onOpen={openProduct} />
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 장바구니 / 결제                                                    */
/* ------------------------------------------------------------------ */
function CartSheet({
  onClose,
  onCheckout,
  openProduct,
}: {
  onClose: () => void;
  onCheckout: () => void;
  openProduct: (pid: string) => void;
}) {
  const { cart, byId, setQty, removeFromCart } = useShop();
  const lines = cart.map((l) => ({ ...l, p: byId[l.product_id] })).filter((l) => l.p);
  const total = lines.reduce((a, b) => a + b.qty * (b.p!.monthly_price_krw || 0), 0);
  return (
    <div className="sheet">
      <header className="appbar">
        <button className="iconbtn" onClick={onClose}>
          ‹
        </button>
        <h1 style={{ fontSize: 15 }}>장바구니</h1>
      </header>
      <div className="pbody">
        {lines.length === 0 ? (
          <div style={{ textAlign: "center", padding: 50, color: "var(--text-muted)", fontSize: 13 }}>
            장바구니가 비어 있어요.
          </div>
        ) : (
          <>
            {lines.map((l) => (
              <div key={l.product_id} className="listrow">
                <span className="lr-emoji" onClick={() => openProduct(l.product_id)}>
                  {catEmoji(l.p!.category)}
                </span>
                <div className="lr-main">
                  <div className="lr-name">{l.p!.name}</div>
                  <div className="lr-sub">{won(l.p!.monthly_price_krw || 0)}</div>
                  <div style={{ marginTop: 6 }}>
                    <span className="stepper">
                      <button onClick={() => setQty(l.product_id, l.qty - 1)}>−</button>
                      <span className="val">{l.qty}</span>
                      <button onClick={() => setQty(l.product_id, l.qty + 1)}>+</button>
                    </span>
                  </div>
                </div>
                <button className="iconbtn" onClick={() => removeFromCart(l.product_id)}>
                  ✕
                </button>
              </div>
            ))}
            <div style={{ display: "flex", justifyContent: "space-between", padding: "16px 0", fontSize: 15, fontWeight: 800 }}>
              <span>합계</span>
              <span>{won(total)}</span>
            </div>
            <button className="bigbtn" onClick={onCheckout}>
              {won(total)} 주문하기
            </button>
          </>
        )}
      </div>
    </div>
  );
}

function CheckoutSheet({ onClose, notify }: { onClose: () => void; notify: (m: string) => void }) {
  const { cart, byId, clearCart } = useShop();
  const [addr, setAddr] = useState("");
  const [pay, setPay] = useState<"card" | "easy">("easy");
  const [done, setDone] = useState<{ order_id: number; total: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const lines = cart.map((l) => ({ ...l, p: byId[l.product_id] })).filter((l) => l.p);
  const total = lines.reduce((a, b) => a + b.qty * (b.p!.monthly_price_krw || 0), 0);

  async function pyamentSubmit() {
    setBusy(true);
    try {
      const res = await api.createOrder({
        items: lines.map((l) => ({
          product_id: l.product_id,
          name: l.p!.name,
          qty: l.qty,
          price_krw: l.p!.monthly_price_krw || 0,
        })),
        address: addr.trim(),
      });
      setDone({ order_id: res.order_id, total: res.total_krw });
      clearCart();
    } catch (e) {
      notify(String((e as Error).message || e));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="sheet">
        <div className="pbody" style={{ display: "grid", placeItems: "center", textAlign: "center" }}>
          <div>
            <div style={{ fontSize: 46 }}>✅</div>
            <h2 style={{ fontSize: 18, margin: "10px 0 4px" }}>주문이 완료됐어요</h2>
            <p className="c-sub">주문번호 #{done.order_id} · {won(done.total)}</p>
            <p className="c-sub" style={{ marginTop: 6 }}>모의 결제입니다. 실제 청구는 발생하지 않습니다.</p>
            <button className="bigbtn" style={{ marginTop: 20, width: "auto", padding: "11px 22px" }} onClick={onClose}>
              확인
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="sheet">
      <header className="appbar">
        <button className="iconbtn" onClick={onClose}>
          ‹
        </button>
        <h1 style={{ fontSize: 15 }}>주문 / 결제</h1>
      </header>
      <div className="pbody" style={{ display: "flex", flexDirection: "column", gap: "var(--sp-4)" }}>
        <div className="c-card">
          <div className="c-hd">주문 상품</div>
          {lines.map((l) => (
            <div key={l.product_id} style={{ display: "flex", justifyContent: "space-between", fontSize: 13, padding: "4px 0" }}>
              <span>
                {l.p!.name} × {l.qty}
              </span>
              <span>{won(l.qty * (l.p!.monthly_price_krw || 0))}</span>
            </div>
          ))}
        </div>
        <div className="field-c">
          <label>배송지</label>
          <input className="input-c" placeholder="주소를 입력하세요" value={addr} onChange={(e) => setAddr(e.target.value)} />
        </div>
        <div>
          <label style={{ fontSize: 12, fontWeight: 600, color: "var(--text-secondary)" }}>결제수단</label>
          <div className="segmented" style={{ marginTop: 6 }}>
            <button aria-pressed={pay === "easy"} onClick={() => setPay("easy")}>
              간편결제
            </button>
            <button aria-pressed={pay === "card"} onClick={() => setPay("card")}>
              신용카드
            </button>
          </div>
        </div>
        <div className="c-sub">
          모의 결제 데모입니다. 카드사 연동이 없으며 실제 청구는 발생하지 않습니다.
        </div>
        <button className="bigbtn" disabled={busy || lines.length === 0} onClick={pyamentSubmit}>
          {busy ? "처리 중…" : `${won(total)} 결제하기`}
        </button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 알림 / 주문내역 / 마이                                             */
/* ------------------------------------------------------------------ */
function NotifSheet({ onClose, goDiet }: { onClose: () => void; goDiet: () => void }) {
  const { analysis, dietProducts } = useShop();
  const { state } = useSession();
  const items: { icon: string; title: string; body: string; action?: () => void }[] = [];
  const warns = analysis.data?.summary.filter((r) => ["중복 가능", "기준 초과 가능"].includes(r.status)) ?? [];
  if (warns.length)
    items.push({
      icon: "⚠️",
      title: `${state.profile.name} 영양 경고 ${warns.length}건`,
      body: warns.map((w) => `${w.nutrient} ${w.status}`).join(", "),
      action: goDiet,
    });
  if (dietProducts.length)
    items.push({
      icon: "🔁",
      title: "재구매 시기 알림",
      body: `${dietProducts[0].name} 재고가 곧 떨어질 것 같아요.`,
    });
  items.push({ icon: "⭐", title: "내 리뷰에 답글", body: "브랜드가 회원님의 리뷰에 감사 인사를 남겼어요." });
  items.push({ icon: "📦", title: "배송 안내", body: "최근 주문이 오늘 출고 예정입니다." });

  return (
    <div className="sheet">
      <header className="appbar">
        <button className="iconbtn" onClick={onClose}>
          ‹
        </button>
        <h1 style={{ fontSize: 15 }}>알림</h1>
      </header>
      <div className="pbody">
        {items.map((it, i) => (
          <div key={i} className="listrow" onClick={it.action} style={{ cursor: it.action ? "pointer" : "default" }}>
            <span className="lr-emoji">{it.icon}</span>
            <div className="lr-main">
              <div className="lr-name">{it.title}</div>
              <div className="lr-sub" style={{ whiteSpace: "normal" }}>
                {it.body}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function OrdersSheet({ onClose }: { onClose: () => void }) {
  const [orders, setOrders] = useState<Awaited<ReturnType<typeof api.orders>>>([]);
  useEffect(() => {
    api.orders().then(setOrders).catch(() => setOrders([]));
  }, []);
  return (
    <div className="sheet">
      <header className="appbar">
        <button className="iconbtn" onClick={onClose}>
          ‹
        </button>
        <h1 style={{ fontSize: 15 }}>주문 내역</h1>
      </header>
      <div className="pbody">
        {orders.length === 0 ? (
          <div style={{ textAlign: "center", padding: 50, color: "var(--text-muted)", fontSize: 13 }}>
            주문 내역이 없어요.
          </div>
        ) : (
          orders.map((o) => (
            <div key={o.order_id} className="c-card" style={{ marginBottom: 10 }}>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <b style={{ fontSize: 13 }}>#{o.order_id}</b>
                <span className="c-sub">{new Date(o.created_at).toLocaleDateString("ko-KR")}</span>
              </div>
              <div className="c-sub" style={{ margin: "6px 0" }}>
                {o.items.map((it) => `${it.name}×${it.qty}`).join(", ")}
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13 }}>
                <span className="pill ok">결제완료</span>
                <b>{won(o.total_krw)}</b>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function MyScreen({ onOrders }: { onOrders: () => void }) {
  const { user, logout } = useAuth();
  const { state, dispatch } = useSession();
  const p = state.profile;
  const [edit, setEdit] = useState(false);
  const [notif, setNotif] = useState(true);
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem("wooaeyoung-theme") || "system";
    } catch {
      return "system";
    }
  });
  function applyTheme(t: string) {
    setTheme(t);
    const r = document.documentElement;
    if (t === "system") r.removeAttribute("data-theme");
    else r.setAttribute("data-theme", t);
    try {
      localStorage.setItem("wooaeyoung-theme", t);
    } catch {
      /* ignore */
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "var(--sp-4)" }}>
      <div className="c-card" style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span style={{ width: 48, height: 48, borderRadius: 14, background: "var(--brand-wash)", display: "grid", placeItems: "center", fontSize: 24 }}>
          🐶
        </span>
        <div style={{ flex: 1 }}>
          <b style={{ fontSize: 15 }}>{p.name}</b>
          <div className="c-sub">
            {p.age}세 · {(+p.weight).toFixed(1)}kg{p.breed ? ` · ${p.breed}` : ""}
          </div>
          <div className="c-sub">{user?.email}</div>
        </div>
        <button className="more" onClick={() => setEdit((v) => !v)}>
          {edit ? "닫기" : "편집"}
        </button>
      </div>

      {edit && (
        <div className="c-card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <div className="field-c">
            <label>이름</label>
            <input className="input-c" value={p.name} onChange={(e) => dispatch({ type: "profile", patch: { name: e.target.value } })} />
          </div>
          <div style={{ display: "flex", gap: 10 }}>
            <div className="field-c" style={{ flex: 1 }}>
              <label>나이</label>
              <input className="input-c" type="number" value={p.age} onChange={(e) => dispatch({ type: "profile", patch: { age: +e.target.value } })} />
            </div>
            <div className="field-c" style={{ flex: 1 }}>
              <label>체중(kg)</label>
              <input className="input-c" type="number" step="0.1" value={p.weight} onChange={(e) => dispatch({ type: "profile", patch: { weight: +e.target.value } })} />
            </div>
          </div>
          <div className="field-c">
            <label>품종</label>
            <input className="input-c" value={p.breed} onChange={(e) => dispatch({ type: "profile", patch: { breed: e.target.value } })} />
          </div>
        </div>
      )}

      <div className="c-card">
        <button className="listrow" style={{ width: "100%", border: 0, background: "none", cursor: "pointer" }} onClick={onOrders}>
          <span className="lr-emoji">📦</span>
          <div className="lr-main" style={{ textAlign: "left" }}>
            <div className="lr-name">주문 내역</div>
            <div className="lr-sub">모의 결제 주문 기록</div>
          </div>
          <span className="c-sub">›</span>
        </button>
        <div className="listrow">
          <span className="lr-emoji">🔔</span>
          <div className="lr-main">
            <div className="lr-name">알림 받기</div>
            <div className="lr-sub">영양 경고 · 재구매 시기</div>
          </div>
          <label className="switch">
            <input type="checkbox" checked={notif} onChange={(e) => setNotif(e.target.checked)} />
            <span className="track" />
          </label>
        </div>
        <div className="listrow" style={{ alignItems: "flex-start" }}>
          <span className="lr-emoji">🎨</span>
          <div className="lr-main">
            <div className="lr-name">테마</div>
            <div className="segmented" style={{ marginTop: 6 }}>
              {["light", "system", "dark"].map((t) => (
                <button key={t} aria-pressed={theme === t} onClick={() => applyTheme(t)}>
                  {t === "light" ? "밝게" : t === "dark" ? "어둡게" : "시스템"}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      <div className="c-card" style={{ background: "linear-gradient(160deg, var(--brand-wash), var(--surface-1))" }}>
        <div className="c-hd">우애영 프리미엄</div>
        <div className="c-sub">맞춤 급여 리포트, 무제한 영양 분석, 재구매 자동 배송 — 월 4,900원</div>
        <button className="bigbtn" style={{ marginTop: 12 }} disabled>
          구독 준비 중
        </button>
      </div>

      <button className="bigbtn ghost" onClick={() => logout()}>
        로그아웃
      </button>
      <div className="c-sub" style={{ textAlign: "center" }}>
        기능 검증용 데모 · 실제 급여 판단에 사용할 수 없습니다.
      </div>
    </div>
  );
}
