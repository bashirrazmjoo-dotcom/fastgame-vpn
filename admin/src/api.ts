// Thin client for the /admin API. The token lives in sessionStorage only (cleared when the tab closes).
export type Quota = { used_seconds: number; remaining_seconds: number; resets_at: string | null };
export type Device = {
  id: string; user_code: string; is_vip: boolean; vip_expires_at: string | null; vip_lifetime: boolean;
  is_blocked: boolean; block_reason: string | null; blocked_at: string | null; created_at: string;
  last_seen_at: string | null; app_version_code: number; locale: string; total_used_seconds: number;
  quota: Quota; has_active_session: boolean;
};
export type SessionRow = {
  id: string; server_name: string; started_at: string; ended_at: string | null;
  counted_seconds: number; end_reason: string | null; was_vip: boolean;
};
export type VipEvent = {
  action: string; days: number | null; lifetime: boolean; expires_at_after: string | null;
  admin_username: string; created_at: string;
};
export type DeviceDetail = Device & { sessions: SessionRow[]; vip_events: VipEvent[] };
export type DeviceList = { items: Device[]; total: number; limit: number; offset: number };
export type Server = {
  id: number; name: string; country_code: string; country_name_en: string; country_name_fa: string;
  host: string; port: number; proto: string; ovpn_config: string; auth_username: string | null;
  has_credentials: boolean; mikrotik: { api_url: string; username: string; ppp_profile: string | null; has_ca: boolean } | null; is_active: boolean; sort_order: number; created_at: string; active_connections: number;
};
export type ServerInput = {
  name: string; country_code: string; country_name_en: string; country_name_fa: string;
  ovpn_config: string; auth_username?: string | null; auth_password?: string | null;
  clear_credentials?: boolean; clear_mikrotik?: boolean;
  mikrotik?: { api_url: string; username: string; password?: string | null; ppp_profile: string; ca_pem?: string | null } | null;
  is_active: boolean; sort_order: number;
};
export type Stats = {
  total_devices: number; vip_devices: number; blocked_devices: number; new_devices_24h: number;
  active_sessions: number; sessions_started_24h: number; usage_seconds_24h: number;
};
export type ActiveSession = {
  session_id: string; user_code: string; server_id: number; server_name: string; started_at: string;
  last_heartbeat_at: string; duration_seconds: number; counted_seconds: number; was_vip: boolean;
};

const KEY = "fg_admin_token";
export const getToken = () => { try { return sessionStorage.getItem(KEY); } catch { return null; } };
export const setToken = (t: string | null) => {
  try { t ? sessionStorage.setItem(KEY, t) : sessionStorage.removeItem(KEY); } catch { /* ignore */ }
};

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

let onUnauthorized: () => void = () => {};
export const setUnauthorizedHandler = (fn: () => void) => { onUnauthorized = fn; };

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = getToken();
  const res = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    if (res.status === 401 && path !== "/admin/auth/login") onUnauthorized();
    const err = data?.error;
    throw new ApiError(res.status, err?.code ?? "error", err?.message ?? `HTTP ${res.status}`);
  }
  return data as T;
}

const q = (o: Record<string, string | number | undefined>) =>
  Object.entries(o).filter(([, v]) => v !== undefined && v !== "").map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join("&");

export const api = {
  login: (username: string, password: string) =>
    request<{ access_token: string; expires_in: number }>("POST", "/admin/auth/login", { username, password }),
  stats: () => request<Stats>("GET", "/admin/stats"),
  activeSessions: () => request<ActiveSession[]>("GET", "/admin/sessions/active"),
  devices: (p: { q?: string; status?: string; limit?: number; offset?: number }) =>
    request<DeviceList>("GET", `/admin/devices?${q(p)}`),
  device: (code: string) => request<DeviceDetail>("GET", `/admin/devices/${encodeURIComponent(code)}`),
  vip: (code: string, body: { action: "activate" | "extend" | "cancel"; days?: number; lifetime?: boolean }) =>
    request<Device>("POST", `/admin/devices/${encodeURIComponent(code)}/vip`, body),
  block: (code: string, blocked: boolean, reason?: string) =>
    request<Device>("POST", `/admin/devices/${encodeURIComponent(code)}/block`, { blocked, reason }),
  resetQuota: (code: string) => request<Device>("POST", `/admin/devices/${encodeURIComponent(code)}/reset-quota`),
  servers: () => request<Server[]>("GET", "/admin/servers"),
  createServer: (b: ServerInput) => request<Server>("POST", "/admin/servers", b),
  updateServer: (id: number, b: ServerInput) => request<Server>("PUT", `/admin/servers/${id}`, b),
  deleteServer: (id: number) => request<void>("DELETE", `/admin/servers/${id}`),
};
