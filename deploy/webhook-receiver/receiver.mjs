#!/usr/bin/env node
// receiver.mjs — ricevitore d'esempio dei webhook di Loyalty Hub (F-WBH-01, BO-23). Nessuna dipendenza.
//   LH_WEBHOOK_SECRET=whsec_... node receiver.mjs            # ascolta su http://localhost:4000/hook
//   PORT=4100 FAIL_WITH=500 LH_WEBHOOK_SECRET=... node receiver.mjs   # risponde sempre 500: per vedere i ritenti
// Per ogni richiesta verifica X-LH-Signature sul corpo grezzo: 204 se valida, 401 se no (o FAIL_WITH se impostato).
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { verifySignature } from "./verify.mjs";

/** Crea il server (esportato per i test). `onEvent` riceve {valid, headers, body, event}. */
export function createReceiver({ secret, failWith, onEvent = () => {} }) {
  return createServer((req, res) => {
    if (req.method !== "POST") {
      res.writeHead(405, { allow: "POST" }).end();
      return;
    }
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks);
      const valid = verifySignature(secret, body, req.headers["x-lh-signature"]);
      let event = null;
      try {
        event = JSON.parse(body.toString("utf8"));
      } catch {
        /* corpo non JSON: resta null */
      }
      onEvent({ valid, headers: req.headers, body, event });
      if (failWith) {
        res.writeHead(Number(failWith), { "content-type": "text/plain; charset=utf-8" }).end("Errore simulato dal ricevitore");
      } else if (!valid) {
        res.writeHead(401, { "content-type": "text/plain; charset=utf-8" }).end("Firma non valida");
      } else {
        res.writeHead(204).end();
      }
    });
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const secret = process.env.LH_WEBHOOK_SECRET;
  if (!secret) {
    console.error("Imposta LH_WEBHOOK_SECRET col segreto mostrato da BO-23 alla creazione del webhook.");
    process.exit(2);
  }
  const port = Number(process.env.PORT ?? 4000);
  createReceiver({
    secret,
    failWith: process.env.FAIL_WITH,
    onEvent: ({ valid, headers, event }) => {
      const when = new Date().toISOString();
      console.log(
        `${when} ${valid ? "✓ firma valida  " : "✗ firma NON valida"} consegna ${headers["x-lh-delivery-id"] ?? "?"} ` +
          `evento ${headers["x-lh-event-id"] ?? "?"} ${event?.type ?? ""} ${event?.subject ?? ""}`,
      );
    },
  }).listen(port, "127.0.0.1", () => console.log(`Ricevitore webhook in ascolto su http://localhost:${port}/hook`));
}
