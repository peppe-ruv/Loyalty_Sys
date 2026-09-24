"use client";

import { useState } from "react";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import { AURORA, COLOR_KEYS, contrastChecks, isHex, normalizeTheme, themeStyle, type ColorKey, type PortalTheme } from "@/lib/theme/theme";
import type { ContentDisplay } from "@/lib/content/types";
import { QueryState } from "@/components/bo/QueryState";
import { Can, useCan } from "@/components/bo/Can";
import { PhoneFrame } from "@/components/bo/PhoneFrame";
import { Field, INPUT, Section } from "@/components/bo/FormBits";
import { PageHeader } from "@/components/bo/primitives";
import { ThemeProvider } from "@/components/shared/ThemeContext";
import { MemberCard } from "@/components/shared/content/MemberCard";
import { ContentCard } from "@/components/shared/content/ContentCard";
import { cn } from "@/lib/cn";

// BO-20 Tema e brand (docs/08 §BO-20, F-THM-01): nome del programma, logo, colori night/primary/secondary/coin/bg, testi
// hero, nomi delle valute nel portale. Anteprima dal vivo di PT-01 in PhoneFrame con le variabili CSS applicate e gli
// stessi componenti del portale; verifica del contrasto AA in linea (il servizio blocca con THEME_CONTRAST_TOO_LOW);
// "Ripristina Aurora" riporta i valori di docs/07 §5.3 nel modulo (si salva con "Salva").
const COLOR_LABEL: Record<ColorKey, string> = {
  primary: "Primario",
  secondary: "Secondario",
  coin: "Moneta",
  night: "Testo (notte)",
  bg: "Sfondo",
};

const SAMPLE_HERO: ContentDisplay = {
  code: "ANTEPRIMA", kind: "CARD", placement: "HOME_HERO", title: "Gira la Ruota d'Autunno",
  body: "Ogni giorno una giocata gratis.", imageUrl: null, ctaLabel: "Gioca ora", ctaTarget: "/portal/play",
  linkType: "NONE", linkCode: null, style: { tone: "SECONDARY" },
};
const SAMPLE_GRID: ContentDisplay = {
  code: "ANTEPRIMA-2", kind: "CARD", placement: "HOME_GRID", title: "Porta un amico", body: "Premio per entrambi.",
  imageUrl: null, ctaLabel: "Invita", ctaTarget: "/portal/invite", linkType: "NONE", linkCode: null, style: { tone: "COIN" },
};

export default function ThemePage() {
  const theme = useLhQuery<PortalTheme>("engagement", "/v1/theme");
  return (
    <div>
      <PageHeader title="Tema e brand" subtitle="Nome, colori e testi del portale: si applicano subito, senza nuovo rilascio." />
      <QueryState query={theme} service="engagement">
        {(t) => <ThemeEditor key={t.version ?? 0} initial={normalizeTheme(t)} version={t.version ?? 0} />}
      </QueryState>
    </div>
  );
}

function ThemeEditor({ initial, version }: { initial: PortalTheme; version: number }) {
  const canWrite = useCan("content.write");
  const [d, setD] = useState<PortalTheme>(initial);
  const [saved, setSaved] = useState(false);
  const set = <K extends keyof PortalTheme>(k: K, v: PortalTheme[K]) => { setD((x) => ({ ...x, [k]: v })); setSaved(false); };
  const setColor = (k: ColorKey, v: string) => set("colors", { ...d.colors, [k]: v });

  const save = useLhMutation<PortalTheme, unknown>("engagement", "PUT", () => "/v1/theme", { onSuccess: () => setSaved(true) });
  const err = save.error as LhError | null;
  const fieldError = (name: string) => err?.errors.find((e) => e.field === name)?.message;
  const checks = contrastChecks(d.colors);
  const colorsValid = COLOR_KEYS.every((k) => isHex(d.colors[k]));
  const blocked = !colorsValid || checks.some((c) => !c.ok) || !d.programName.trim();
  const previewTheme = normalizeTheme(d);

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate({ ...d, version });
        }}
      >
        <fieldset disabled={!canWrite} className="space-y-4">
          <Section title="Programma">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Nome del programma" hint={fieldError("programName")}>
                <input value={d.programName} onChange={(e) => set("programName", e.target.value)} className={INPUT} maxLength={40} required />
              </Field>
              <Field label="Logo (percorso o URL https, facoltativo)" hint={fieldError("logoUrl")}>
                <input value={d.logoUrl ?? ""} onChange={(e) => set("logoUrl", e.target.value || null)} className={INPUT} placeholder="/demo/logo-aurora.svg" />
              </Field>
            </div>
            <Field label="Sottotitolo"><input value={d.tagline ?? ""} onChange={(e) => set("tagline", e.target.value || null)} className={INPUT} /></Field>
          </Section>

          <Section title="Colori">
            <div className="grid gap-3 sm:grid-cols-2">
              {COLOR_KEYS.map((k) => (
                <Field key={k} label={COLOR_LABEL[k]} hint={fieldError(`colors.${k}`)}>
                  <span className="flex items-center gap-2">
                    <input
                      type="color"
                      aria-label={`${COLOR_LABEL[k]}: selettore`}
                      value={isHex(d.colors[k]) ? d.colors[k] : "#000000"}
                      onChange={(e) => setColor(k, e.target.value.toUpperCase())}
                      className="h-8 w-10 cursor-pointer rounded border border-[var(--color-bo-border)] bg-white"
                    />
                    <input
                      value={d.colors[k]}
                      onChange={(e) => setColor(k, e.target.value.trim())}
                      className={cn(INPUT, "font-mono", !isHex(d.colors[k]) && "border-red-400")}
                      aria-label={`${COLOR_LABEL[k]}: esadecimale`}
                    />
                  </span>
                </Field>
              ))}
            </div>
            <ul className="flex flex-wrap gap-2" aria-label="Verifica contrasto">
              {checks.map((c) => (
                <li key={c.key} className={cn("rounded-full px-2.5 py-1 text-xs font-medium", c.ok ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-800")}>
                  {c.label}: {c.ratio != null ? `${c.ratio.toFixed(2).replace(".", ",")}:1` : "—"} {c.ok ? "· AA ✓" : "· sotto 4,5:1"}
                </li>
              ))}
            </ul>
          </Section>

          <Section title="Testi e valute">
            <Field label="Titolo in Home"><input value={d.heroTitle ?? ""} onChange={(e) => set("heroTitle", e.target.value || null)} className={INPUT} maxLength={60} /></Field>
            <Field label="Sottotitolo in Home"><input value={d.heroSubtitle ?? ""} onChange={(e) => set("heroSubtitle", e.target.value || null)} className={INPUT} maxLength={120} /></Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Nome dei punti (PTS)">
                <input value={d.currencyNames.PTS} onChange={(e) => set("currencyNames", { ...d.currencyNames, PTS: e.target.value })} className={INPUT} maxLength={24} />
              </Field>
              <Field label="Nome dei punti status (STS)">
                <input value={d.currencyNames.STS} onChange={(e) => set("currencyNames", { ...d.currencyNames, STS: e.target.value })} className={INPUT} maxLength={24} />
              </Field>
            </div>
          </Section>
        </fieldset>

        {err && !err.errors.length ? <p className="rounded border border-red-200 bg-red-50 p-2 text-sm text-red-800">{err.code}: {err.detail}</p> : null}
        {err?.code === "THEME_CONTRAST_TOO_LOW" ? <p className="text-sm text-red-700">Contrasto insufficiente: il tema non è stato salvato.</p> : null}
        <div className="flex flex-wrap items-center gap-3">
          <Can capability="content.write" mode="disable">
            <button type="submit" disabled={save.isPending || blocked} className="rounded bg-[var(--color-bo-accent)] px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50">
              {save.isPending ? "Salvataggio…" : "Salva"}
            </button>
          </Can>
          <Can capability="content.write" mode="disable">
            <button type="button" onClick={() => { setD(AURORA); setSaved(false); }} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
              Ripristina Aurora
            </button>
          </Can>
          {saved ? <span className="text-sm text-emerald-700">Salvato: il portale lo mostra entro un minuto.</span> : null}
          {blocked && !save.isPending ? <span className="text-xs text-[var(--color-bo-ink-2)]">Correggi colori e contrasto per salvare.</span> : null}
        </div>
      </form>

      <div className="lg:sticky lg:top-4 lg:self-start">
        <PhoneFrame label="Anteprima dal vivo della Home" programName={previewTheme.programName} style={themeStyle(previewTheme)}>
          <ThemeProvider theme={previewTheme}>
            <div className="space-y-3">
              <div>
                <p className="text-base font-semibold text-[var(--color-pt-night)]">Ciao Marco 👋</p>
                {previewTheme.heroTitle ? <p className="text-xs text-[var(--color-pt-night)]/70">{previewTheme.heroTitle}</p> : null}
              </div>
              <MemberCard memberName="Marco Bianchi" memberId="MBR-000002" pts={1850} tier="SILVER" />
              <ContentCard content={SAMPLE_HERO} variant="hero" preview />
              <div className="grid grid-cols-2 gap-2">
                <ContentCard content={SAMPLE_GRID} variant="grid" preview />
                <div className="flex flex-col justify-center gap-2 rounded-2xl bg-white p-3 text-xs text-[var(--color-pt-night)] shadow-sm">
                  <span>1.420 {previewTheme.currencyNames.STS}</span>
                  <span className="rounded-full bg-[var(--color-pt-primary)] px-3 py-1.5 text-center font-semibold text-[var(--color-pt-night)]">Richiedi</span>
                </div>
              </div>
            </div>
          </ThemeProvider>
        </PhoneFrame>
      </div>
    </div>
  );
}
