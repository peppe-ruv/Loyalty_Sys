import Link from "next/link";
import { safeReturnTo } from "@/lib/auth/returnTo";

// Stato di errore del login (profilo enterprise, docs/07 §4-bis e §6): perché non è andata e «Riprova».
// Il motivo arriva da lib/auth/handlers.ts come codice chiuso; qualunque altro valore mostra il testo generico.

export const dynamic = "force-dynamic";

const REASONS: Record<string, { title: string; detail: string }> = {
  expired: {
    title: "Accesso scaduto",
    detail: "Il tentativo di accesso è durato troppo o è stato aperto in un'altra scheda. Riprova da qui.",
  },
  denied: {
    title: "Accesso annullato",
    detail: "L'accesso è stato annullato nella pagina del fornitore di identità.",
  },
  rejected: {
    title: "Accesso non riuscito",
    detail: "La risposta del fornitore di identità non è valida. Riprova; se succede ancora, contatta l'amministratore.",
  },
  logout_failed: {
    title: "Uscita non riuscita",
    detail: "La richiesta di uscita non è stata riconosciuta: ricarica la pagina e premi di nuovo «Esci».",
  },
  idp_unavailable: {
    title: "Servizio di accesso non raggiungibile",
    detail: "Il fornitore di identità non risponde. Riprova tra qualche istante.",
  },
};

export default async function AuthErrorPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const reason = typeof params.reason === "string" ? params.reason : "";
  const returnTo = safeReturnTo(typeof params.returnTo === "string" ? params.returnTo : null);
  const text = REASONS[reason] ?? REASONS.rejected;

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <div role="alert" className="space-y-2">
        <h1 className="text-lg font-semibold">{text.title}</h1>
        <p className="text-sm text-[var(--color-bo-ink-2)]">{text.detail}</p>
      </div>
      <div className="flex gap-2">
        {/* Navigazione a pagina intera verso un route handler: niente <Link> (prefetch di un endpoint di login). */}
        <a
          href={`/api/auth/login?returnTo=${encodeURIComponent(returnTo)}`}
          className="rounded-md bg-[var(--color-bo-accent)] px-4 py-2 text-sm font-semibold text-white"
        >
          Riprova
        </a>
        <Link href="/" className="rounded-md border border-[var(--color-bo-border)] px-4 py-2 text-sm font-semibold">
          Torna all&apos;inizio
        </Link>
      </div>
    </main>
  );
}
