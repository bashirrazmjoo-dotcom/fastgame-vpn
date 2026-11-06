# FastGame VPN — App API (v1)

All errors: `{"error":{"code","message","details"}}`. App endpoints (except `/v1/config`, `/v1/devices/register`) need `Authorization: Bearer <device_token>`.

| Method | Path | Purpose |
|---|---|---|
| GET | /v1/config | heartbeat interval, support/privacy URLs, min supported version |
| POST | /v1/devices/register | `hardware_id`, `version_code`, `locale` → `user_code`, `device_token` (same hardware = same account) |
| GET | /v1/me | account, VIP state, quota (total/used/remaining/resets_at), Telegram purchase URL |
| GET | /v1/servers | list of servers (no secrets) |
| POST | /v1/sessions | start session for `server_id`; returns OpenVPN profile only if not blocked and quota remains |
| POST | /v1/sessions/{id}/connected | tunnel is up — charging starts here |
| POST | /v1/sessions/{id}/heartbeat | server credits elapsed time (max grace 45s) and returns status/remaining |
| POST | /v1/sessions/{id}/stop | close session |

Error codes the app handles: `quota_exhausted` (details.resets_at), `device_blocked`, `session_closed`, `server_unavailable`, 401 (token invalid → app re-registers).
Time is metered only on the server; the app's countdown is a display estimate re-anchored on every heartbeat.
Admin API lives under `/admin/*` (JWT) — see OpenAPI in development mode.
