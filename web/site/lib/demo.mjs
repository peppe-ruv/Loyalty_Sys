/**
 * Dati dimostrativi del portale (modalità vetrina).
 *
 * Servono a far vedere il portale senza i quattordici servizi dietro. Valgono le stesse regole
 * della piattaforma vera: il membro è un identificatore opaco, non c'è nessun dato personale, e
 * la pagina dichiara all'utente che sta guardando dati finti.
 *
 * Le forme rispettano i contratti del BFF (`web/bff/src/server.js`): se il BFF cambia risposta,
 * questi finti vanno aggiornati o tolti — un finto che mente è peggio di nessun finto.
 */

/** Istante fisso più lo scorrere del tempo: la vetrina non deve cambiare a ogni ricarica, ma nemmeno sembrare ferma. */
const GIORNO = 86_400_000;

export function summary() {
  return {
    memberId: 'sub-8f2a',
    tier: 'PLUS',
    statusPointsYear: 7_310,
    pointsToNext: 2_690,
    updatedAt: new Date().toISOString(),
    stale: false,
  };
}

export function nextBestAction() {
  return {
    action: 'SHOW_OFFER',
    offerId: 'offerta-manutenzione',
    reason: 'propensione 0,62 · canale preferito · nessun tetto di contatto superato',
    metadata: {
      params: {
        title: 'Un buono manutenzione per te',
        body: 'Hai raggiunto i punti che servono: il buono vale 12 € sulla prossima manutenzione.',
      },
    },
  };
}

export function inbox() {
  return [
    {
      id: 'msg-1',
      subject: 'Bolletta puntuale: 168 punti accreditati',
      body: 'Il pagamento entro la scadenza vale il doppio dell’importo in punti.',
      expires_at: new Date(Date.now() + 20 * GIORNO).toISOString(),
    },
    {
      id: 'msg-2',
      subject: 'Manca poco al livello Top',
      body: 'Con 2.690 punti status passi al livello superiore e il moltiplicatore diventa 1,25.',
      expires_at: null,
    },
  ];
}
