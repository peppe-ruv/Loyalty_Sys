import { ArrowRight, ExternalLink, Info, LogIn } from "lucide-react";
import { Card } from "@/components/ui/card";
import type { SessionUser } from "@/lib/auth/sessionStore";
import { cn } from "@/lib/cn";
import type { TestMode } from "@/lib/hub/testMode";
import type { MasterConsole } from "@/lib/hub/vetrinaMarkers";
import {
  KEYCLOAK_ADMIN_PASSWORD,
  KEYCLOAK_ADMINS,
  MEMBER_PORTAL_READY,
  MEMBERS_PASSWORD,
  OPERATORS_PASSWORD,
  OPERATORS_TOTP_SEED,
  TEST_MEMBERS,
  TEST_OPERATORS,
} from "@/lib/hub/testUsers";
import { it } from "@/lib/i18n/it";
import { LogoutButton } from "@/components/shared/auth/LogoutButton";
import { CopyValue } from "./CopyButton";
import { OtpCard } from "./OtpCard";

// HUB-02 — utenti di test (ADR-051, Q-673, Q-676, Q-677). SERVER COMPONENT: è l'unico modulo UI che importa le
// credenziali pubbliche di `lib/hub/testUsers.ts`; ai componenti client (copia, OTP) passa solo i valori da mostrare.
// Si rende SOLO nell'ambiente di test dichiarato (`testMode()` non nullo): altrove HUB-02 resta con il solo login.
// Sessione aperta: Keycloak rifiuta di autenticare un altro utente sopra una sessione SSO esistente, quindi con una
// sessione da operatore la scheda dell'utente collegato offre «Apri il backoffice» e ogni ALTRA scheda offre l'uscita
// («Esci per entrare come <Nome>») al posto del collegamento di ingresso; `prompt=login` resta, innocuo.
// Membri (V9b, F2-SEC-09, ADR-051): il portale funziona dal token, quindi le schede dei membri sono attive. Le sessioni
// dei due realm sono indipendenti: con una sessione da membro la scheda del membro collegato offre «Apri il portale» e
// ogni ALTRA scheda di membro offre l'uscita dalla sola sessione del MEMBRO («Esci per entrare come <Nome>»).
// SPEC-GAP: Q-673 — se `MEMBER_PORTAL_READY` è false i pulsanti dei membri tornano disabilitati e la scheda lo dice.

const t = it.testUsers;

const cardBtn =
  "inline-flex items-center justify-center gap-2 rounded-md bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-semibold text-white hover:opacity-90";
const ghostBtn =
  "inline-flex items-center justify-center gap-2 rounded-md border border-[var(--color-bo-border)] px-3 py-1.5 text-sm font-semibold hover:bg-[var(--color-bo-bg)]";

/** Ingresso dal BFF con lo username già scritto (login_hint, ammesso solo per gli utenti di test noti). */
export function loginHref(realm: "operators" | "members", returnTo: string, username: string): string {
  return `/api/auth/login?realm=${realm}&returnTo=${encodeURIComponent(returnTo)}&login_hint=${encodeURIComponent(username)}`;
}

function initials(name: string): string {
  const words = name.split(" ").filter(Boolean);
  return (words.length > 1 ? words[0][0] + words[1][0] : name.slice(0, 2)).toUpperCase();
}

function Pill({ children, tone }: { children: React.ReactNode; tone: "role" | "ok" | "warn" }) {
  return (
    <span
      className={cn(
        "shrink-0 rounded-full border px-2 py-0.5 text-xs font-semibold",
        tone === "role" && "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]",
        tone === "ok" && "border-[var(--color-state-up)]/50 bg-[var(--color-state-up)]/10 text-[var(--color-state-up)]",
        tone === "warn" && "border-[var(--color-state-waking)]/50 bg-[var(--color-state-waking)]/10 text-[var(--color-state-waking)]",
      )}
    >
      {children}
    </span>
  );
}

function PersonHead({ name, username, pill }: { name: string; username: string; pill: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-2">
      <div className="flex min-w-0 items-center gap-2.5">
        <span
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[var(--color-bo-accent)] text-xs font-bold text-white"
          aria-hidden
        >
          {initials(name)}
        </span>
        <div className="min-w-0">
          <h4 className="font-semibold">{name}</h4>
          <p className="truncate font-mono text-xs text-[var(--color-bo-ink-2)]">{username}</p>
        </div>
      </div>
      {pill}
    </div>
  );
}

function GroupHeader({ id, title, passwordWhat, password }: { id: string; title: string; passwordWhat: string; password: string }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <h3 id={id} className="text-lg font-semibold">
        {title}
      </h3>
      <span className="text-sm text-[var(--color-bo-ink-2)]">
        {t.sharedPassword}: <CopyValue value={password} what={passwordWhat} className="text-[var(--color-bo-ink)]" />
      </span>
    </div>
  );
}

/** Banner dell'ambiente di test (Q-676), in aggiunta a quello della vetrina. */
export function TestEnvironmentBanner() {
  return (
    <p
      role="note"
      data-testid="test-banner"
      className="mb-8 flex items-start gap-2 rounded-md border border-[var(--color-state-down)]/40 bg-[var(--color-state-down)]/5 px-3 py-2 text-sm"
    >
      <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <span>
        <b>{t.banner}</b> {t.bannerText}
      </span>
    </p>
  );
}

function OperatorCard({ op, user }: { op: (typeof TEST_OPERATORS)[number]; user: Pick<SessionUser, "username" | "name"> | null }) {
  const here = user !== null && user.username === op.username;
  const first = op.name.split(" ")[0];
  return (
    <Card className="flex flex-col gap-2 p-4" data-testid={`op-${op.username}`}>
      <PersonHead name={op.name} username={op.username} pill={<Pill tone="role">{op.role}</Pill>} />
      <p className="flex-1 text-sm text-[var(--color-bo-ink-2)]">{op.summary}</p>
      {here ? (
        <>
          <p className="text-xs font-medium text-[var(--color-state-up)]">{t.signedInAs(user.name ?? user.username)}</p>
          <a href="/backoffice" className={cardBtn}>
            {t.openBackoffice} <ArrowRight className="h-4 w-4" aria-hidden />
          </a>
          {op.role === "ADMIN" ? <LoadSampleLink /> : null}
        </>
      ) : user !== null ? (
        <>
          <p className="text-xs font-medium text-[var(--color-bo-ink-2)]">{t.signedInAs(user.name ?? user.username)}</p>
          <LogoutButton label={t.logoutToEnterAs(first)} className={cn(ghostBtn, "w-full text-sm")} />
        </>
      ) : (
        <>
          <a href={loginHref("operators", "/backoffice", op.username)} className={cardBtn}>
            <LogIn className="h-4 w-4" aria-hidden /> {t.enterAs(op.name)}
          </a>
          {op.role === "ADMIN" ? <LoadSampleLink /> : null}
        </>
      )}
    </Card>
  );
}

/** ADR-051 decisione 3 (V10): il programma di esempio si carica dalla Dashboard del backoffice, solo da ADMIN. */
function LoadSampleLink() {
  return (
    <a href="/backoffice" className="text-center text-sm text-[var(--color-bo-ink-2)] underline underline-offset-2">
      {t.loadSample}
    </a>
  );
}

function MemberCard({ m, user }: { m: (typeof TEST_MEMBERS)[number]; user: Pick<SessionUser, "username" | "name"> | null }) {
  const first = m.name.split(" ")[0];
  const label = m.registered ? t.enterAs(first) : t.registerAs(first);
  return (
    <Card className={cn("flex flex-col gap-2 p-4", !m.registered && "border-dashed")} data-testid={`member-${m.username}`}>
      <PersonHead
        name={m.name}
        username={m.username}
        pill={m.registered ? <Pill tone="ok">{t.registered[m.gender]}</Pill> : <Pill tone="warn">{t.toRegister}</Pill>}
      />
      <p className="flex-1 text-sm text-[var(--color-bo-ink-2)]">{m.story}</p>
      {MEMBER_PORTAL_READY && user !== null && user.username === m.username ? (
        <>
          <p className="text-xs font-medium text-[var(--color-state-up)]">{t.signedInAs(user.name ?? user.username)}</p>
          <a href="/portal" className={cardBtn}>
            {t.openPortal} <ArrowRight className="h-4 w-4" aria-hidden />
          </a>
        </>
      ) : MEMBER_PORTAL_READY && user !== null ? (
        <>
          <p className="text-xs font-medium text-[var(--color-bo-ink-2)]">{t.signedInAs(user.name ?? user.username)}</p>
          <LogoutButton realm="members" label={t.logoutToEnterAs(first)} className={cn(ghostBtn, "w-full text-sm")} />
        </>
      ) : MEMBER_PORTAL_READY ? (
        <a href={loginHref("members", "/portal", m.username)} className={cardBtn}>
          <LogIn className="h-4 w-4" aria-hidden /> {label}
        </a>
      ) : (
        <>
          <button type="button" disabled aria-describedby={`portal-not-ready-${m.username}`} className={cn(cardBtn, "cursor-not-allowed opacity-50 hover:opacity-50")}>
            {label}
          </button>
          <p id={`portal-not-ready-${m.username}`} className="text-xs text-[var(--color-bo-ink-2)]">
            {t.portalNotReady}
          </p>
        </>
      )}
    </Card>
  );
}

function ConsoleCard({
  title,
  text,
  realm,
  admin,
  href,
}: {
  title: string;
  text: string;
  realm: string;
  admin: string;
  href: string;
}) {
  return (
    <Card className="flex flex-col gap-2 p-4" data-testid={`console-${realm}`}>
      <div className="flex items-start justify-between gap-2">
        <h4 className="font-semibold">{title}</h4>
        <Pill tone="role">{realm}</Pill>
      </div>
      <p className="flex-1 text-sm text-[var(--color-bo-ink-2)]">{text}</p>
      <p className="text-sm">
        <span className="text-xs text-[var(--color-bo-ink-2)]">{t.user}</span> <CopyValue value={admin} what={t.adminUser} />
      </p>
      <p className="text-sm">
        <span className="text-xs text-[var(--color-bo-ink-2)]">{t.password}</span>{" "}
        <CopyValue value={KEYCLOAK_ADMIN_PASSWORD} what={t.adminPassword} />
      </p>
      <a href={href} target="_blank" rel="noopener noreferrer" className={ghostBtn}>
        {t.consoleOpen} <ExternalLink className="h-4 w-4" aria-hidden />
        <span className="sr-only">{t.consoleExternal}</span>
      </a>
    </Card>
  );
}

/** Realm master (ADR-055, Q-727): solo lo stato e il collegamento, MAI utente, password o valori copiabili. */
function MasterConsoleCard({ master }: { master: MasterConsole }) {
  const open = master.state === "open";
  return (
    <Card className="flex flex-col gap-2 p-4" data-testid="console-master" data-state={master.state}>
      <div className="flex items-start justify-between gap-2">
        <h4 className="font-semibold">{t.consoleMaster}</h4>
        <Pill tone={open ? "ok" : "warn"}>{open ? t.consoleMasterOpen : t.consoleMasterClosed}</Pill>
      </div>
      <p className="flex-1 text-sm text-[var(--color-bo-ink-2)]">{t.consoleMasterText}</p>
      <p role="note" className="flex items-start gap-2 text-xs text-[var(--color-bo-ink-2)]">
        <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>{open ? t.consoleMasterInfoOpen : t.consoleMasterInfoClosed}</span>
      </p>
      {open ? (
        <a href={master.url} target="_blank" rel="noopener noreferrer" className={ghostBtn}>
          {t.consoleOpen} <ExternalLink className="h-4 w-4" aria-hidden />
          <span className="sr-only">{t.consoleExternal}</span>
        </a>
      ) : (
        <button type="button" disabled className={cn(ghostBtn, "cursor-not-allowed opacity-50 hover:bg-transparent")}>
          {t.consoleOpen}
        </button>
      )}
    </Card>
  );
}

/** I tre gruppi di schede di HUB-02 nell'ambiente di test: operatori, membri, console di Keycloak. */
export function TestUsers({
  mode,
  user,
  memberUser = null,
  masterConsole = null,
}: {
  mode: TestMode;
  /** Sessione dell'operatore (realm operatori), se c'è. */
  user: Pick<SessionUser, "username" | "name"> | null;
  /** Sessione del membro (realm membri), se c'è: indipendente da quella dell'operatore. */
  memberUser?: Pick<SessionUser, "username" | "name"> | null;
  /** Console del realm master: la scheda compare solo se c'è (ADR-055, Q-727). */
  masterConsole?: MasterConsole | null;
}) {
  const seedHint = `${OPERATORS_TOTP_SEED.slice(0, 8)}…${OPERATORS_TOTP_SEED.slice(-8)}`;
  return (
    <div className="space-y-8" data-testid="test-users">
      <section aria-labelledby="tu-operators">
        <GroupHeader id="tu-operators" title={t.operators} passwordWhat={t.operatorsPassword} password={OPERATORS_PASSWORD} />
        <div className="mt-3">
          <OtpCard seedHint={seedHint} />
        </div>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {TEST_OPERATORS.map((op) => (
            <OperatorCard key={op.username} op={op} user={user} />
          ))}
        </div>
      </section>

      <section aria-labelledby="tu-members">
        <GroupHeader id="tu-members" title={t.members} passwordWhat={t.membersPassword} password={MEMBERS_PASSWORD} />
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {TEST_MEMBERS.map((m) => (
            <MemberCard key={m.username} m={m} user={memberUser} />
          ))}
        </div>
        <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">{t.membersNote}</p>
      </section>

      <section aria-labelledby="tu-consoles">
        <h3 id="tu-consoles" className="text-lg font-semibold">
          {t.consoles}
        </h3>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {mode.operators !== null ? (
            <ConsoleCard
              title={t.consoleOperators}
              text={t.consoleOperatorsText}
              realm={mode.operators.realm}
              admin={KEYCLOAK_ADMINS.operators.username}
              href={mode.operators.url}
            />
          ) : null}
          {mode.members !== null ? (
            <ConsoleCard
              title={t.consoleMembers}
              text={t.consoleMembersText}
              realm={mode.members.realm}
              admin={KEYCLOAK_ADMINS.members.username}
              href={mode.members.url}
            />
          ) : null}
          {masterConsole !== null ? <MasterConsoleCard master={masterConsole} /> : null}
        </div>
        <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">{t.consolesNote}</p>
      </section>
    </div>
  );
}
