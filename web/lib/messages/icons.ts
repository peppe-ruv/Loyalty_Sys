import {
  Award,
  Bell,
  Cake,
  CircleX,
  Coins,
  Gift,
  Hourglass,
  PackageCheck,
  Sparkles,
  Ticket,
  TrendingDown,
  TrendingUp,
  Trophy,
  Users,
  type LucideIcon,
} from "lucide-react";
import type { MessageCategory } from "./types";

// Icone dei messaggi (campo `icon` dei template, seed message-templates.json), condivise da PT-12 e BO-19.
// Un nome sconosciuto ricade sull'icona della categoria (docs/09 PT-12: "icona per categoria").
export const MESSAGE_ICONS: Record<string, LucideIcon> = {
  coins: Coins,
  hourglass: Hourglass,
  "trending-up": TrendingUp,
  "trending-down": TrendingDown,
  "package-check": PackageCheck,
  gift: Gift,
  "circle-x": CircleX,
  ticket: Ticket,
  trophy: Trophy,
  award: Award,
  users: Users,
  sparkles: Sparkles,
  cake: Cake,
  bell: Bell,
};

export const ICON_CHOICES = Object.keys(MESSAGE_ICONS);

const CATEGORY_ICON: Record<MessageCategory, LucideIcon> = {
  POINTS: Coins,
  TIER: TrendingUp,
  REWARD: Gift,
  GAME: Trophy,
  PROGRAM: Sparkles,
};

export function messageIcon(icon: string | null | undefined, category: MessageCategory | null | undefined): LucideIcon {
  if (icon && MESSAGE_ICONS[icon]) return MESSAGE_ICONS[icon];
  return (category && CATEGORY_ICON[category]) || Bell;
}
