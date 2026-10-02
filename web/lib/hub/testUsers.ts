// HUB-02 — utenti di test della vetrina enterprise (ADR-051 decisione 1, Q-673, Q-676, Q-671).
//
// ATTENZIONE: queste sono credenziali PUBBLICHE di un ambiente di test dichiarato, per decisione del proprietario
// (ADR-051 dec. 1, Q-676): le stesse degli overlay di Keycloak della vetrina (`deploy/idp/vetrina/`), pubblicate
// anche nella guida di Mintlify. Non sono segreti e non proteggono nulla. Il profilo `enterprise` le rifiuta ovunque
// salvo con LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test (config.ts, ruolo LH_TEST_USER).
// Questo è l'UNICO file del web che le contiene: le importano solo server component e route handler (mai un modulo
// "use client": al browser arriva solo ciò che la pagina passa come prop). Allowlist di gitleaks e controllo di
// coerenza con gli overlay di Keycloak puntano a questo percorso.

import type { Role } from "@/lib/persona/personas";

export const OPERATORS_PASSWORD = "Aurora-Operatori-26!";
export const MEMBERS_PASSWORD = "Aurora-Membri-26!";
export const KEYCLOAK_ADMIN_PASSWORD = "Aurora-Admin-26!";
/** Seme TOTP (base32) condiviso dai cinque operatori di test: RFC 6238, SHA-1, 6 cifre, 30 s. */
export const OPERATORS_TOTP_SEED = "KZSXI4TJNZQUC5LSN5ZGCMRQGI3EY2BB";

/**
 * Il portale dei membri non funziona ancora dal token (fetta V9b): finché è `false` i pulsanti dei quattro membri
 * sono disabilitati e la scheda lo dice. Si porta a `true` nella fetta V9b, insieme ai test dei percorsi del portale.
 */
export const MEMBER_PORTAL_READY = false;

export interface TestOperator {
  username: string;
  name: string;
  role: Role;
  /** Cosa fa questo operatore, in una riga. */
  summary: string;
}

export interface TestMember {
  username: string;
  name: string;
  /** `true`: registrazione già completata all'avvio (Q-673); `false`: da registrare al primo accesso (Laura, PT-16). */
  registered: boolean;
  /** Genere grammaticale della pillola «registrata/registrato». */
  gender: "f" | "m";
  story: string;
}

export const TEST_OPERATORS: readonly TestOperator[] = [
  { username: "marta.admin", name: "Marta", role: "ADMIN", summary: "Configura il programma, approva, anonimizza." },
  { username: "luca.marketing", name: "Luca", role: "MARKETING", summary: "Crea campagne e premi in bozza." },
  { username: "elena.legal", name: "Elena", role: "LEGAL", summary: "Approva concorsi e regolamenti." },
  { username: "paolo.care", name: "Paolo", role: "CARE", summary: "Assiste i membri, rettifiche, import." },
  { username: "sara.analyst", name: "Sara", role: "ANALYST", summary: "Legge report e audit, non modifica." },
];

export const TEST_MEMBERS: readonly TestMember[] = [
  { username: "anna.rossi", name: "Anna Rossi", registered: true, gender: "f", story: "Appena iscritta, primi punti di benvenuto." },
  { username: "marco.bianchi", name: "Marco Bianchi", registered: true, gender: "m", story: "Livello Silver, acquisti regolari." },
  { username: "giulia.ferri", name: "Giulia Ferri", registered: true, gender: "f", story: "A un passo dalla soglia del livello successivo." },
  {
    username: "laura.conti",
    name: "Laura Conti",
    registered: false,
    gender: "f",
    story: "Ha solo l'account: al primo accesso compila la registrazione. Torna da registrare a ogni avvio.",
  },
];

export const KEYCLOAK_ADMINS = {
  operators: { realm: "loyaltyhub", username: "vetrina.admin" },
  members: { realm: "loyaltyhub-members", username: "membri.admin" },
} as const;

/** Username di test ammessi come `login_hint`, per realm (login del BFF, mai altri valori). */
export function testUsernamesFor(realm: "operators" | "members"): readonly string[] {
  return realm === "operators" ? TEST_OPERATORS.map((u) => u.username) : TEST_MEMBERS.map((u) => u.username);
}
