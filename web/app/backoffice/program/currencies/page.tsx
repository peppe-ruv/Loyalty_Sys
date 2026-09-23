"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, LhError } from "@/lib/api/client";
import type { Currency, Edition, EditionClosePreviewResult, Liability } from "@/lib/api/types";
import { LiabilityColumns } from "@/components/bo/LiabilityColumns";
import { formatPoints } from "@/lib/format/points";
import { Tabs } from "@/components/bo/Tabs";
import { PageHeader, StatusPill, TierBadge } from "@/components/bo/primitives";
import { QueryState } from "@/components/bo/QueryState";
import { Card, CardBody } from "@/components/ui/card";
import { Can } from "@/components/bo/Can";
import { computeRollingExpiry } from "@/lib/format/dates";

const DATE_FORMAT = new Intl.DateTimeFormat("it-IT", {
  day: "numeric",
  month: "short",
  year: "numeric",
  timeZone: "Europe/Rome",
});

export default function CurrenciesPage() {
  const search = useSearchParams();
  const requested = search.get("tab");
  const currentTab = requested === "editions" || requested === "liability" ? requested : "currencies";

  return (
    <div>
      <PageHeader title="Valute, scadenze, edizioni" subtitle="Gestione dei lotti di punti e della discesa morbida a fine anno" />

      <Tabs
        tabs={[
          { key: "currencies", label: "Valute" },
          { key: "editions", label: "Edizioni" },
          { key: "liability", label: "Passività" },
        ]}
        current={currentTab}
      />

      {currentTab === "currencies" ? <CurrenciesTab /> : currentTab === "editions" ? <EditionsTab /> : <LiabilityTab />}
    </div>
  );
}

function CurrenciesTab() {
  const query = useLhQuery<Currency[]>("wallet", "/v1/currencies");

  return (
    <QueryState query={query} service="wallet" isEmpty={(d) => d.length === 0}>
      {(currencies) => (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {currencies.map((currency) => (
            <CurrencyCard key={currency.code} currency={currency} />
          ))}
        </div>
      )}
    </QueryState>
  );
}


function CurrencyCard({ currency }: { currency: Currency }) {
  const qc = useQueryClient();
  const policy = currency.expiryPolicy;

  const [months, setMonths] = useState(policy?.type === "ROLLING_MONTHS" ? String(policy.months) : "");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  async function save() {
    setBusy(true);
    setMsg(null);
    try {
      await lhFetch("wallet", `/v1/currencies/${currency.code}`, {
        method: "PUT",
        body: JSON.stringify({
          expiryPolicy: {
            type: "ROLLING_MONTHS",
            months: parseInt(months, 10),
          },
        }),
      });
      setMsg({ ok: true, text: "Policy salvata." });
      qc.invalidateQueries({ queryKey: ["wallet"] });
    } catch (e) {
      const err = e as LhError;
      setMsg({ ok: false, text: err.detail || err.code || "Errore di salvataggio" });
    } finally {
      setBusy(false);
    }
  }

  let expiryContent = null;

  if (policy?.type === "ROLLING_MONTHS") {
    const exampleDate = computeRollingExpiry(parseInt(months, 10) || 0);

    expiryContent = (
      <div className="mt-4 border-t border-[var(--color-bo-border)] pt-3">
        <p className="text-sm font-medium text-[var(--color-bo-ink)]">Scadenza: ROLLING_MONTHS</p>
        <div className="mt-2 flex items-center gap-2">
          <label className="text-xs text-[var(--color-bo-ink-2)]">Mesi:</label>
          <Can capability="program.config" mode="disable">
            <input
              type="number"
              min="1"
              max="60"
              value={months}
              onChange={(e) => setMonths(e.target.value)}
              className="w-20 rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm"
            />
          </Can>
          <Can capability="program.config" mode="hide">
            <button
              onClick={save}
              disabled={busy || months === String(policy.months)}
              className="rounded bg-[var(--color-bo-accent)] px-2 py-1 text-xs font-medium text-white disabled:opacity-50"
            >
              Salva
            </button>
          </Can>
        </div>
        <p className="mt-2 text-xs italic text-[var(--color-bo-ink-2)]">
          Esempio: Guadagnati oggi → scadono il {DATE_FORMAT.format(exampleDate)}
        </p>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          Vale per i nuovi lotti; i lotti esistenti mantengono la loro scadenza.
        </p>
        {msg ? (
          <p className={`mt-2 text-xs ${msg.ok ? "text-[var(--color-earn)]" : "text-[var(--color-spend)]"}`}>
            {msg.text}
          </p>
        ) : null}
      </div>
    );
  } else if (policy?.type === "END_OF_EDITION_PLUS_GRACE") {
     expiryContent = (
      <div className="mt-4 border-t border-[var(--color-bo-border)] pt-3">
        <p className="text-sm font-medium text-[var(--color-bo-ink)]">Scadenza: END_OF_EDITION_PLUS_GRACE</p>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          I punti scadono alla data di tolleranza dell&apos;edizione in cui sono guadagnati (se non è impostata: fine
          edizione + {policy.graceDays ?? 0} giorni).
        </p>
      </div>
     );
  } else if (policy?.type === "EDITION") {
     expiryContent = (
      <div className="mt-4 border-t border-[var(--color-bo-border)] pt-3">
        <p className="text-sm font-medium text-[var(--color-bo-ink)]">Scadenza: EDITION</p>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          Non scadono a lotto: il saldo del periodo si azzera con la chiusura dell&apos;edizione (discesa morbida).
        </p>
      </div>
     );
  } else if (policy?.type === "NEVER" || !policy) {
     expiryContent = (
      <div className="mt-4 border-t border-[var(--color-bo-border)] pt-3">
        <p className="text-sm font-medium text-[var(--color-bo-ink)]">Scadenza: {policy?.type || "NEVER"}</p>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          Questa valuta non ha una scadenza definita.
        </p>
      </div>
     );
  }

  return (
    <Card>
      <CardBody className="pt-4">
        <div className="flex items-start justify-between">
          <div>
            <h3 className="font-semibold text-[var(--color-bo-ink)]">{currency.name}</h3>
            <p className="mt-0.5 font-mono text-sm text-[var(--color-bo-ink-2)]">{currency.code}</p>
          </div>
          {currency.spendable && (
             <span className="inline-flex rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700 ring-1 ring-inset ring-blue-700/10">
               Spendibile
             </span>
          )}
        </div>
        {expiryContent}
      </CardBody>
    </Card>
  );
}

function EditionsTab() {
  const query = useLhQuery<Edition[]>("wallet", "/v1/editions");
  // Tenuto qui: dopo la chiusura l'edizione non è più ACTIVE e il pannello si smonta.
  const [closed, setClosed] = useState<{ code: string; retained: number; downgraded: number } | null>(null);

  return (
    <QueryState query={query} service="wallet" isEmpty={(d) => d.length === 0}>
      {(editions) => {
        const sorted = [...editions].sort((a, b) => new Date(a.startDate).getTime() - new Date(b.startDate).getTime());
        return (
          <div className="space-y-6">
            {closed && (
              <div className="rounded bg-emerald-50 p-4 text-emerald-800">
                <p className="font-semibold">Edizione {closed.code} chiusa con successo.</p>
                <p className="text-sm">Membri che mantengono il livello: {closed.retained}. Membri scesi di livello: {closed.downgraded}.</p>
              </div>
            )}
            <div className="flex gap-4 overflow-x-auto pb-4">
              {sorted.map((ed) => (
                <EditionCard key={ed.code} edition={ed} />
              ))}
            </div>
            {sorted.map(
              (ed) => ed.status === "ACTIVE" && (
                <EditionClosePanel key={ed.code} edition={ed} onClosed={(r) => setClosed({ code: ed.code, ...r })} />
              )
            )}
          </div>
        );
      }}
    </QueryState>
  );
}

function EditionCard({ edition }: { edition: Edition }) {
  return (
    <Card className="min-w-[250px] shrink-0">
      <CardBody className="pt-4">
        <div className="mb-2 flex items-center justify-between">
          <h3 className="font-semibold text-[var(--color-bo-ink)]">{edition.name}</h3>
          <StatusPill status={edition.status} />
        </div>
        <p className="font-mono text-sm text-[var(--color-bo-ink-2)]">{edition.code}</p>
        <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">
          {DATE_FORMAT.format(new Date(edition.startDate))} — {DATE_FORMAT.format(new Date(edition.endDate))}
        </p>
      </CardBody>
    </Card>
  );
}

function EditionClosePanel({
  edition,
  onClosed,
}: {
  edition: Edition;
  onClosed: (summary: { retained: number; downgraded: number }) => void;
}) {
  const qc = useQueryClient();
  const [preview, setPreview] = useState<EditionClosePreviewResult | null>(null);
  const [filter, setFilter] = useState<"ALL" | "RETAINED" | "DOWNGRADED">("ALL");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [confirmCode, setConfirmCode] = useState("");
  const [showConfirm, setShowConfirm] = useState(false);

  async function loadPreview() {
    setBusy(true);
    setError(null);
    try {
      const res = await lhFetch<EditionClosePreviewResult>("wallet", `/v1/editions/${edition.code}/close?dryRun=true`, { method: "POST" });
      setPreview(res);
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || "Errore durante l'anteprima");
    } finally {
      setBusy(false);
    }
  }

  async function applyClose() {
    if (confirmCode !== edition.code) return;
    setBusy(true);
    setError(null);
    try {
      const res = await lhFetch<EditionClosePreviewResult>("wallet", `/v1/editions/${edition.code}/close?dryRun=false`, { method: "POST" });
      setShowConfirm(false);
      onClosed(res.summary);
      qc.invalidateQueries({ queryKey: ["wallet"] });
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || "Errore durante la chiusura");
    } finally {
      setBusy(false);
    }
  }

  const filteredMembers = preview?.members.filter((m) => filter === "ALL" || m.outcome === filter) || [];

  return (
    <Card className="border-[var(--color-bo-accent)]">
      <CardBody className="pt-4">
        <h3 className="text-lg font-semibold text-[var(--color-bo-ink)]">Chiusura edizione {edition.name}</h3>
        <p className="mt-1 text-sm text-[var(--color-bo-ink-2)]">
          L&apos;edizione è attiva. Puoi simulare la chiusura (dry-run) per vedere l&apos;effetto della discesa morbida sui livelli dei membri.
        </p>

        {error && (
          <div className="mt-4 rounded bg-red-50 p-4 text-sm text-red-800">
            {error}
          </div>
        )}

        <div className="mt-4 flex gap-2">
          <button
            onClick={loadPreview}
            disabled={busy}
            className="rounded border border-[var(--color-bo-border)] px-4 py-2 text-sm font-medium hover:bg-slate-50 disabled:opacity-50"
          >
            Anteprima chiusura
          </button>
          <Can capability="program.config" mode="disable">
            <button
              onClick={() => setShowConfirm(true)}
              disabled={busy}
              className="rounded bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            >
              Applica chiusura ●
            </button>
          </Can>
        </div>

        {showConfirm && (
          <div className="mt-4 rounded border border-red-200 bg-red-50 p-4">
            <p className="text-sm font-semibold text-red-800">Sei sicuro di voler chiudere l&apos;edizione?</p>
            <p className="mt-1 text-xs text-red-700">Questa azione non è reversibile. Digita <strong>{edition.code}</strong> per confermare.</p>
            <div className="mt-3 flex gap-2">
              <input
                value={confirmCode}
                onChange={(e) => setConfirmCode(e.target.value)}
                placeholder={edition.code}
                className="rounded border border-red-300 px-3 py-1.5 text-sm"
              />
              <button
                onClick={applyClose}
                disabled={busy || confirmCode !== edition.code}
                className="rounded bg-red-600 px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50"
              >
                Conferma
              </button>
              <button
                onClick={() => { setShowConfirm(false); setConfirmCode(""); }}
                className="rounded border border-red-200 px-4 py-1.5 text-sm font-medium text-red-800 hover:bg-red-100"
              >
                Annulla
              </button>
            </div>
          </div>
        )}

        {preview && (
          <div className="mt-6 border-t border-[var(--color-bo-border)] pt-4">
            <div className="mb-4 flex items-center justify-between">
              <p className="text-sm font-medium">
                Sintesi: {preview.summary.retained} mantengono il livello, {preview.summary.downgraded} scendono.
              </p>
              <select
                value={filter}
                onChange={(e) => setFilter(e.target.value as "ALL" | "RETAINED" | "DOWNGRADED")}
                className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm"
              >
                <option value="ALL">Tutti</option>
                <option value="RETAINED">Mantengono</option>
                <option value="DOWNGRADED">Scendono</option>
              </select>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]">
                    <th className="py-2 font-medium">Membro</th>
                    <th className="py-2 font-medium">Livello attuale</th>
                    <th className="py-2 font-medium">STS di periodo</th>
                    <th className="py-2 font-medium">Livello guadagnato</th>
                    <th className="py-2 font-medium">Nuovo livello</th>
                    <th className="py-2 font-medium">Esito</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--color-bo-border)]">
                  {filteredMembers.map((m) => (
                    <tr key={m.memberId}>
                      <td className="py-2 font-mono text-xs">{m.memberId}</td>
                      <td className="py-2"><TierBadge tier={m.currentTier} /></td>
                      <td className="py-2 font-mono">{m.periodSts.toLocaleString("it-IT")}</td>
                      <td className="py-2"><TierBadge tier={m.earnedTier} /></td>
                      <td className="py-2"><TierBadge tier={m.newTier} /></td>
                      <td className="py-2">
                        {m.outcome === "RETAINED" ? (
                          <span className="text-emerald-600">Mantiene</span>
                        ) : (
                          <span className="text-red-600">Scende</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </CardBody>
    </Card>
  );
}

// Scheda liability (F-WAL-09): punti in circolazione per valuta e per mese di scadenza.
function LiabilityTab() {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <LiabilityCard currency="PTS" title="Punti (PTS)" />
      <LiabilityCard
        currency="STS"
        title="Punti status (STS)"
        neverNote="Il saldo del periodo si azzera con la chiusura dell'edizione (discesa morbida)."
      />
    </div>
  );
}

function LiabilityCard({ currency, title, neverNote }: { currency: string; title: string; neverNote?: string }) {
  const query = useLhQuery<Liability>("wallet", `/v1/liability?currency=${currency}`);
  return (
    <Card>
      <CardBody className="pt-4">
        <h3 className="font-semibold text-[var(--color-bo-ink)]">{title}</h3>
        <QueryState query={query} service="wallet">
          {(l) => (
            <div className="mt-2 space-y-3">
              <div className="flex gap-6">
                <div>
                  <p className="text-xs text-[var(--color-bo-ink-2)]">In circolazione</p>
                  <p className="text-2xl font-semibold tabular-nums text-[var(--color-bo-ink)]">{formatPoints(l.outstanding)}</p>
                </div>
                <div>
                  <p className="text-xs text-[var(--color-bo-ink-2)]">In attesa</p>
                  <p className="text-2xl font-semibold tabular-nums text-[var(--color-bo-ink-2)]">{formatPoints(l.pending)}</p>
                </div>
              </div>
              <p className="text-xs text-[var(--color-bo-ink-2)]">Per mese di scadenza (prossimi 12 mesi)</p>
              <LiabilityColumns rows={l.byExpiryMonth} unit={currency} neverNote={neverNote} />
            </div>
          )}
        </QueryState>
      </CardBody>
    </Card>
  );
}
