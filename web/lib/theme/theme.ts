import type { CSSProperties } from "react";

// Tema del portale (docs/07 §5.3, docs/09 §1, F-THM-01): letto da engagement e applicato come variabili CSS.

export const COLOR_KEYS = ["primary", "secondary", "coin", "night", "bg"] as const;
export type ColorKey = (typeof COLOR_KEYS)[number];

export interface PortalTheme {
  programName: string;
  tagline: string | null;
  logoUrl: string | null;
  colors: Record<ColorKey, string>;
  heroTitle: string | null;
  heroSubtitle: string | null;
  fontDisplay: string | null;
  currencyNames: { PTS: string; STS: string };
  version?: number;
  updatedAt?: string | null;
}

/** Aurora (docs/07 §5.3): il ripiego quando engagement dorme o non risponde, e "Ripristina Aurora" in BO-20. */
export const AURORA: PortalTheme = {
  programName: "Club Aurora",
  tagline: "Il programma fedeltà che premia ogni gesto",
  logoUrl: null,
  colors: { primary: "#1FB98F", secondary: "#7A5CFA", coin: "#FFB547", night: "#0E1B2C", bg: "#F3F7F9" },
  heroTitle: "Ogni gesto conta",
  heroSubtitle: "Accumula punti, sali di livello, scegli il tuo premio.",
  fontDisplay: null,
  currencyNames: { PTS: "punti", STS: "punti status" },
};

export const MIN_CONTRAST = 4.5;
const HEX = /^#[0-9a-f]{6}$/i;

export function isHex(value: string | null | undefined): value is string {
  return typeof value === "string" && HEX.test(value);
}

/** Tema ricevuto dal servizio, con i campi mancanti o non validi presi da Aurora. */
export function normalizeTheme(t: Partial<PortalTheme> | null | undefined): PortalTheme {
  if (!t) return AURORA;
  const colors = { ...AURORA.colors };
  for (const k of COLOR_KEYS) if (isHex(t.colors?.[k])) colors[k] = t.colors![k];
  return {
    ...AURORA,
    ...t,
    programName: t.programName?.trim() || AURORA.programName,
    colors,
    currencyNames: { PTS: t.currencyNames?.PTS || AURORA.currencyNames.PTS, STS: t.currencyNames?.STS || AURORA.currencyNames.STS },
  };
}

/** Variabili CSS dei token del portale (--color-pt-*) da applicare a un contenitore. */
export function themeStyle(t: PortalTheme): CSSProperties {
  return Object.fromEntries(COLOR_KEYS.map((k) => [`--color-pt-${k}`, t.colors[k]])) as CSSProperties;
}

function channel(v: number): number {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * channel((n >> 16) & 0xff) + 0.7152 * channel((n >> 8) & 0xff) + 0.0722 * channel(n & 0xff);
}

/** Rapporto di contrasto WCAG 2.x (1…21), come il controllo del servizio. */
export function contrast(a: string, b: string): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/**
 * Coppie verificate al salvataggio (engagement: `THEME_CONTRAST_TOO_LOW`): testo (`night`) su primario e su sfondo.
 * SPEC-GAP: Q-79 — "testo/primario" letto come il colore del testo del portale su primary (più lo sfondo).
 */
export function contrastChecks(colors: Record<ColorKey, string>): { key: ColorKey; label: string; ratio: number | null; ok: boolean }[] {
  return (["primary", "bg"] as const).map((k) => {
    const ratio = isHex(colors.night) && isHex(colors[k]) ? contrast(colors.night, colors[k]) : null;
    return { key: k, label: k === "primary" ? "Testo su primario" : "Testo su sfondo", ratio, ok: ratio != null && ratio >= MIN_CONTRAST };
  });
}
