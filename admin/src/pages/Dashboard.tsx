import { useEffect } from "react";
import { api } from "../api";
import { Badge, Card, fmtDate, fmtDur, useAsync } from "../ui";

export default function Dashboard() {
  const stats = useAsync(api.stats, []);
  const active = useAsync(api.activeSessions, []);
  useEffect(() => {
    const t = setInterval(() => { stats.reload(); active.reload(); }, 15000);
    return () => clearInterval(t);
  });
  const s = stats.data;
  const tiles: [string, string | number][] = s ? [
    ["Users", s.total_devices], ["VIP", s.vip_devices], ["Blocked", s.blocked_devices],
    ["New (24h)", s.new_devices_24h], ["Connected now", s.active_sessions],
    ["Sessions (24h)", s.sessions_started_24h], ["Usage (24h)", fmtDur(s.usage_seconds_24h)],
  ] : [];
  return (
    <div className="space-y-4">
      {stats.error && <p className="text-rose-400">{stats.error}</p>}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {tiles.map(([k, v]) => (
          <div key={k} className="rounded-xl border border-slate-800 bg-slate-900 p-4">
            <div className="text-sm text-slate-400">{k}</div>
            <div className="text-2xl font-bold">{v}</div>
          </div>
        ))}
      </div>
      <Card title="Active connections">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-slate-400"><tr><th className="py-1">User</th><th>Server</th><th>Started</th><th>Last heartbeat</th><th>Counted</th><th /></tr></thead>
            <tbody>
              {active.data?.map((a) => (
                <tr key={a.session_id} className="border-t border-slate-800">
                  <td className="py-1.5"><a className="text-emerald-400" href={`#/users/${a.user_code}`}>{a.user_code}</a></td>
                  <td>{a.server_name}</td><td>{fmtDate(a.started_at)}</td><td>{fmtDate(a.last_heartbeat_at)}</td>
                  <td>{fmtDur(a.counted_seconds)}</td><td>{a.was_vip && <Badge tone="amber">VIP</Badge>}</td>
                </tr>
              ))}
              {active.data?.length === 0 && <tr><td colSpan={6} className="py-3 text-slate-500">No active connections.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
