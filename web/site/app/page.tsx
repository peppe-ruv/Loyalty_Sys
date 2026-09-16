const BFF = process.env.BFF_URL ?? "http://bff:3001";

async function getSummary(memberId: string) {
  const r = await fetch(`${BFF}/api/members/${memberId}/summary`, { cache: "no-store" });
  if (!r.ok) return null;
  return r.json();
}

export default async function Home() {
  // In produzione il memberId arriva dal token OIDC (D12); qui un membro di esempio del seed.
  const s = await getSummary("demo-member");
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
    </main>
  );
}
