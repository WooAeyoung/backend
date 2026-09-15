import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api } from "../api/client";
import type { AnalyzeResponse, Product, Selection } from "../api/types";

export interface CartLine {
  product_id: string;
  qty: number;
}
export interface DietLine {
  g: number;
  active: boolean;
}

interface ShopCtx {
  catalog: Product[];
  byId: Record<string, Product>;
  ratings: Record<string, { avg: number; count: number }>;
  favorites: Set<string>;
  toggleFavorite: (pid: string) => void;
  cart: CartLine[];
  cartCount: number;
  addToCart: (pid: string, qty?: number) => void;
  setQty: (pid: string, qty: number) => void;
  removeFromCart: (pid: string) => void;
  clearCart: () => void;
  refreshRatings: () => void;

  diet: Record<string, DietLine>;
  dietProducts: Product[];
  inDiet: (pid: string) => boolean;
  addToDiet: (pid: string, g?: number) => void;
  setDietG: (pid: string, g: number) => void;
  toggleDietActive: (pid: string) => void;
  removeFromDiet: (pid: string) => void;
  analysis: { data: AnalyzeResponse | null; loading: boolean; error: string | null };
}

const Ctx = createContext<ShopCtx | null>(null);
const CART_KEY = "wooaeyoung-cart";
const DIET_KEY = "wooaeyoung-diet";
const DEF_G: Record<string, number> = { food_a: 120, supp_cal: 2, snack_a: 10, multi_a: 3 };

export function ShopProvider({ children }: { children: ReactNode }) {
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [ratings, setRatings] = useState<Record<string, { avg: number; count: number }>>({});
  const [favorites, setFavorites] = useState<Set<string>>(new Set());
  const [cart, setCart] = useState<CartLine[]>(() => {
    try {
      return JSON.parse(localStorage.getItem(CART_KEY) || "[]");
    } catch {
      return [];
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(CART_KEY, JSON.stringify(cart));
    } catch {
      /* ignore */
    }
  }, [cart]);

  const refreshRatings = useCallback(() => {
    const ids = catalog.map((p) => p.product_id);
    if (!ids.length) return;
    api.ratings(ids).then(setRatings).catch(() => {});
  }, [catalog]);

  useEffect(() => {
    api.catalogProducts().then(setCatalog).catch(() => setCatalog([]));
    api.favorites().then((r) => setFavorites(new Set(r.product_ids))).catch(() => {});
  }, []);

  useEffect(() => {
    refreshRatings();
  }, [refreshRatings]);

  const byId = useMemo(
    () => Object.fromEntries(catalog.map((p) => [p.product_id, p])),
    [catalog],
  );

  const toggleFavorite = useCallback(
    (pid: string) => {
      setFavorites((prev) => {
        const next = new Set(prev);
        if (next.has(pid)) {
          next.delete(pid);
          api.removeFavorite(pid).catch(() => {});
        } else {
          next.add(pid);
          api.addFavorite(pid).catch(() => {});
        }
        return next;
      });
    },
    [],
  );

  const addToCart = useCallback((pid: string, qty = 1) => {
    setCart((prev) => {
      const i = prev.findIndex((l) => l.product_id === pid);
      if (i >= 0) {
        const next = [...prev];
        next[i] = { ...next[i], qty: next[i].qty + qty };
        return next;
      }
      return [...prev, { product_id: pid, qty }];
    });
  }, []);

  const setQty = useCallback((pid: string, qty: number) => {
    setCart((prev) =>
      qty <= 0
        ? prev.filter((l) => l.product_id !== pid)
        : prev.map((l) => (l.product_id === pid ? { ...l, qty } : l)),
    );
  }, []);

  const removeFromCart = useCallback(
    (pid: string) => setCart((prev) => prev.filter((l) => l.product_id !== pid)),
    [],
  );
  const clearCart = useCallback(() => setCart([]), []);

  // ---- 내 식단 ----
  const [diet, setDiet] = useState<Record<string, DietLine>>(() => {
    try {
      return JSON.parse(localStorage.getItem(DIET_KEY) || "{}");
    } catch {
      return {};
    }
  });
  useEffect(() => {
    try {
      localStorage.setItem(DIET_KEY, JSON.stringify(diet));
    } catch {
      /* ignore */
    }
  }, [diet]);

  const inDiet = useCallback((pid: string) => !!diet[pid], [diet]);
  const addToDiet = useCallback(
    (pid: string, g?: number) =>
      setDiet((d) => (d[pid] ? d : { ...d, [pid]: { g: g ?? DEF_G[pid] ?? 50, active: true } })),
    [],
  );
  const setDietG = useCallback(
    (pid: string, g: number) =>
      setDiet((d) => (d[pid] ? { ...d, [pid]: { ...d[pid], g: Math.max(0, g) } } : d)),
    [],
  );
  const toggleDietActive = useCallback(
    (pid: string) =>
      setDiet((d) => (d[pid] ? { ...d, [pid]: { ...d[pid], active: !d[pid].active } } : d)),
    [],
  );
  const removeFromDiet = useCallback(
    (pid: string) =>
      setDiet((d) => {
        const n = { ...d };
        delete n[pid];
        return n;
      }),
    [],
  );

  const dietProducts = useMemo(
    () => Object.keys(diet).map((id) => byId[id]).filter(Boolean),
    [diet, byId],
  );

  const [analysis, setAnalysis] = useState<ShopCtx["analysis"]>({
    data: null,
    loading: false,
    error: null,
  });
  const dietKey = JSON.stringify(diet);
  useEffect(() => {
    const products = Object.keys(diet)
      .map((id) => byId[id])
      .filter(Boolean);
    if (products.length === 0) {
      setAnalysis({ data: null, loading: false, error: null });
      return;
    }
    const selections: Selection[] = products.map((p) => ({
      product_id: p.product_id,
      daily_amount_g: diet[p.product_id].g,
      active: diet[p.product_id].active,
    }));
    setAnalysis((a) => ({ ...a, loading: true }));
    const t = window.setTimeout(() => {
      api
        .analyze(products, selections)
        .then((data) => setAnalysis({ data, loading: false, error: null }))
        .catch((e) => setAnalysis({ data: null, loading: false, error: String(e.message || e) }));
    }, 250);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dietKey, byId]);

  const value: ShopCtx = {
    catalog,
    byId,
    ratings,
    favorites,
    toggleFavorite,
    cart,
    cartCount: cart.reduce((a, b) => a + b.qty, 0),
    addToCart,
    setQty,
    removeFromCart,
    clearCart,
    refreshRatings,
    diet,
    dietProducts,
    inDiet,
    addToDiet,
    setDietG,
    toggleDietActive,
    removeFromDiet,
    analysis,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useShop(): ShopCtx {
  const c = useContext(Ctx);
  if (!c) throw new Error("useShop outside ShopProvider");
  return c;
}

export function catEmoji(cat: string): string {
  return cat === "주식" ? "🥣" : cat === "간식" ? "🦴" : "💊";
}
