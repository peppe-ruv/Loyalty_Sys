// Nome e cognome dell'account di accesso per precompilare la registrazione (PT-16, F2-SEC-09).
// Arrivano dai claim dell'ID token letti lato server (lib/auth/handlers.ts): i claim `given_name`/`family_name`, altrimenti
// il nome completo diviso al primo spazio. Nessun valore inventato: se mancano restano vuoti e la schermata lo dice.

export interface AccountNames {
  givenName: string | null;
  familyName: string | null;
  name: string | null;
}

export function accountNames(a: AccountNames): { firstName: string; lastName: string } {
  if (a.givenName?.trim() || a.familyName?.trim()) {
    return { firstName: (a.givenName ?? "").trim(), lastName: (a.familyName ?? "").trim() };
  }
  const [first = "", ...rest] = (a.name ?? "").trim().split(/\s+/);
  return { firstName: first, lastName: rest.join(" ") };
}
