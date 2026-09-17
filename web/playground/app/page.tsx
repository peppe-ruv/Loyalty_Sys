const CAPACITA = [
  {
    titolo: 'Ingestione e regole',
    testo:
      'Qualunque sistema manda un’azione premiante — bolletta pagata, autolettura, acquisto, check-in. Le campagne, scritte dal marketing nel backoffice, la trasformano in punti sul wallet giusto.',
  },
  {
    titolo: 'Registro e livelli',
    testo:
      'Il registro punti è l’unica verità sui saldi: movimenti immutabili, scadenze, blocchi, trasferimenti. I livelli si ricalcolano dai movimenti, con discesa morbida a fine anno.',
  },
  {
    titolo: 'Premi e concorsi',
    testo:
      'Dieci tipi di premio con evasione e lotti di codici, «paga con i punti», instant win a istanti vincenti pre-generati e registro giocate a prova di manomissione.',
  },
  {
    titolo: 'Livello decisionale',
    testo:
      'Per ogni evento il motore sceglie che cosa proporre, su quale canale, entro i vincoli della policy — e registra il perché di ogni scelta e di ogni scarto.',
  },
];

export default function Home() {
  return (
    <>
      <section className="pg-hero">
        <h1>Vedere come decide una piattaforma loyalty</h1>
        <p className="pg-lead">
          Loyalty Hub è una piattaforma loyalty open source e vendor neutral: riceve azioni premianti da qualunque
          sistema, le trasforma in punti e livelli, sblocca premi, gestisce concorsi e decide che cosa proporre a
          ciascun cliente. Questo playground ne mostra due parti che di solito restano invisibili: i{' '}
          <a href="/pattern">pattern del backoffice</a> con cui si configura il programma e il{' '}
          <a href="/decisioni">motore decisionale</a> con i suoi vincoli.
        </p>
      </section>

      <p className="pg-note" style={{ marginTop: 'var(--lh-space-4)' }}>
        Tutto quello che vedi gira nel browser su dati inventati. Non c’è un backend, non c’è un dato personale e
        nessuna scelta fatta qui ha effetti da nessuna parte.
      </p>

      <div className="pg-grid">
        {CAPACITA.map((capacita) => (
          <article key={capacita.titolo} className="lh-card">
            <h2 className="lh-title">{capacita.titolo}</h2>
            <p className="lh-muted">{capacita.testo}</p>
          </article>
        ))}
      </div>

      <section className="pg-section">
        <h2>Come si muove un evento</h2>
        <pre className="pg-code">{`fonte  →  ingestione  →  topic delle azioni
                              ↓
                        decision-service
             contesto + campagne + offerte + previsioni + policy
                              ↓
        effetti contrattuali (punti, livello, badge)  →  registro
        azioni discrezionali arbitrate                →  consegna  →  canale
                              ↓
                   decisione registrata: scelte, scarti, motivi`}</pre>
        <p className="lh-muted">
          Le azioni contrattuali sono sempre applicate: previsioni e policy non toccano mai punti, saldi o livelli.
          L’arbitrato riguarda solo ciò che è discrezionale — un’offerta, un messaggio, una richiesta di feedback.
        </p>
      </section>

      <section className="pg-section">
        <h2>Dove guardare</h2>
        <div className="pg-grid">
          <article className="lh-card">
            <h3 className="lh-title">Pattern</h3>
            <p className="lh-muted">
              Lista con filtri come frasi, form a sezioni fisse con il workflow di approvazione, regole che si leggono
              come «se… allora», KPI con il confronto al periodo precedente, scheda membro con la timeline dei saldi.
            </p>
            <p>
              <a href="/pattern">Apri i pattern →</a>
            </p>
          </article>
          <article className="lh-card">
            <h3 className="lh-title">Decisioni</h3>
            <p className="lh-muted">
              Scegli un profilo, l’ora del giorno e il budget già speso: il motore mostra che cosa sceglierebbe, che
              cosa scarterebbe e con quale codice motivo.
            </p>
            <p>
              <a href="/decisioni">Prova il motore →</a>
            </p>
          </article>
        </div>
      </section>
    </>
  );
}
