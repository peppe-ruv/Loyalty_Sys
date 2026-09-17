const BFF = process.env.BFF_URL ?? "http://bff:3001";

async function getSummary(memberId: string) {
  const r = await fetch(`${BFF}/api/members/${memberId}/summary`, { cache: "no-store" });
  if (!r.ok) return null;
  return r.json();
}

/** RF-129: Next Best Action per la pagina (contesto = canale web, pagina); degrado a NO_ACTION se il motore non risponde. */
async function getNextBestAction(memberId: string) {
  try {
    const r = await fetch(`${BFF}/api/members/${memberId}/next-best-action`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ page: "home" }), cache: "no-store" });
    if (!r.ok) return null;
    return r.json();
  } catch { return null; }
}

/** RF-132: inbox in app (offerte e messaggi consegnati sul canale app/web). */
async function getInbox(memberId: string) {
  try {
    const r = await fetch(`${BFF}/api/members/${memberId}/inbox`, { cache: "no-store" });
    return r.ok ? r.json() : [];
  } catch { return []; }
}

export default async function Home() {
  // In produzione il memberId arriva dal token OIDC (D12); qui un membro di esempio del seed.
  const memberId = "demo-member";
  const [s, nba, inbox] = await Promise.all([getSummary(memberId), getNextBestAction(memberId), getInbox(memberId)]);
  return (
    <main style={{ fontFamily: "system-ui", padding: 24, maxWidth: 720 }}>
      <h1>Il tuo programma</h1>
      {s ? (
        <section>
          <p>Tier <strong>{s.tier}</strong> · {s.statusPointsYear} punti status quest'anno · {s.pointsToNext} al prossimo livello</p>
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
      {Array.isArray(inbox) && inbox.length > 0 && (
        <section aria-label="Messaggi" style={{ marginTop: 16 }}>
          <h2 style={{ fontSize: 18 }}>Messaggi e offerte</h2>
          <ul style={{ paddingLeft: 18 }}>
            {inbox.map((m: any) => (
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
