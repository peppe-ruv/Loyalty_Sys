// Supporto ai test del testbook (docs/16 §1bis): ogni caso ha un nome che inizia con `[<ID riga>]`.
// `it.each` con oggetti e `$id` cita le stringhe ('TB-…'), per cui le righe diventano tuple [id, descrizione, caso] da
// usare con il nome "[%s] %s".
export function rows<T extends { id: string; desc: string }>(cases: T[]): [string, string, T][] {
  return cases.map((c) => [c.id, c.desc, c]);
}
