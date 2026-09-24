#!/usr/bin/env node
// verify.mjs — verifica della firma dei webhook di Loyalty Hub (docs/servizi/engagement-service.md §5, F-WBH-01).
// Nessuna dipendenza: solo node:crypto. Header `X-LH-Signature: sha256=<HMAC-SHA256(secret, corpo)>` in esadecimale
// minuscolo, calcolato sui byte grezzi del corpo ricevuto (non su un JSON riserializzato).
//
// Uso come libreria:   import { verifySignature } from "./verify.mjs";
// Uso da riga di comando:
//   node verify.mjs --secret whsec_... --signature sha256=... --file corpo.json
//   cat corpo.json | node verify.mjs --secret whsec_... --signature sha256=...
// Esce con 0 se la firma è valida, 1 se non lo è, 2 per argomenti errati.
import { createHmac, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const PREFIX = "sha256=";

/** Firma attesa per un corpo (Buffer o stringa UTF-8). */
export function sign(secret, rawBody) {
  const body = Buffer.isBuffer(rawBody) ? rawBody : Buffer.from(String(rawBody), "utf8");
  return PREFIX + createHmac("sha256", secret).update(body).digest("hex");
}

/**
 * true se `signatureHeader` è la firma di `rawBody` con `secret`. Confronto a tempo costante (timingSafeEqual) per
 * non rivelare, col tempo di risposta, quanti caratteri iniziali coincidono.
 */
export function verifySignature(secret, rawBody, signatureHeader) {
  if (!secret || typeof signatureHeader !== "string") return false;
  const expected = Buffer.from(sign(secret, rawBody), "utf8");
  const received = Buffer.from(signatureHeader.trim(), "utf8");
  if (expected.length !== received.length) return false;
  return timingSafeEqual(expected, received);
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) out[a.slice(2)] = argv[i + 1];
    if (a.startsWith("--")) i++;
  }
  return out;
}

async function readStdin() {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  return Buffer.concat(chunks);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const secret = args.secret ?? process.env.LH_WEBHOOK_SECRET;
  if (!secret || !args.signature) {
    console.error("Uso: node verify.mjs --secret <whsec_...> --signature <sha256=...> [--file corpo.json]");
    process.exit(2);
  }
  const body = args.file ? readFileSync(args.file) : await readStdin();
  if (verifySignature(secret, body, args.signature)) {
    console.log("Firma valida ✓");
    process.exit(0);
  }
  console.error(`Firma NON valida ✗ (attesa ${sign(secret, body)})`);
  process.exit(1);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await main();
}
