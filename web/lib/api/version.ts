// Versioni (M7.6; docs/06 §colonne standard, docs/08 §editor): gli editor rimandano la `version` letta; il servizio
// risponde 409 VERSION_CONFLICT se nel frattempo qualcuno ha salvato. *Sovrascrivi* rimanda le stesse modifiche senza
// versione (nessun controllo), *Ricarica* rilegge l'oggetto e scarta le modifiche locali.

export interface VersionedError {
  status: number;
  code: string;
}

export function isVersionConflict(
  e: VersionedError | null | undefined,
): boolean {
  return !!e && e.status === 409 && e.code === "VERSION_CONFLICT";
}

/** Corpo del PUT con la versione letta dall'editor; `version` assente o null → corpo invariato. */
export function withVersion<T extends object>(
  body: T,
  version: number | null | undefined,
): T & { version?: number } {
  return version == null ? body : { ...body, version };
}

/** Corpo per *Sovrascrivi*: le stesse modifiche senza il controllo di versione. */
export function withoutVersion<T extends object>(
  body: T & { version?: number },
): Omit<T, "version"> {
  const rest: T & { version?: number } = { ...body };
  delete rest.version;
  return rest;
}
