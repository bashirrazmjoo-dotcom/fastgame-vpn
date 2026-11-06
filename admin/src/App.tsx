import { useEffect, useState } from "react";
import { api, getToken, setToken, setUnauthorizedHandler } from "./api";
import { Btn, inputCls } from "./ui";
import Dashboard from "./pages/Dashboard";
import Users from "./pages/Users";
import UserDetail from "./pages/UserDetail";
import Servers from "./pages/Servers";

function useHash() {
  const [h, setH] = useState(location.hash || "#/");
  useEffect(() => {
    const f = () => setH(location.hash || "#/");
    window.addEventListener("hashchange", f);
    return () => window.removeEventListener("hashchange", f);
  }, []);
  return h;
}

function Login({ onDone }: { onDone: () => void }) {
  const [u, setU] = useState(""), [p, setP] = useState(""), [err, setErr] = useState(""), [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true); setErr("");
    try { setToken((await api.login(u, p)).access_token); onDone(); }
    catch (x) { setErr((x as Error).message); } finally { setBusy(false); }
  };
  return (
    <form onSubmit={submit} className="mx-auto mt-24 w-full max-w-sm space-y-3 rounded-xl border border-slate-800 bg-slate-900 p-6">
      <h1 className="text-xl font-bold">FastGame Admin</h1>
      <input className={inputCls} placeholder="Username" autoComplete="username" value={u} onChange={(e) => setU(e.target.value)} />
      <input className={inputCls} placeholder="Password" type="password" autoComplete="current-password" value={p} onChange={(e) => setP(e.target.value)} />
      {err && <p className="text-sm text-rose-400">{err}</p>}
      <Btn tone="green" disabled={busy || !u || !p} className="w-full">Sign in</Btn>
    </form>
  );
}

export default function App() {
  const [authed, setAuthed] = useState(!!getToken());
  const hash = useHash();
  useEffect(() => setUnauthorizedHandler(() => { setToken(null); setAuthed(false); }), []);
  if (!authed) return <Login onDone={() => setAuthed(true)} />;

  const [, route, arg] = hash.split("/");
  const link = (to: string, label: string) => (
    <a href={`#/${to}`} className={`rounded-lg px-3 py-1.5 text-sm ${route === to || (to === "" && !route) ? "bg-slate-700" : "hover:bg-slate-800"}`}>{label}</a>
  );
  return (
    <div className="mx-auto max-w-6xl p-4">
      <header className="mb-4 flex flex-wrap items-center gap-2">
        <strong className="mr-4">FastGame Admin</strong>
        {link("", "Dashboard")}{link("users", "Users")}{link("servers", "Servers")}
        <Btn className="ml-auto" onClick={() => { setToken(null); setAuthed(false); }}>Sign out</Btn>
      </header>
      {route === "users" && arg ? <UserDetail code={decodeURIComponent(arg)} /> : route === "users" ? <Users /> : route === "servers" ? <Servers /> : <Dashboard />}
    </div>
  );
}
