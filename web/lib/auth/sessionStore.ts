import { deriveKey, digest, open, randomId, seal } from "./crypto";
import type { Role } from "@/lib/persona/personas";
import type { SessionKind } from "./roles";

// Sessioni del BFF (docs/18 §3.2, ADR-027). SOLO LATO SERVER. Il browser ha solo l'id opaco nel cookie
// `__Host-lh_session`; token e identità stanno qui, cifrati con AES-256-GCM e legati all'id (AAD).
// La specifica vuole le sessioni «cifrate nel database del ruolo web»: il ruolo web non ha ancora un database, quindi
// qui c'è l'interfaccia e un'implementazione in memoria per una sola replica.
// SPEC-GAP: Q-409 (store su database condiviso tra repliche, con lock del rinnovo).

/**
 * Identità mostrata dal web; niente dati che non servano a UI e logout. Fissata al login: un cambio di ruoli nell'IdP
 * si vede nella UI solo al login successivo. Serve solo a mostrare o nascondere azioni: l'autorizzazione vera la fanno
 * i servizi sui claim dell'access token, che si rinnova ogni 5 minuti (Q-409).
 */
export interface SessionUser {
  /** `sub` del token: il membro o l'operatore per l'IdP. */
  sub: string;
  /** `sid` della sessione dell'IdP (back-channel logout); `null` se l'IdP non lo manda. */
  sid: string | null;
  username: string;
  name: string | null;
  roles: string[];
  role: Role;
  kind: SessionKind;
}

export interface SessionTokens {
  accessToken: string;
  /** Scadenza dell'access token, in secondi epoch. */
  accessExpiresAt: number;
  refreshToken: string | null;
  /** Per `id_token_hint` nel logout. */
  idToken: string;
}

export interface Session {
  user: SessionUser;
  tokens: SessionTokens;
  /** Secondi epoch. */
  createdAt: number;
  lastSeenAt: number;
}

export interface SessionStore {
  /** Crea una sessione e restituisce il nuovo id opaco (256 bit casuali): mai riusato, niente session fixation. */
  create(user: SessionUser, tokens: SessionTokens): Promise<string>;
  /** Sessione valida (non scaduta per inattività o durata massima), rinnovandone l'ultimo uso; altrimenti `null`. */
  get(id: string): Promise<Session | null>;
  /** Sostituisce i token (rinnovo); `false` se la sessione non esiste più. */
  updateTokens(id: string, tokens: SessionTokens): Promise<boolean>;
  delete(id: string): Promise<void>;
  /** Chiude le sessioni con quel `sid` (e, se dato, quel `sub`) o, senza `sid`, tutte quelle del `sub` (back-channel). */
  deleteMatching(match: { sid?: string; sub?: string }): Promise<number>;
}

export interface InMemorySessionStoreOptions {
  masterKey: Buffer;
  idleSeconds: number;
  maxSeconds: number;
  maxSessions: number;
  /**
   * Sessioni per lo stesso `sub` (browser o dispositivi): oltre, esce la sua meno recente. Così un solo account che
   * ripete il login non può spingere fuori dal tetto globale le sessioni di tutti gli altri. Predefinito 10.
   */
  maxSessionsPerSubject?: number;
  /** Orologio in secondi epoch (iniettabile nei test). */
  now?: () => number;
}

interface Entry {
  /** `{user, tokens}` cifrati, con l'impronta dell'id come AAD. */
  blob: string;
  sid: string | null;
  sub: string;
  createdAt: number;
  lastSeenAt: number;
}

/**
 * Store in memoria: chiave interna = SHA-256 dell'id (un dump della memoria non contiene cookie utilizzabili), valore
 * cifrato. Inattività e durata massima controllate a ogni lettura; oltre `maxSessions` esce la sessione usata meno di
 * recente (la `Map` è tenuta in ordine d'uso). Le sessioni scadute si ripuliscono a ogni creazione.
 */
export class InMemorySessionStore implements SessionStore {
  private readonly entries = new Map<string, Entry>();
  private readonly key: Buffer;
  private readonly now: () => number;

  constructor(private readonly options: InMemorySessionStoreOptions) {
    this.key = deriveKey(options.masterKey, "session");
    this.now = options.now ?? (() => Math.floor(Date.now() / 1000));
  }

  async create(user: SessionUser, tokens: SessionTokens): Promise<string> {
    this.sweep();
    this.makeRoomFor(user.sub);
    const id = randomId();
    const handle = digest(id);
    const now = this.now();
    this.entries.set(handle, {
      blob: seal(JSON.stringify({ user, tokens }), this.key, handle),
      sid: user.sid,
      sub: user.sub,
      createdAt: now,
      lastSeenAt: now,
    });
    while (this.entries.size > this.options.maxSessions) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
    return id;
  }

  async get(id: string): Promise<Session | null> {
    const handle = digest(id);
    const entry = this.entries.get(handle);
    if (!entry) return null;
    const now = this.now();
    if (this.expired(entry, now)) {
      this.entries.delete(handle);
      return null;
    }
    const plain = open(entry.blob, this.key, handle);
    if (plain === null) {
      // Blob alterato o chiave cambiata: la sessione non è più affidabile.
      this.entries.delete(handle);
      return null;
    }
    entry.lastSeenAt = now;
    this.entries.delete(handle);
    this.entries.set(handle, entry);
    const { user, tokens } = JSON.parse(plain) as Pick<Session, "user" | "tokens">;
    return { user, tokens, createdAt: entry.createdAt, lastSeenAt: now };
  }

  async updateTokens(id: string, tokens: SessionTokens): Promise<boolean> {
    const handle = digest(id);
    const entry = this.entries.get(handle);
    if (!entry) return false;
    const plain = open(entry.blob, this.key, handle);
    if (plain === null) {
      this.entries.delete(handle);
      return false;
    }
    const { user } = JSON.parse(plain) as Pick<Session, "user">;
    entry.blob = seal(JSON.stringify({ user, tokens }), this.key, handle);
    return true;
  }

  async delete(id: string): Promise<void> {
    this.entries.delete(digest(id));
  }

  async deleteMatching(match: { sid?: string; sub?: string }): Promise<number> {
    if (!match.sid && !match.sub) return 0;
    let removed = 0;
    for (const [handle, entry] of this.entries) {
      const sameSid = match.sid ? entry.sid === match.sid : true;
      const sameSub = match.sub ? entry.sub === match.sub : true;
      if (sameSid && sameSub) {
        this.entries.delete(handle);
        removed++;
      }
    }
    return removed;
  }

  /** Numero di sessioni tenute (anche scadute non ancora ripulite): per i test e le metriche. */
  get size(): number {
    return this.entries.size;
  }

  private expired(entry: Entry, now: number): boolean {
    return now - entry.lastSeenAt > this.options.idleSeconds || now - entry.createdAt > this.options.maxSeconds;
  }

  /** Toglie le sessioni meno recenti di `sub` finché ne resta posto per una nuova (la `Map` è in ordine d'uso). */
  private makeRoomFor(sub: string): void {
    const cap = this.options.maxSessionsPerSubject ?? 10;
    const mine = [...this.entries].filter(([, entry]) => entry.sub === sub).map(([handle]) => handle);
    for (let i = 0; i <= mine.length - cap; i++) this.entries.delete(mine[i]);
  }

  private sweep(): void {
    const now = this.now();
    for (const [handle, entry] of this.entries) {
      if (this.expired(entry, now)) this.entries.delete(handle);
    }
  }
}
