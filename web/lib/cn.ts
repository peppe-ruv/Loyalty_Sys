// Minimo merge di classi (evita una dipendenza per il PoC).
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}
