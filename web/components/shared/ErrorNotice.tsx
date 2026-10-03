"use client";

import { useRef, useState } from "react";
import type { LhError } from "@/lib/api/client";
import { it } from "@/lib/i18n/it";

// Codice dell'errore (F2-QA-06, ADR-052 decisione 3, Q-684, Q-716; docs/07 §6 Error). Un guasto (5xx o 4xx inatteso) mostra
// il codice che il servizio ha scritto anche nel suo log (`correlationId` del problema RFC 9457, fallback intestazione
// `X-Correlation-Id`, già in lhFetch): chi fa il collaudo lo cerca nei log. Un errore di validazione dei campi (422 con
// `errors[]`) NON è un guasto: resta sui campi, senza codice (B1). Backoffice e portale usano questi stessi pezzi
// (non si importano a vicenda, CLAUDE.md §5): il portale ne usa la versione piccola (C1, `InlineErrorCode`).

const t = it.errorCode;

/** Il codice da mostrare: quello dell'errore, solo se è un guasto (nessun errore di campo) e il codice c'è (B1). */
export function errorCodeOf(error: LhError): string | null {
  return error.correlationId && error.errors.length === 0 ? error.correlationId : null;
}

/** Copia negli appunti; se non si può, seleziona il testo del codice per la copia a mano. Annuncia l'esito ai lettori di schermo. */
function useCopyCode(code: string) {
  const codeRef = useRef<HTMLElement>(null);
  const [announce, setAnnounce] = useState("");
  const copy = () => {
    const fallback = () => {
      const el = codeRef.current;
      const selection = typeof window !== "undefined" ? window.getSelection() : null;
      if (!el || !selection) return setAnnounce("");
      const range = document.createRange();
      range.selectNodeContents(el);
      selection.removeAllRanges();
      selection.addRange(range);
      setAnnounce(t.selected);
    };
    try {
      const write = navigator.clipboard?.writeText(code);
      if (!write) return fallback();
      void write.then(() => setAnnounce(t.copied), fallback);
    } catch {
      fallback();
    }
  };
  return { codeRef, announce, copy };
}

/** Conferma della copia: sempre presente nel DOM, così il lettore di schermo la annuncia quando il testo compare. */
function CopyStatus({ message, visible }: { message: string; visible?: boolean }) {
  return (
    <span role="status" aria-live="polite" className={visible && message ? "text-xs" : "sr-only"}>
      {message}
    </span>
  );
}

/** Riga «Codice dell'errore» + pulsante «Copia il codice» (backoffice). */
function ErrorCodeRow({ code, retry }: { code: string; retry?: React.ReactNode }) {
  const { codeRef, announce, copy } = useCopyCode(code);
  return (
    <>
      <p className="mt-1 flex flex-wrap items-center gap-1.5 text-xs">
        <span>{t.label}</span>
        <code ref={codeRef} className="select-all break-all rounded bg-red-100 px-1.5 py-0.5 font-mono">
          {code}
        </code>
      </p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        {retry}
        <button
          type="button"
          onClick={copy}
          className="rounded border border-red-300 px-2 py-1 text-xs hover:bg-red-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--color-bo-accent)]"
        >
          {t.copy}
        </button>
        <CopyStatus message={announce} visible />
      </div>
    </>
  );
}

const RETRY_CLASS =
  "rounded border border-red-800 bg-red-800 px-2 py-1 text-xs font-medium text-white hover:bg-red-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--color-bo-accent)]";

/**
 * Errore nel caricare una vista (docs/07 §6 Error). 5xx: titolo che nomina il servizio e suggerimento fisso (il `detail` del
 * servizio, generico, non aggiunge nulla); 4xx inatteso: `title` e `detail` del problema. Poi «Codice dell'errore»,
 * «Riprova» (primario) e «Copia il codice».
 */
export function ErrorBox({ error, service, onRetry }: { error: LhError; service?: string; onRetry: () => void }) {
  const serverFault = error.status >= 500 && service !== undefined;
  const title = serverFault ? t.serviceTitle(service) : (error.title ?? `Errore: ${error.code}`);
  const detail = serverFault ? t.serviceHint : error.detail;
  const code = errorCodeOf(error);
  const retry = (
    <button type="button" onClick={onRetry} className={RETRY_CLASS}>
      {t.retry}
    </button>
  );
  return (
    <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
      <p className="font-medium">{title}</p>
      {detail && detail !== title ? <p className="mt-1 text-xs">{detail}</p> : null}
      {code ? <ErrorCodeRow code={code} retry={retry} /> : <div className="mt-2">{retry}</div>}
    </div>
  );
}

/**
 * Errore di un'azione (salvataggio, invio) in testa al modulo: stesso aspetto dell'errore di vista. I campi restano
 * compilati (chi lo usa non azzera lo stato). Un errore di campo (422 con `errors[]`) non è un guasto: niente codice,
 * solo il dettaglio; i messaggi per campo li mostra il modulo sui campi.
 */
export function ActionError({ error, title, detail }: { error: LhError; title: string; detail?: string }) {
  const code = errorCodeOf(error);
  const shown = detail ?? (code && error.status >= 500 ? t.actionHint : error.detail || t.actionHint);
  return (
    <div role="alert" data-testid="action-error" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
      <p className="font-medium">{title}</p>
      {shown && shown !== title ? <p className="mt-1 text-xs">{shown}</p> : null}
      {code ? <ErrorCodeRow code={code} /> : null}
    </div>
  );
}

/** Codice in piccolo sotto il messaggio, con «Copia il codice» (portale, C1). Niente se non c'è un codice. */
export function InlineErrorCode({ code, className }: { code: string | null; className?: string }) {
  const { codeRef, announce, copy } = useCopyCode(code ?? "");
  if (!code) return null;
  return (
    <p className={className ?? "mt-1 text-xs opacity-80"}>
      {t.labelShort}{" "}
      <code ref={codeRef} className="select-all break-all font-mono">
        {code}
      </code>
      {" · "}
      <button type="button" onClick={copy} className="underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2">
        {t.copy}
      </button>
      <CopyStatus message={announce} />
    </p>
  );
}
