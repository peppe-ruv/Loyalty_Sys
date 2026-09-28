import {
  Award,
  BadgeCheck,
  Bell,
  Bike,
  BookOpen,
  Cake,
  Calendar,
  CalendarCheck,
  Camera,
  Car,
  ClipboardList,
  Coffee,
  CreditCard,
  Dumbbell,
  FileText,
  Footprints,
  Gauge,
  Gift,
  Globe,
  GraduationCap,
  Handshake,
  Heart,
  HelpCircle,
  House,
  Leaf,
  Lightbulb,
  LogIn,
  Mail,
  MapPin,
  MessageSquare,
  Music,
  PackageCheck,
  Percent,
  Phone,
  Plug,
  QrCode,
  Receipt,
  Recycle,
  RotateCcw,
  ScanLine,
  Share2,
  ShieldCheck,
  ShoppingCart,
  Smartphone,
  Sparkles,
  Star,
  Store,
  Tag,
  Target,
  ThumbsUp,
  Ticket,
  TrendingUp,
  Trophy,
  Truck,
  UserCheck,
  UserPlus,
  Users,
  Utensils,
  Wallet,
  Zap,
  type LucideIcon,
} from "lucide-react";

// Elenco chiuso delle icone dei tipi azione (BO-09 sezione 1, BO-06 «2 · Quando»). Solo nella UI: il servizio salva
// il nome come testo libero (SPEC-GAP: Q-431). Import espliciti, mai la mappa `icons` completa di lucide (peso del
// bundle). Contiene tutte le icone di `seed/event-types.json`: toglierne una romperebbe i tipi di sistema.

export interface ActionIconChoice {
  /** Nome lucide in kebab-case, come nel seed. */
  name: string;
  /** Etichetta in italiano, letta anche dalle tecnologie assistive. */
  label: string;
  Icon: LucideIcon;
  /** Parole aggiuntive per la ricerca. */
  keywords?: string;
}

/** Le 19 icone del seed dei tipi di sistema. */
export const SEED_ACTION_ICONS = [
  "shopping-cart",
  "rotate-ccw",
  "file-text",
  "credit-card",
  "gauge",
  "smartphone",
  "clipboard-list",
  "help-circle",
  "star",
  "mail",
  "user-plus",
  "user-check",
  "cake",
  "trending-up",
  "gift",
  "target",
  "award",
  "users",
  "package-check",
] as const;

export const ACTION_ICONS: readonly ActionIconChoice[] = [
  // Seed
  { name: "shopping-cart", label: "Carrello", Icon: ShoppingCart, keywords: "acquisto spesa ordine" },
  { name: "rotate-ccw", label: "Reso", Icon: RotateCcw, keywords: "restituzione rimborso" },
  { name: "file-text", label: "Documento", Icon: FileText, keywords: "bolletta contratto" },
  { name: "credit-card", label: "Carta di pagamento", Icon: CreditCard, keywords: "pagamento domiciliazione" },
  { name: "gauge", label: "Contatore", Icon: Gauge, keywords: "lettura autolettura consumo" },
  { name: "smartphone", label: "Telefono", Icon: Smartphone, keywords: "app accesso" },
  { name: "clipboard-list", label: "Questionario", Icon: ClipboardList, keywords: "survey sondaggio" },
  { name: "help-circle", label: "Domanda", Icon: HelpCircle, keywords: "quiz aiuto" },
  { name: "star", label: "Stella", Icon: Star, keywords: "recensione voto" },
  { name: "mail", label: "Busta", Icon: Mail, keywords: "newsletter posta" },
  { name: "user-plus", label: "Nuovo membro", Icon: UserPlus, keywords: "iscrizione registrazione" },
  { name: "user-check", label: "Profilo completo", Icon: UserCheck, keywords: "verifica" },
  { name: "cake", label: "Torta", Icon: Cake, keywords: "compleanno festa" },
  { name: "trending-up", label: "Crescita", Icon: TrendingUp, keywords: "livello salita" },
  { name: "gift", label: "Regalo", Icon: Gift, keywords: "premio vincita" },
  { name: "target", label: "Obiettivo", Icon: Target, keywords: "traguardo" },
  { name: "award", label: "Medaglia", Icon: Award, keywords: "badge" },
  { name: "users", label: "Persone", Icon: Users, keywords: "amico referral presentazione" },
  { name: "package-check", label: "Pacco consegnato", Icon: PackageCheck, keywords: "consegna ritiro" },
  // Selezione curata
  { name: "map-pin", label: "Luogo", Icon: MapPin, keywords: "visita negozio posizione" },
  { name: "store", label: "Negozio", Icon: Store, keywords: "punto vendita visita" },
  { name: "calendar", label: "Calendario", Icon: Calendar, keywords: "evento data" },
  { name: "calendar-check", label: "Presenza a un evento", Icon: CalendarCheck, keywords: "partecipazione prenotazione" },
  { name: "ticket", label: "Biglietto", Icon: Ticket, keywords: "evento ingresso" },
  { name: "qr-code", label: "Codice QR", Icon: QrCode, keywords: "scansione" },
  { name: "scan-line", label: "Scansione", Icon: ScanLine, keywords: "scontrino codice" },
  { name: "handshake", label: "Stretta di mano", Icon: Handshake, keywords: "partner accordo" },
  { name: "heart", label: "Cuore", Icon: Heart, keywords: "preferito" },
  { name: "thumbs-up", label: "Mi piace", Icon: ThumbsUp, keywords: "gradimento" },
  { name: "message-square", label: "Messaggio", Icon: MessageSquare, keywords: "commento chat" },
  { name: "share-2", label: "Condivisione", Icon: Share2, keywords: "social" },
  { name: "camera", label: "Fotocamera", Icon: Camera, keywords: "foto" },
  { name: "leaf", label: "Foglia", Icon: Leaf, keywords: "ambiente sostenibilità" },
  { name: "recycle", label: "Riciclo", Icon: Recycle, keywords: "ambiente raccolta" },
  { name: "lightbulb", label: "Lampadina", Icon: Lightbulb, keywords: "energia idea risparmio" },
  { name: "plug", label: "Presa elettrica", Icon: Plug, keywords: "energia fornitura" },
  { name: "house", label: "Casa", Icon: House, keywords: "abitazione fornitura" },
  { name: "log-in", label: "Accesso", Icon: LogIn, keywords: "login entrata" },
  { name: "bell", label: "Campanella", Icon: Bell, keywords: "notifica avviso" },
  { name: "phone", label: "Chiamata", Icon: Phone, keywords: "telefonata assistenza" },
  { name: "globe", label: "Sito web", Icon: Globe, keywords: "online internet" },
  { name: "receipt", label: "Scontrino", Icon: Receipt, keywords: "ricevuta acquisto" },
  { name: "wallet", label: "Portafoglio", Icon: Wallet, keywords: "pagamento" },
  { name: "tag", label: "Etichetta", Icon: Tag, keywords: "prezzo offerta" },
  { name: "percent", label: "Sconto", Icon: Percent, keywords: "promozione" },
  { name: "truck", label: "Spedizione", Icon: Truck, keywords: "consegna" },
  { name: "trophy", label: "Coppa", Icon: Trophy, keywords: "vittoria gara" },
  { name: "badge-check", label: "Verificato", Icon: BadgeCheck, keywords: "conferma" },
  { name: "sparkles", label: "Novità", Icon: Sparkles, keywords: "speciale" },
  { name: "shield-check", label: "Sicurezza", Icon: ShieldCheck, keywords: "protezione assicurazione" },
  { name: "book-open", label: "Libro", Icon: BookOpen, keywords: "lettura corso" },
  { name: "graduation-cap", label: "Formazione", Icon: GraduationCap, keywords: "corso scuola" },
  { name: "footprints", label: "Passi", Icon: Footprints, keywords: "camminata sport" },
  { name: "bike", label: "Bicicletta", Icon: Bike, keywords: "sport mobilità" },
  { name: "dumbbell", label: "Palestra", Icon: Dumbbell, keywords: "sport allenamento" },
  { name: "car", label: "Auto", Icon: Car, keywords: "mobilità viaggio" },
  { name: "coffee", label: "Caffè", Icon: Coffee, keywords: "bar colazione" },
  { name: "utensils", label: "Ristorante", Icon: Utensils, keywords: "cena pranzo" },
  { name: "music", label: "Musica", Icon: Music, keywords: "concerto" },
  { name: "zap", label: "Fulmine", Icon: Zap, keywords: "generica energia" },
];

const BY_NAME = new Map(ACTION_ICONS.map((c) => [c.name, c]));

/** Icona di ripiego per un nome assente o fuori elenco. */
export const FALLBACK_ACTION_ICON: LucideIcon = Zap;

export function isKnownActionIcon(name: string | null | undefined): boolean {
  return !!name && BY_NAME.has(name);
}

/** Componente dell'icona; ⚡ per un nome assente o fuori elenco. */
export function actionIcon(name: string | null | undefined): LucideIcon {
  return (name && BY_NAME.get(name)?.Icon) || FALLBACK_ACTION_ICON;
}

export function actionIconLabel(name: string | null | undefined): string | null {
  return (name && BY_NAME.get(name)?.label) || null;
}

/** Ricerca per etichetta, nome o parole chiave (minuscole, senza accenti). */
export function searchActionIcons(query: string): readonly ActionIconChoice[] {
  const q = fold(query.trim());
  if (!q) return ACTION_ICONS;
  return ACTION_ICONS.filter((c) => fold(`${c.label} ${c.name} ${c.keywords ?? ""}`).includes(q));
}

function fold(s: string): string {
  return s.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
}
