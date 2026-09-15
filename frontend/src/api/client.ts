import type {
  AnalyzeResponse,
  Offer,
  OcrDraft,
  Pet,
  Product,
  RecommendResponse,
  Selection,
  StandardRow,
  User,
} from "./types";

const API_BASE_KEY = "wooaeyoung-api-base";
const TOKEN_KEY = "wooaeyoung-token";

export function getApiBase(): string {
  try {
    return localStorage.getItem(API_BASE_KEY) || "";
  } catch {
    return "";
  }
}
export function setApiBase(v: string) {
  try {
    if (v) localStorage.setItem(API_BASE_KEY, v.replace(/\/$/, ""));
    else localStorage.removeItem(API_BASE_KEY);
  } catch {
    /* ignore */
  }
}
export function getToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) || "";
  } catch {
    return "";
  }
}
export function setToken(v: string | null) {
  try {
    if (v) localStorage.setItem(TOKEN_KEY, v);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { ...(init?.headers as Record<string, string>) };
  if (!(init?.body instanceof FormData)) headers["Content-Type"] = "application/json";
  const token = getToken();
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(getApiBase() + path, { ...init, headers });
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body?.detail)
        detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
    } catch {
      /* ignore */
    }
    const err = new Error(detail) as Error & { status?: number };
    err.status = res.status;
    throw err;
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  health: () => req<{ status: string }>("/health"),

  // 인증 (F-028)
  register: (email: string, password: string, display_name?: string) =>
    req<{ user: User; token: string }>("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, password, display_name }),
    }),
  login: (email: string, password: string) =>
    req<{ user: User; token: string }>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),
  logout: () => req<{ ok: boolean }>("/api/auth/logout", { method: "POST" }),
  me: () => req<User>("/api/auth/me"),

  // 카탈로그 · 분석
  catalogProducts: () => req<Product[]>("/api/catalog/products"),
  catalogStandards: () => req<StandardRow[]>("/api/catalog/standards"),
  analyze: (products: Product[], selections: Selection[]) =>
    req<AnalyzeResponse>("/api/session/analyze", {
      method: "POST",
      body: JSON.stringify({ products, selections }),
    }),
  recommend: (products: Product[], selections: Selection[]) =>
    req<RecommendResponse>("/api/recommend", {
      method: "POST",
      body: JSON.stringify({ products, selections }),
    }),

  // 단위 변환 (F-011)
  convertUnit: (body: {
    value: number;
    unit: string;
    nutrient?: string;
    serving_basis_g?: number;
    servings_per_pack?: number;
  }) =>
    req<{ mg: number; unit: string }>("/api/units/convert", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  supportedUnits: () => req<{ supported: string[] }>("/api/units"),

  // 최저가 (F-021)
  prices: (productId: string) =>
    req<{ product_id: string; offers: Offer[]; lowest_price_krw: number | null; lowest_vendor?: string }>(
      `/api/prices/${encodeURIComponent(productId)}`,
    ),

  // OCR
  ocrAvailable: () =>
    req<{ image_ocr: boolean; vision: boolean }>("/api/ocr/available"),
  ocrDraft: (payload: { text?: string; file?: File }) => {
    const fd = new FormData();
    if (payload.text) fd.append("text", payload.text);
    if (payload.file) fd.append("file", payload.file);
    return req<OcrDraft>("/api/ocr/draft", { method: "POST", body: fd });
  },
  ocrConfirm: (body: {
    product_name: string;
    category: string;
    serving_basis_g: number;
    label_complete: boolean;
    monthly_price_krw: number;
    nutrients: { nutrient: string; amount_mg: number; label_complete: boolean }[];
  }) => req<Product>("/api/ocr/confirm", { method: "POST", body: JSON.stringify(body) }),

  // 저장 · 복원 (F-026)
  listPets: () => req<Pet[]>("/api/pets"),
  saveSession: (body: {
    profile: { name: string; weight_kg: number; age: number; breed: string; neutered: boolean };
    products: Product[];
    selections: Selection[];
  }) =>
    req<{ pet_id: number; saved_products: number }>("/api/session/save", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  restoreSession: (petId: number) =>
    req<{
      profile: { name: string; weight_kg: number; breed: string; neutered: boolean };
      products: Product[];
      selections: Selection[];
    }>(`/api/session/restore/${petId}`),

  // 소비자 앱: 즐겨찾기 · 리뷰 · 주문
  favorites: () => req<{ product_ids: string[] }>("/api/favorites"),
  addFavorite: (pid: string) =>
    req<{ ok: boolean }>(`/api/favorites/${encodeURIComponent(pid)}`, { method: "PUT" }),
  removeFavorite: (pid: string) =>
    req<{ ok: boolean }>(`/api/favorites/${encodeURIComponent(pid)}`, { method: "DELETE" }),
  reviews: (pid: string) =>
    req<{
      summary: { avg: number; count: number; dist: Record<string, number> };
      reviews: { review_id: number; author: string; rating: number; body: string; created_at: string }[];
    }>(`/api/reviews/${encodeURIComponent(pid)}`),
  ratings: (ids: string[]) =>
    req<Record<string, { avg: number; count: number }>>(
      `/api/ratings?ids=${ids.map(encodeURIComponent).join(",")}`,
    ),
  addReview: (body: { product_id: string; rating: number; body: string }) =>
    req<{ review_id: number }>("/api/reviews", { method: "POST", body: JSON.stringify(body) }),
  createOrder: (body: {
    items: { product_id: string; name: string; qty: number; price_krw: number }[];
    address: string;
  }) =>
    req<{ order_id: number; status: string; total_krw: number }>("/api/orders", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  orders: () =>
    req<
      {
        order_id: number;
        items: { product_id: string; name: string; qty: number; price_krw: number }[];
        total_krw: number;
        address: string;
        status: string;
        created_at: string;
      }[]
    >("/api/orders"),
};
