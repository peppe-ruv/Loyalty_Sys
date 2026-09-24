"use client";

import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import type { ActionType } from "@/lib/actiontypes/types";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";

// BO-28 Simulatore eventi (docs/08 §BO-28, F-DEMO-03): invia un'azione vera nella pipeline. M6.7: `?type=` (da *Prova*
// in BO-09) sceglie il tipo e precompila i dati col suo `sampleData`; i tipi custom sono inviabili come gli altri.
interface FireResult {
  eventId: string;
  correlationId: string;
  status: string;
  rejectCode: string | null;
}

const SHORTCUTS: { label: string; type: string; data: string }[] = [
  { label: "Acquisto 130 €", type: "purchase.completed", data: '{ "orderId": "ORD-DEMO", "amount": 130, "currency": "EUR", "channel": "ONLINE" }' },
  { label: "Attiva bolletta digitale", type: "ebill.activated", data: '{ "contractId": "CTR-DEMO" }' },
  { label: "Accesso quotidiano", type: "app.login.daily", data: '{ "platform": "IOS" }' },
  { label: "Dati mancanti (rifiuto)", type: "purchase.completed", data: '{ "orderId": "ORD-X", "currency": "EUR" }' },
];

export default function SimulatorPage() {
  const types = useLhQuery<ActionType[]>("ingestion", "/v1/event-types");
  const requested = useSearchParams().get("type");
  const [memberId, setMemberId] = useState("MBR-000003");
  const [type, setType] = useState(requested ?? "purchase.completed");
  const [dataText, setDataText] = useState(requested ? "{}" : SHORTCUTS[0].data);
  const prefilled = useRef(false);

  // Tipo arrivato da BO-09: dati d'esempio appena i tipi sono caricati (una volta sola).
  useEffect(() => {
    if (prefilled.current || !requested || !types.data) return;
    const t = types.data.find((x) => x.code === requested);
    if (t) setDataText(JSON.stringify(t.sampleData ?? {}, null, 2));
    prefilled.current = true;
  }, [requested, types.data]);

  function chooseType(code: string) {
    setType(code);
    const t = types.data?.find((x) => x.code === code);
    if (t) setDataText(JSON.stringify(t.sampleData ?? {}, null, 2));
  }
  const [count, setCount] = useState(1);
  const [results, setResults] = useState<FireResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function fire() {
    setError(null);
    setResults(null);
    setPending(true);
    try {
      const data = JSON.parse(dataText);
      const res = await lhFetch<FireResult[]>("ingestion", "/v1/demo/simulator/fire", {
        method: "POST",
        body: JSON.stringify({ memberId, type, data, count }),
      });
      setResults(res);
    } catch (e) {
      setError(e instanceof LhError ? e.detail || e.code : "Dati non validi (JSON)");
    } finally {
      setPending(false);
    }
  }

  return (
    <div>
      <PageHeader title="Simulatore eventi" subtitle="Invia un'azione nella pipeline e osservane l'esito" />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardBody className="space-y-3 pt-4">
            <div className="flex flex-wrap gap-2">
              {SHORTCUTS.map((s) => (
                <button
                  key={s.label}
                  onClick={() => {
                    setType(s.type);
                    setDataText(s.data);
                  }}
                  className="rounded-full bg-slate-100 px-2.5 py-1 text-xs hover:bg-slate-200"
                >
                  {s.label}
                </button>
              ))}
            </div>
            <label className="block text-xs">
              Membro
              <input value={memberId} onChange={(e) => setMemberId(e.target.value)} className={input} />
            </label>
            <label className="block text-xs">
              Tipo azione
              <select value={type} onChange={(e) => chooseType(e.target.value)} className={input}>
                {(types.data ?? []).filter((t) => t.enabled || t.code === type).map((t) => (
                  <option key={t.code} value={t.code}>
                    {t.code}
                    {t.origin === "CUSTOM" ? " (custom)" : ""}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-xs">
              Dati (JSON)
              <textarea value={dataText} onChange={(e) => setDataText(e.target.value)} rows={6} className={input + " font-mono"} />
            </label>
            <label className="block text-xs">
              Ripetizioni
              <input type="number" min={1} max={20} value={count} onChange={(e) => setCount(Number(e.target.value))} className={input} />
            </label>
            {error ? <p className="text-xs text-red-700">{error}</p> : null}
            <Can capability="demo.simulate" mode="disable">
              <button onClick={fire} disabled={pending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50">
                {pending ? "Invio…" : "Invia"}
              </button>
            </Can>
          </CardBody>
        </Card>
        <Card>
          <CardBody className="pt-4">
            <h3 className="mb-2 text-sm font-semibold">Esiti</h3>
            {results ? (
              <ul className="space-y-1 text-sm">
                {results.map((r) => (
                  <li key={r.eventId} className="flex items-center justify-between">
                    <CodeText>{r.eventId.slice(0, 10)}…</CodeText>
                    <span className="flex items-center gap-2">
                      <StatusPill status={r.status} />
                      {r.rejectCode ? <span className="text-xs text-red-700">{r.rejectCode}</span> : null}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-[var(--color-bo-ink-2)]">Invia un evento per vederne l&apos;esito qui.</p>
            )}
          </CardBody>
        </Card>
      </div>
    </div>
  );
}

const input = "mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm";
