import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Radice del repository: web/test/ → ../..
const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

// Legge un file canonico di seed/ (docs/10) a runtime. Un import JSON fuori da web/ romperebbe il controllo dei tipi
// di `next build` nell'immagine, dove il contesto di build contiene solo web/.
export function readSeed<T>(file: string): T {
  return JSON.parse(readFileSync(join(REPO_ROOT, "seed", file), "utf8")) as T;
}
