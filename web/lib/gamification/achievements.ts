import {
  Award,
  Flame,
  Gauge,
  Gem,
  Leaf,
  ShoppingBag,
  ShoppingCart,
  Sparkles,
  Star,
  Trophy,
  type LucideIcon,
} from "lucide-react";
import type { Achievement, AchievementMetric, AchievementPeriod } from "@/lib/api/types";
import { actionLabel } from "@/lib/campaign/describe";

// Obiettivi e badge (docs/08 §BO-15, docs/09 §PT-09, docs/03 §8): icone, frase generata, periodi in parole.

const ICONS: Record<string, LucideIcon> = {
  "shopping-cart": ShoppingCart,
  "shopping-bag": ShoppingBag,
  flame: Flame,
  leaf: Leaf,
  gauge: Gauge,
  gem: Gem,
  sparkles: Sparkles,
  award: Award,
  star: Star,
  trophy: Trophy,
};

export const ICON_CHOICES = Object.keys(ICONS);

export function gameIcon(icon: string | null | undefined): LucideIcon {
  return (icon && ICONS[icon]) || Award;
}

export const METRIC_LABEL: Record<AchievementMetric, string> = {
  COUNT: "Conteggio",
  SUM: "Somma di un campo",
  DISTINCT_TYPES: "Tipi distinti",
  STREAK: "Serie consecutiva",
};

export const PERIOD_LABEL: Record<AchievementPeriod, string> = {
  EVER: "Sempre",
  MONTH: "Mese",
  EDITION: "Edizione",
};

/** Periodo come lo legge il membro: "questo mese", "in questa edizione"; niente per "sempre". */
export function periodPhrase(period: AchievementPeriod): string | null {
  if (period === "MONTH") return "questo mese";
  if (period === "EDITION") return "in questa edizione";
  return null;
}

type Draft = Pick<Achievement, "metric" | "actionTypes" | "target" | "period" | "repeatable"> &
  Partial<Pick<Achievement, "sumField" | "streakUnit">>;

/**
 * Frase generata dell'editor (docs/08 §BO-15): "Completa **3** volte **Acquisto completato** **nello stesso mese**".
 * I marcatori ** li rende GeneratedText in grassetto.
 */
export function describeAchievement(a: Draft): string {
  const types = a.actionTypes.length ? a.actionTypes.map(actionLabel) : ["(nessuna azione)"];
  const what = types.map((t) => `**${t}**`).join(" o ");
  const when = a.period === "MONTH" ? " **nello stesso mese**" : a.period === "EDITION" ? " **nella stessa edizione**" : "";
  let sentence: string;
  switch (a.metric) {
    case "SUM":
      sentence = `Raggiungi **${a.target}** sommando ${a.sumField ? `**${a.sumField.replace(/^.*data\./, "")}**` : "un campo"} di ${what}${when}`;
      break;
    case "DISTINCT_TYPES":
      sentence = `Fai **${a.target}** azioni diverse tra ${what}${when}`;
      break;
    case "STREAK":
      sentence = `Fai ${what} per **${a.target} ${a.streakUnit === "WEEK" ? "settimane" : "giorni"} di fila**${when}`;
      break;
    default:
      sentence = a.target === 1 ? `Fai ${what} per la prima volta${when}` : `Completa **${a.target}** volte ${what}${when}`;
  }
  const again = a.repeatable ? (a.period === "EVER" ? "" : "; si può ripetere ogni periodo") : "; una volta sola";
  return sentence + again + ".";
}

/** Pallini della serie (PT-09): quanti pieni su quanti. */
export function streakDots(value: number, target: number): boolean[] {
  return Array.from({ length: Math.max(0, target) }, (_, i) => i < value);
}
