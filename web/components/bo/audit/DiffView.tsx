"use client";

// DiffView (docs/08 §BO-22): confronto campo per campo tra before e after (solo i campi cambiati, docs/05 §6).
// Aggiunto = solo after; rimosso = solo before; modificato = entrambi.

export function DiffView({
  before,
  after,
}: {
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
}) {
  const keys = Array.from(new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})])).sort();
  if (keys.length === 0) {
    return <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun campo registrato.</p>;
  }
  return (
    <table className="w-full text-xs">
      <thead className="text-left text-[var(--color-bo-ink-2)]">
        <tr>
          <th className="py-1 pr-2 font-medium">Campo</th>
          <th className="py-1 pr-2 font-medium">Prima</th>
          <th className="py-1 font-medium">Dopo</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-[var(--color-bo-border)]">
        {keys.map((k) => {
          const b = before?.[k];
          const a = after?.[k];
          const added = (before == null || !(k in before)) && after != null && k in after;
          const removed = (after == null || !(k in after)) && before != null && k in before;
          return (
            <tr key={k}>
              <td className="py-1 pr-2 font-mono text-[var(--color-bo-ink)]">{k}</td>
              <td className={`py-1 pr-2 tabular-nums ${removed ? "text-red-700" : "text-[var(--color-bo-ink-2)]"}`}>
                {added ? <span className="text-[var(--color-bo-ink-2)]">—</span> : <Val v={b} strike={removed} />}
              </td>
              <td className={`py-1 tabular-nums ${added ? "text-emerald-700" : "text-[var(--color-bo-ink)]"}`}>
                {removed ? <span className="text-[var(--color-bo-ink-2)]">—</span> : <Val v={a} />}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function Val({ v, strike }: { v: unknown; strike?: boolean }) {
  const text = v == null ? "null" : typeof v === "object" ? JSON.stringify(v) : String(v);
  return <span className={strike ? "line-through" : ""}>{text}</span>;
}
