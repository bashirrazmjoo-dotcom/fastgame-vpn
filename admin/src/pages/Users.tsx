import { useState } from "react";
import { api } from "../api";
import { Badge, Btn, Card, fmtDate, fmtDur, inputCls, useAsync } from "../ui";

const PAGE = 25;

export default function Users() {
  const [q, setQ] = useState(""), [status, setStatus] = useState("all"), [offset, setOffset] = useState(0);
  const [applied, setApplied] = useState("");
  const list = useAsync(() => api.devices({ q: applied, status, limit: PAGE, offset }), [applied, status, offset]);
  const d = list.data;
  return (
    <Card title="Users">
      <form className="mb-3 flex flex-wrap gap-2" onSubmit={(e) => { e.preventDefault(); setOffset(0); setApplied(q.trim()); }}>
        <input className={`${inputCls} max-w-xs`} placeholder="Search by user ID" value={q} onChange={(e) => setQ(e.target.value)} />
        <select className={`${inputCls} max-w-[10rem]`} value={status} onChange={(e) => { setOffset(0); setStatus(e.target.value); }}>
          {["all", "vip", "free", "blocked"].map((s) => <option key={s}>{s}</option>)}
        </select>
        <Btn>Search</Btn>
      </form>
      {list.error && <p className="text-rose-400">{list.error}</p>}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-slate-400"><tr><th className="py-1">ID</th><th>Plan</th><th>Left today</th><th>Total used</th><th>Last seen</th><th>Created</th><th /></tr></thead>
          <tbody>
            {d?.items.map((u) => (
              <tr key={u.id} className="border-t border-slate-800">
                <td className="py-1.5"><a className="text-emerald-400" href={`#/users/${u.user_code}`}>{u.user_code}</a></td>
                <td className="space-x-1">
                  {u.is_vip ? <Badge tone="amber">{u.vip_lifetime ? "VIP ∞" : "VIP"}</Badge> : <Badge tone="slate">Free</Badge>}
                  {u.is_blocked && <Badge tone="red">Blocked</Badge>}
                </td>
                <td>{u.is_vip ? "∞" : fmtDur(u.quota.remaining_seconds)}</td>
                <td>{fmtDur(u.total_used_seconds)}</td><td>{fmtDate(u.last_seen_at)}</td><td>{fmtDate(u.created_at)}</td>
                <td>{u.has_active_session && <Badge tone="green">online</Badge>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {d && (
        <div className="mt-3 flex items-center gap-2 text-sm text-slate-400">
          <Btn disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Prev</Btn>
          <span>{d.total === 0 ? 0 : offset + 1}–{Math.min(offset + PAGE, d.total)} / {d.total}</span>
          <Btn disabled={offset + PAGE >= d.total} onClick={() => setOffset(offset + PAGE)}>Next</Btn>
        </div>
      )}
    </Card>
  );
}
