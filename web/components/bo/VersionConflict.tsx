"use client";

// Avviso di conflitto di versione (M7.6; docs/08 §editor: "Qualcun altro ha modificato: ricarica / sovrascrivi").
export function VersionConflict({
  what,
  busy,
  onReload,
  onOverwrite,
}: {
  /** Oggetto modificato, es. "il premio". */
  what: string;
  busy?: boolean;
  onReload: () => void;
  onOverwrite: () => void;
}) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-2 rounded border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
    >
      <span className="mr-auto">
        Qualcun altro ha modificato {what} nel frattempo.
      </span>
      <button
        type="button"
        onClick={onReload}
        disabled={busy}
        className="rounded border border-amber-400 bg-white px-2.5 py-1 text-xs font-medium hover:bg-amber-100 disabled:opacity-50"
      >
        Ricarica
      </button>
      <button
        type="button"
        onClick={onOverwrite}
        disabled={busy}
        className="rounded bg-amber-600 px-2.5 py-1 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50"
      >
        Sovrascrivi
      </button>
    </div>
  );
}
