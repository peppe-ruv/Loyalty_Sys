// Corpo delle richieste che il proxy /api/lh inoltra ai servizi (docs/07 §3): letto con un tetto, così un caricamento
// enorme (BO-32) non viene tenuto in memoria dal server web. Il servizio ha comunque i suoi limiti (1 MiB per l'import,
// 3 MiB per il batch): qui si ferma prima ciò che non potrebbe comunque passare.

/** Tetto del corpo inoltrato: sopra i limiti dei servizi, sotto ciò che il server web può tenere senza problemi. */
export const MAX_PROXY_BODY_BYTES = 3 * 1024 * 1024;

/**
 * Corpo della richiesta come byte, oppure `null` se supera `max`: prima dal `content-length` dichiarato (senza leggere
 * nulla), poi durante la lettura quando la lunghezza non è dichiarata (corpo a pezzi), interrompendo lo stream.
 */
export async function readCappedBody(req: Request, max = MAX_PROXY_BODY_BYTES): Promise<Uint8Array<ArrayBuffer> | null> {
  const declared = Number(req.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > max) return null;
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out;
}

/** Intestazioni di un file scaricato da inoltrare al browser (nome del file, niente interpretazione del tipo). */
export const DOWNLOAD_HEADERS = ["content-disposition", "x-content-type-options"] as const;

/** Il percorso chiede un file CSV scaricabile (`…/report.csv`, `…/winners.csv`)? */
export function isCsvDownload(path: string[]): boolean {
  return (path[path.length - 1] ?? "").toLowerCase().endsWith(".csv");
}
