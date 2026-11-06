import { useState } from "react";
import { api, type Server, type ServerInput } from "../api";
import { Badge, Btn, Card, inputCls, useAsync } from "../ui";

const empty: ServerInput = { name: "", country_code: "DE", country_name_en: "Germany", country_name_fa: "آلمان", ovpn_config: "", auth_username: "", auth_password: "", is_active: true, sort_order: 0 };

export default function Servers() {
  const { data, error, reload } = useAsync(api.servers, []);
  const [editing, setEditing] = useState<{ id: number | null; form: ServerInput } | null>(null);
  const [msg, setMsg] = useState("");
  const [mt, setMt] = useState({ on: false, api_url: "", username: "", password: "", ppp_profile: "", ca_pem: "" });

  const edit = (s: Server) => {
    setMt({ on: !!s.mikrotik, api_url: s.mikrotik?.api_url ?? "", username: s.mikrotik?.username ?? "", password: "", ppp_profile: s.mikrotik?.ppp_profile ?? "", ca_pem: "" });
    setEditing({
    id: s.id,
    form: { name: s.name, country_code: s.country_code, country_name_en: s.country_name_en, country_name_fa: s.country_name_fa,
      ovpn_config: s.ovpn_config, auth_username: s.auth_username ?? "", auth_password: "", is_active: s.is_active, sort_order: s.sort_order },
    });
  };
  const save = async () => {
    if (!editing) return;
    const f: ServerInput = { ...editing.form, auth_username: editing.form.auth_username || null, auth_password: editing.form.auth_password || null };
    if (mt.on) f.mikrotik = { api_url: mt.api_url, username: mt.username, password: mt.password || null, ppp_profile: mt.ppp_profile, ca_pem: mt.ca_pem || null };
    else f.clear_mikrotik = true;
    try {
      if (editing.id === null) await api.createServer(f); else await api.updateServer(editing.id, f);
      setEditing(null); setMsg(""); reload();
    } catch (e) { setMsg((e as Error).message); }
  };
  const remove = async (s: Server) => {
    if (!confirm(`Delete server ${s.name}?`)) return;
    try { await api.deleteServer(s.id); reload(); } catch (e) { setMsg((e as Error).message); }
  };
  const set = (k: keyof ServerInput, v: string | boolean | number) => editing && setEditing({ ...editing, form: { ...editing.form, [k]: v } });

  return (
    <div className="space-y-4">
      <Card title="Servers" right={<Btn tone="green" onClick={() => { setMt({ on: false, api_url: "", username: "", password: "", ppp_profile: "", ca_pem: "" }); setEditing({ id: null, form: empty }); }}>Add server</Btn>}>
        {error && <p className="text-rose-400">{error}</p>}
        <table className="w-full text-left text-sm">
          <thead className="text-slate-400"><tr><th className="py-1">Name</th><th>Country</th><th>Endpoint</th><th>Connections</th><th>State</th><th /></tr></thead>
          <tbody>{data?.map((s) => (
            <tr key={s.id} className="border-t border-slate-800">
              <td className="py-1.5">{s.name}</td><td>{s.country_code} · {s.country_name_en}</td><td>{s.host}:{s.port}/{s.proto}</td>
              <td>{s.active_connections}</td>
              <td className="space-x-1">{s.is_active ? <Badge tone="green">active</Badge> : <Badge tone="slate">hidden</Badge>}{s.mikrotik ? <Badge tone="green">per-session accounts</Badge> : s.has_credentials && <Badge tone="amber">shared credentials</Badge>}</td>
              <td className="space-x-1 text-right"><Btn onClick={() => edit(s)}>Edit</Btn><Btn tone="red" onClick={() => remove(s)}>Delete</Btn></td>
            </tr>))}</tbody>
        </table>
      </Card>
      {editing && (
        <Card title={editing.id === null ? "New server" : "Edit server"}>
          <div className="grid gap-2 md:grid-cols-2">
            <input className={inputCls} placeholder="Name" value={editing.form.name} onChange={(e) => set("name", e.target.value)} />
            <input className={inputCls} placeholder="Country code (DE)" maxLength={2} value={editing.form.country_code} onChange={(e) => set("country_code", e.target.value)} />
            <input className={inputCls} placeholder="Country (English)" value={editing.form.country_name_en} onChange={(e) => set("country_name_en", e.target.value)} />
            <input className={inputCls} dir="rtl" placeholder="کشور (فارسی)" value={editing.form.country_name_fa} onChange={(e) => set("country_name_fa", e.target.value)} />
            <input className={inputCls} placeholder="Auth username (optional)" autoComplete="off" value={editing.form.auth_username ?? ""} onChange={(e) => set("auth_username", e.target.value)} />
            <input className={inputCls} type="password" autoComplete="new-password" placeholder={editing.id === null ? "Auth password (optional)" : "New password (leave empty to keep)"} value={editing.form.auth_password ?? ""} onChange={(e) => set("auth_password", e.target.value)} />
            <input className={inputCls} type="number" placeholder="Sort order" value={editing.form.sort_order} onChange={(e) => set("sort_order", Number(e.target.value))} />
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={editing.form.is_active} onChange={(e) => set("is_active", e.target.checked)} /> Visible to users</label>
          </div>
          <label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={mt.on} onChange={(e) => setMt({ ...mt, on: e.target.checked })} /> MikroTik: create a separate account for every connection (recommended)</label>
          {mt.on && (
            <div className="mt-2 grid gap-2 md:grid-cols-2">
              <input className={inputCls} placeholder="REST URL (https://1.2.3.4:8443)" value={mt.api_url} onChange={(e) => setMt({ ...mt, api_url: e.target.value })} />
              <input className={inputCls} placeholder="PPP profile (e.g. fg-ovpn)" value={mt.ppp_profile} onChange={(e) => setMt({ ...mt, ppp_profile: e.target.value })} />
              <input className={inputCls} placeholder="API user" autoComplete="off" value={mt.username} onChange={(e) => setMt({ ...mt, username: e.target.value })} />
              <input className={inputCls} type="password" autoComplete="new-password" placeholder="API password (leave empty to keep)" value={mt.password} onChange={(e) => setMt({ ...mt, password: e.target.value })} />
              <textarea className={`${inputCls} h-24 font-mono text-xs md:col-span-2`} placeholder="Router certificate (PEM) for a self-signed certificate — optional" value={mt.ca_pem} onChange={(e) => setMt({ ...mt, ca_pem: e.target.value })} />
            </div>
          )}
          <textarea className={`${inputCls} mt-2 h-48 font-mono text-xs`} placeholder="OpenVPN profile (.ovpn) — credentials inside <auth-user-pass> are moved to the encrypted fields by the CLI importer; paste the profile without secrets here"
            value={editing.form.ovpn_config} onChange={(e) => set("ovpn_config", e.target.value)} />
          {msg && <p className="mt-2 text-sm text-rose-400">{msg}</p>}
          <div className="mt-3 flex gap-2"><Btn tone="green" onClick={save}>Save</Btn><Btn onClick={() => { setEditing(null); setMsg(""); }}>Cancel</Btn></div>
        </Card>
      )}
    </div>
  );
}
