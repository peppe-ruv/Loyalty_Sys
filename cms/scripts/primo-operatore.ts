/**
 * Crea il primo operatore del backoffice, se non ce n'è ancora nessuno.
 *
 * Perché esiste: finché la collezione degli utenti è vuota, Payload tiene aperta la rotta di
 * primo accesso (`/api/users/first-register`) e **chiunque raggiunga il pannello può diventarne
 * amministratore**. Su una macchina in ufficio è una comodità; su un indirizzo pubblico è una
 * consegna delle chiavi: il backoffice decide campagne, policy del motore decisionale, premi e
 * concorsi. Questo script chiude quella finestra al primo rilascio, prima che il pannello sia
 * raggiungibile.
 *
 * Si esegue con `npx payload run scripts/primo-operatore.ts`, dopo le migrazioni.
 * Senza `ADMIN_EMAIL` e `ADMIN_PASSWORD` non fa nulla e lo dice: è un promemoria, non un errore,
 * perché in locale la finestra aperta non è un problema.
 */
import { getPayload } from "payload";
import config from "../src/payload.config.js";

const email = process.env.ADMIN_EMAIL;
const password = process.env.ADMIN_PASSWORD;

const payload = await getPayload({ config });

const { totalDocs } = await payload.count({ collection: "users" });
if (totalDocs > 0) {
  payload.logger.info(`Operatori già presenti (${totalDocs}): niente da fare.`);
} else if (!email || !password) {
  // In locale la finestra aperta è una comodità: si apre il pannello e ci si registra. In un rilascio
  // è una porta aperta su un indirizzo pubblico, e un avviso nel log della build non la chiude —
  // nessuno legge i log di una build verde. Quindi qui la build fallisce.
  const rilascio = Boolean(process.env.VERCEL || process.env.CI);
  const messaggio =
    "Nessun operatore e nessun ADMIN_EMAIL/ADMIN_PASSWORD: il pannello resterebbe aperto al primo che " +
    "lo raggiunge, che potrebbe registrarsi come amministratore.";
  if (rilascio) {
    payload.logger.error(messaggio + " Impostale prima di pubblicare.");
    process.exit(1);
  }
  payload.logger.warn(messaggio + " In locale si può, in un ambiente pubblico no.");
} else {
  await payload.create({ collection: "users", data: { email, password } });
  payload.logger.info(`Primo operatore creato: ${email}`);
}

process.exit(0);
