import { useEffect, useState, type ReactNode } from "react";

export const fmtDur = (s: number) => {
  s = Math.max(0, Math.floor(s));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return `${h}h ${String(m).padStart(2, "0")}m`;
};
export const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "—");

export const Card = ({ title, children, right }: { title?: string; children: ReactNode; right?: ReactNode }) => (
  <section className="rounded-xl border border-slate-800 bg-slate-900 p-4">
    {(title || right) && (
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-semibold">{title}</h2>
        {right}
      </div>
    )}
    {children}
  </section>
);

export const Badge = ({ tone, children }: { tone: "green" | "red" | "amber" | "slate"; children: ReactNode }) => {
  const c = {
    green: "bg-emerald-500/15 text-emerald-300", red: "bg-rose-500/15 text-rose-300",
    amber: "bg-amber-500/15 text-amber-300", slate: "bg-slate-700/50 text-slate-300",
  }[tone];
  return <span className={`rounded-full px-2 py-0.5 text-xs ${c}`}>{children}</span>;
};

export const Btn = ({ tone = "slate", ...p }: React.ButtonHTMLAttributes<HTMLButtonElement> & { tone?: "slate" | "green" | "red" }) => {
  const c = { slate: "bg-slate-700 hover:bg-slate-600", green: "bg-emerald-600 hover:bg-emerald-500", red: "bg-rose-600 hover:bg-rose-500" }[tone];
  return <button {...p} className={`rounded-lg px-3 py-1.5 text-sm disabled:opacity-50 ${c} ${p.className ?? ""}`} />;
};

export const inputCls = "w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-sm outline-none focus:border-emerald-500";

export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let live = true;
    fn().then((d) => live && (setData(d), setError(null))).catch((e) => live && setError(e.message));
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, reload: () => setTick((t) => t + 1) };
}
