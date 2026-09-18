const GRUPPI: Array<{ titolo: string; testo: string; token: string[] }> = [
  {
    titolo: 'Superfici',
    testo: 'Tre livelli e basta: sfondo della pagina, superficie delle schede, incavo per le zone secondarie.',
    token: ['--lh-ground', '--lh-surface', '--lh-sunk'],
  },
  {
    titolo: 'Testo e bordi',
    testo:
      'Un inchiostro, un grigio per ciò che è secondario, due linee — quella decorativa e quella che delimita un campo, che deve reggere il contrasto — e il blu dei collegamenti.',
    token: ['--lh-ink', '--lh-muted', '--lh-line', '--lh-line-strong', '--lh-link'],
  },
  {
    titolo: 'Accento',
    testo: 'L’accento segnala ciò che è attivo o primario; la versione morbida fa da sfondo alle chip.',
    token: ['--lh-accent', '--lh-accent-soft', '--lh-on-accent'],
  },
  {
    titolo: 'Esiti',
    testo: 'Verde, ambra, rosso hanno un significato fisso: riuscito, da guardare, rotto. Mai usati per decorare.',
    token: ['--lh-ok', '--lh-ok-soft', '--lh-warn', '--lh-warn-soft', '--lh-danger', '--lh-danger-soft'],
  },
  {
    titolo: 'Inchiostri sopra il colore',
    testo:
      'Per ogni fondo colorato c’è l’inchiostro che ci va sopra: senza, il testo di una chip finisce sotto la soglia di contrasto appena si cambia tema.',
    token: ['--lh-on-accent-soft', '--lh-on-ok-soft', '--lh-on-warn-soft', '--lh-on-danger-soft'],
  },
  {
    titolo: 'Barra laterale',
    testo: 'La navigazione è scura in entrambi i temi: è l’unico punto in cui il tema non cambia nulla.',
    token: ['--lh-side', '--lh-side-ink', '--lh-side-muted', '--lh-side-line'],
  },
];

const SPAZIATURE = ['--lh-space-1', '--lh-space-2', '--lh-space-3', '--lh-space-4', '--lh-space-5', '--lh-space-6', '--lh-space-7'];

export default function Token() {
  return (
    <>
      <section className="pg-hero">
        <h1>Token del tema</h1>
        <p className="pg-lead">
          Ogni colore, spaziatura e raggio del backoffice è un token con prefisso <code>--lh-</code>, e ognuno è un
          alias verso una primitiva dei{' '}
          <a href="https://github.com/italia/design-tokens-italia" rel="noreferrer noopener" target="_blank">
            Design Tokens Italia
          </a>{' '}
          (ADR-027). I componenti non usano mai un valore letterale: è così che il tema scuro, e un domani il tema di
          un cliente, si applicano senza toccare una riga di componente. Cambia il tema in alto a destra e guarda cosa
          succede.
        </p>
      </section>

      {GRUPPI.map((gruppo) => (
        <section key={gruppo.titolo} className="pg-section">
          <h2>{gruppo.titolo}</h2>
          <p className="lh-muted">{gruppo.testo}</p>
          <div className="pg-grid">
            {gruppo.token.map((token) => (
              <div key={token} className="pg-swatch">
                <span className="pg-swatch__sample" style={{ background: `var(${token})` }} aria-hidden="true" />
                <code>{token}</code>
              </div>
            ))}
          </div>
        </section>
      ))}

      <section className="pg-section">
        <h2>Spaziature</h2>
        <p className="lh-muted">Sette passi, una scala sola: le distanze diventano una decisione già presa.</p>
        <div className="lh-stack">
          {SPAZIATURE.map((token) => (
            <div key={token} className="lh-row">
              <code style={{ minWidth: '9rem' }}>{token}</code>
              <span style={{ height: '0.75rem', width: `var(${token})`, background: 'var(--lh-accent)', borderRadius: 'var(--lh-radius-sm)' }} />
            </div>
          ))}
        </div>
      </section>
    </>
  );
}
