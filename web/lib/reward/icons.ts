import { Bike, Gift, HeartHandshake, Home, Sparkles, Ticket, type LucideIcon } from "lucide-react";

// Icone delle categorie premio (seed reward-categories.json → campo `icon`), condivise da backoffice e portale.
const CATEGORY_ICON: Record<string, LucideIcon> = {
  home: Home,
  sparkles: Sparkles,
  bike: Bike,
  "heart-handshake": HeartHandshake,
  ticket: Ticket,
};

export function categoryIcon(icon: string | null | undefined): LucideIcon {
  return (icon && CATEGORY_ICON[icon]) || Gift;
}
