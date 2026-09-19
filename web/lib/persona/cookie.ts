import { BACKOFFICE_PERSONAS, DEFAULT_BACKOFFICE_USERNAME, DEFAULT_MEMBER_ID, type Role } from "./personas";

// Cookie `lh_persona` (docs/07 §4): identità simulata, nessuna login.

export const PERSONA_COOKIE = "lh_persona";
export const PERSONA_MAX_AGE = 60 * 60 * 24 * 30; // 30 giorni

export type Persona =
  | { kind: "BO"; username: string; role: Role }
  | { kind: "MEMBER"; memberId: string };

export const DEFAULT_BO_PERSONA: Persona = {
  kind: "BO",
  username: DEFAULT_BACKOFFICE_USERNAME,
  role: "ADMIN",
};

export const DEFAULT_MEMBER_PERSONA: Persona = {
  kind: "MEMBER",
  memberId: DEFAULT_MEMBER_ID,
};

/** Interpreta il valore del cookie; formati non validi ⇒ `null`. */
export function parsePersona(raw: string | undefined | null): Persona | null {
  if (!raw) return null;
  try {
    const decoded = decodeURIComponent(raw);
    const value = JSON.parse(decoded) as Persona;
    if (value?.kind === "BO" && typeof value.username === "string" && typeof value.role === "string") {
      return { kind: "BO", username: value.username, role: value.role };
    }
    if (value?.kind === "MEMBER" && typeof value.memberId === "string") {
      return { kind: "MEMBER", memberId: value.memberId };
    }
    return null;
  } catch {
    return null;
  }
}

export function serializePersona(persona: Persona): string {
  return encodeURIComponent(JSON.stringify(persona));
}

/** Header `X-LH-Actor` da inviare ai servizi (docs/06 §3). */
export function actorHeader(persona: Persona | null): string {
  if (persona?.kind === "BO") {
    return `${persona.role}:${persona.username}`;
  }
  // I membri non usano X-LH-Actor sugli endpoint /v1/portal/**; per default sola lettura.
  return "ANALYST:anonymous";
}

export function backofficePersonaFromUsername(username: string): Persona {
  const found = BACKOFFICE_PERSONAS.find((p) => p.username === username);
  return { kind: "BO", username, role: found?.role ?? "ANALYST" };
}
