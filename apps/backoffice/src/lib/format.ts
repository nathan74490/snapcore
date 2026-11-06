// Mise en forme pure, sans dépendance à Next ni à Prisma (testée par src/lib/format.test.ts).

const DATE_TIME = new Intl.DateTimeFormat("fr-FR", { dateStyle: "short", timeStyle: "short", timeZone: "Europe/Paris" });

export function formatDateTime(date: Date | null | undefined): string {
  return date ? DATE_TIME.format(date) : "-";
}

// Colonne DATE (date de naissance, ADR-029) : Prisma la renvoie à minuit UTC. Formatée en UTC,
// sinon un fuseau à l'ouest de Greenwich afficherait la veille.
const DATE_ONLY = new Intl.DateTimeFormat("fr-FR", { dateStyle: "short", timeZone: "UTC" });

export function formatDateOnly(date: Date | null | undefined): string | null {
  return date ? DATE_ONLY.format(date) : null;
}

export function truncate(text: string, max = 80): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

export type MessageKind = "TEXT" | "SNAP_PHOTO" | "SNAP_PHOTO_VIDEO";

/** Aperçu d'un message pour un modérateur : le texte, ou la nature du Snap (le média n'est pas affiché). */
export function describeMessage(message: {
  contentType: MessageKind;
  content: string | null;
  mediaKey: string | null;
  moderatedAt: Date | null;
}): string {
  if (message.moderatedAt) return "Retiré par la modération";
  if (message.contentType === "TEXT") return message.content ?? "";
  const kind = message.contentType === "SNAP_PHOTO" ? "Snap photo" : "Snap vidéo";
  return message.mediaKey ? kind : `${kind} (expiré)`;
}

export const REPORT_STATUS_LABEL = { PENDING: "En attente", RESOLVED: "Résolu", DISMISSED: "Classé sans suite" } as const;
export const TARGET_TYPE_LABEL = { USER: "Compte", MESSAGE: "Message", STORY_ITEM: "Story" } as const;

export const FRIENDSHIP_STATUS_LABEL = { PENDING: "Demande en attente", ACCEPTED: "Amis", BLOCKED: "Bloqué", DECLINED: "Refusée" } as const;

// Partage de position sur la Snap Map (ADR-031). La position elle-même n'est jamais en base.
export const LOCATION_SHARING_LABEL = {
  GHOST: "Mode fantôme",
  ALL_FRIENDS: "Tous ses amis",
  FRIENDS_EXCEPT: "Ses amis, sauf certains",
  ONLY_FRIENDS: "Seulement certains amis",
} as const;

export const PAGE_SIZE = 50;

/** Numéro de page lu dans l'URL : entier ≥ 1, 1 par défaut. */
export function parsePage(raw: string | undefined): number {
  const page = Number(raw);
  return Number.isInteger(page) && page >= 1 ? page : 1;
}

export type SubscriptionState = "free" | "active" | "expiring" | "expired";

/** État d'un abonnement SnapCore+ (ADR-025) ; « expiring » : fin dans moins de 7 jours. */
export function subscriptionState(
  user: { subscriptionTier: string; premiumUntil: Date | null },
  now: Date,
): SubscriptionState {
  if (!user.premiumUntil) return user.subscriptionTier === "premium" ? "active" : "free";
  const left = user.premiumUntil.getTime() - now.getTime();
  if (left <= 0) return "expired";
  return left < 7 * 24 * 60 * 60 * 1000 ? "expiring" : "active";
}

export const SUBSCRIPTION_STATE_LABEL = { free: "Gratuit", active: "SnapCore+", expiring: "SnapCore+ (fin < 7 j)", expired: "SnapCore+ expiré" } as const;

const DAY_KEY = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Paris" });
const DAY_LABEL = new Intl.DateTimeFormat("fr-FR", { day: "2-digit", month: "2-digit", timeZone: "Europe/Paris" });

export interface DayCount {
  key: string;
  label: string;
  count: number;
}

/**
 * Série quotidienne des `days` derniers jours (heure de Paris), aujourd'hui compris : chaque jour
 * sans ligne vaut 0. `rows` vient d'un GROUP BY sur le jour au format AAAA-MM-JJ.
 */
export function dailySeries(rows: { day: string; count: number }[], days: number, now: Date): DayCount[] {
  const counts = new Map(rows.map((row) => [row.day, row.count]));
  const series: DayCount[] = [];
  for (let i = days - 1; i >= 0; i--) {
    const date = new Date(now.getTime() - i * 24 * 60 * 60 * 1000);
    const key = DAY_KEY.format(date);
    series.push({ key, label: DAY_LABEL.format(date), count: counts.get(key) ?? 0 });
  }
  return series;
}

// Les 11 clés d'un avatar avataaars (ADR-028), dans l'ordre de AvatarOptions.
const AVATAR_KEYS = [
  "top", "hairColor", "skinColor", "eyes", "eyebrows", "mouth",
  "facialHair", "accessories", "clothing", "clothesColor", "backgroundColor",
] as const;

/** Paramètres de GET /avatars/avataaars.png pour un avatar enregistré, ou null s'il est incomplet. */
export function avatarQuery(avatar: unknown, size = 128): string | null {
  if (!avatar || typeof avatar !== "object") return null;
  const params = new URLSearchParams();
  for (const key of AVATAR_KEYS) {
    const value = (avatar as Record<string, unknown>)[key];
    if (typeof value !== "string" || !value) return null;
    params.set(key, value);
  }
  params.set("size", String(size));
  return params.toString();
}
