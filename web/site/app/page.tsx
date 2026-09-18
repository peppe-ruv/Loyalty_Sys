import { getInbox, getNextBestAction, getSummary, membroCorrente, vetrina } from "../lib/bff.mjs";

/** La home si costruisce a ogni richiesta: saldo, offerta e messaggi sono per definizione freschi. */
export const dynamic = "force-dynamic";

interface Summary { tier: string; statusPointsYear: number; pointsToNext: number; updatedAt: string; stale?: boolean }
interface NextBestAction { action: string; offerId?: string; reason?: string; metadata?: { params?: { title?: string; body?: string } } }
interface InboxItem { id: string; subject: string; body: string; expires_at?: string | null }

export default async function Home() {
  // In produzione il memberId arriva dal token OIDC (ADR-012). Finché quel pezzo non c'è, con un BFF vero
  // non si inventa un membro: si mostra la pagina di chi non ha ancora fatto accesso.
  const memberId = membroCorrente() as string | null;
  if (memberId === null) {
    return (
      <main style={{ fontFamily: "system-ui", padding: 24, maxWidth: 720 }}>
        <h1>Il tuo programma</h1>
        <p>Accedi per vedere il tuo saldo, le tue offerte e i tuoi messaggi.</p>
        <p style={{ color: "#666", fontSize: 13 }}>
          Questo portale è collegato a un sistema reale: l&apos;identificativo del membro arriva
          dall&apos;autenticazione, non dalla configurazione.
        </p>
      </main>
    );
  }
  const [s, nba, inbox] = await Promise.all([
    getSummary(memberId) as Promise<Summary | null>,
    getNextBestAction(memberId) as Promise<NextBestAction | null>,
    getInbox(memberId) as Promise<InboxItem[]>,
  ]);
  return (
    <main style={{ fontFamily: "system-ui", padding: 24, maxWidth: 720 }}>
      {vetrina() && (
        // Chi guarda deve sapere che cosa sta guardando: senza questo avviso la vetrina passa per
        // un ambiente vero e un saldo inventato sembra un saldo.
        <p role="status" style={{ background: "#fff4d6", border: "1px solid #e0b64a", borderRadius: 8, padding: "10px 14px", fontSize: 13, marginTop: 0 }}>
          <strong>Dati dimostrativi.</strong> Questo portale non è collegato a nessun sistema: saldo,
          offerta e messaggi sono inventati e il membro è un identificatore opaco. Con{" "}
          <code>BFF_URL</code> configurato mostra invece i dati veri.
        </p>
      )}
      <h1>Il tuo programma</h1>
      {s ? (
        <section>
          <p>Tier <strong>{s.tier}</strong> · {s.statusPointsYear} punti status quest&apos;anno · {s.pointsToNext} al prossimo livello</p>
          <p style={{ color: "#666", fontSize: 13 }}>Aggiornato alle {new Date(s.updatedAt).toLocaleTimeString("it-IT")}{s.stale ? " (dati non aggiornati)" : ""}</p>
        </section>
      ) : (
        <p>Il saldo non è disponibile in questo momento. Riprova tra poco.</p>
      )}
      {nba && nba.action !== "NO_ACTION" && (
        <section aria-label="Per te" style={{ border: "1px solid #ddd", borderRadius: 8, padding: 16, marginTop: 16 }}>
          <h2 style={{ fontSize: 18, margin: 0 }}>{nba.metadata?.params?.title ?? "Un'offerta per te"}</h2>
          <p style={{ margin: "8px 0" }}>{nba.metadata?.params?.body ?? nba.offerId}</p>
          <p style={{ color: "#666", fontSize: 12 }}>Perché la vedi: {nba.reason}</p>
        </section>
      )}
      {inbox.length > 0 && (
        <section aria-label="Messaggi" style={{ marginTop: 16 }}>
          <h2 style={{ fontSize: 18 }}>Messaggi e offerte</h2>
          <ul style={{ paddingLeft: 18 }}>
            {inbox.map((m) => (
              <li key={m.id} style={{ marginBottom: 8 }}>
                <strong>{m.subject}</strong> — {m.body}
                {m.expires_at && <span style={{ color: "#666", fontSize: 12 }}> (fino al {new Date(m.expires_at).toLocaleDateString("it-IT")})</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}
