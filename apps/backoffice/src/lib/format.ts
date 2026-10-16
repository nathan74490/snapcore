// Mise en forme pure, sans dépendance à Next ni à Prisma (testée par src/lib/format.test.ts).

const DATE_TIME = new Intl.DateTimeFormat("fr-FR", { dateStyle: "short", timeStyle: "short", timeZone: "Europe/Paris" });

export function formatDateTime(date: Date | null | undefined): string {
  return date ? DATE_TIME.format(date) : "-";
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
