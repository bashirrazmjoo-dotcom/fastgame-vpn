import { useState } from "react";
import { api } from "../api";
import { Badge, Btn, Card, fmtDate, fmtDur, inputCls, useAsync } from "../ui";

export default function UserDetail({ code }: { code: string }) {
  const { data: u, error, reload } = useAsync(() => api.device(code), [code]);
  const [days, setDays] = useState(30), [reason, setReason] = useState(""), [msg, setMsg] = useState("");
  const run = async (fn: () => Promise<unknown>, confirmText?: string) => {
    if (confirmText && !confirm(confirmText)) return;
    try { await fn(); setMsg("Done."); reload(); } catch (e) { setMsg((e as Error).message); }
  };
  if (error) return <p className="text-rose-400">{error}</p>;
  if (!u) return <p className="text-slate-400">Loading…</p>;
  return (
    <div className="space-y-4">
      <a href="#/users" className="text-sm text-slate-400">← Users</a>
      <Card title={u.user_code} right={<div className="space-x-1">
        {u.is_vip ? <Badge tone="amber">{u.vip_lifetime ? "VIP lifetime" : `VIP until ${fmtDate(u.vip_expires_at)}`}</Badge> : <Badge tone="slate">Free</Badge>}
        {u.is_blocked && <Badge tone="red">Blocked</Badge>}{u.has_active_session && <Badge tone="green">online</Badge>}
      </div>}>
        <dl className="grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
          {([
            ["Used this cycle", fmtDur(u.quota.used_seconds)], ["Remaining", u.is_vip ? "∞" : fmtDur(u.quota.remaining_seconds)],
            ["Cycle resets", fmtDate(u.quota.resets_at)], ["Total used", fmtDur(u.total_used_seconds)],
            ["Created", fmtDate(u.created_at)], ["Last seen", fmtDate(u.last_seen_at)],
            ["App version", u.app_version_code], ["Language", u.locale],
          ] as [string, string | number][]).map(([k, v]) => (
            <div key={k}><dt className="text-slate-400">{k}</dt><dd>{v}</dd></div>
          ))}
        </dl>
        {u.is_blocked && <p className="mt-2 text-sm text-rose-300">Blocked {fmtDate(u.blocked_at)}: {u.block_reason ?? "no reason"}</p>}
      </Card>

      <Card title="Actions">
        <div className="flex flex-wrap items-center gap-2">
          <input type="number" min={1} max={3650} className={`${inputCls} w-24`} value={days} onChange={(e) => setDays(Number(e.target.value))} />
          <span className="text-sm text-slate-400">days</span>
          <Btn tone="green" onClick={() => run(() => api.vip(code, { action: "activate", days }))}>Activate VIP</Btn>
          <Btn onClick={() => run(() => api.vip(code, { action: "extend", days }))}>Extend</Btn>
          <Btn onClick={() => run(() => api.vip(code, { action: "activate", lifetime: true }), "Give lifetime VIP?")}>Lifetime</Btn>
          <Btn tone="red" onClick={() => run(() => api.vip(code, { action: "cancel" }), "Cancel VIP?")}>Cancel VIP</Btn>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input className={`${inputCls} max-w-xs`} placeholder="Block reason (optional)" value={reason} onChange={(e) => setReason(e.target.value)} />
          {u.is_blocked
            ? <Btn tone="green" onClick={() => run(() => api.block(code, false))}>Unblock</Btn>
            : <Btn tone="red" onClick={() => run(() => api.block(code, true, reason || undefined), "Block this user?")}>Block</Btn>}
          <Btn onClick={() => run(() => api.resetQuota(code), "Reset this cycle's free time?")}>Reset free time</Btn>
        </div>
        {msg && <p className="mt-2 text-sm text-slate-300">{msg}</p>}
      </Card>

      <Card title="Recent sessions">
        <div className="overflow-x-auto"><table className="w-full text-left text-sm">
          <thead className="text-slate-400"><tr><th className="py-1">Server</th><th>Started</th><th>Ended</th><th>Counted</th><th>Reason</th></tr></thead>
          <tbody>{u.sessions.map((s) => (
            <tr key={s.id} className="border-t border-slate-800"><td className="py-1.5">{s.server_name} {s.was_vip && <Badge tone="amber">VIP</Badge>}</td>
              <td>{fmtDate(s.started_at)}</td><td>{fmtDate(s.ended_at)}</td><td>{fmtDur(s.counted_seconds)}</td><td>{s.end_reason ?? "active"}</td></tr>
          ))}</tbody></table></div>
      </Card>

      <Card title="VIP history">
        <ul className="space-y-1 text-sm">
          {u.vip_events.map((e, i) => (
            <li key={i}>{fmtDate(e.created_at)} — <b>{e.action}</b>{e.lifetime ? " (lifetime)" : e.days ? ` ${e.days}d` : ""} by {e.admin_username}</li>
          ))}
          {u.vip_events.length === 0 && <li className="text-slate-500">None.</li>}
        </ul>
      </Card>
    </div>
  );
}
