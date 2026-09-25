import type { MessageContentType } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";
import type { MediaPurpose } from "../../media/domain/media-purpose";

export interface Message {
  id: string;
  senderId: string;
  recipientId: string;
  contentType: MessageContentType;
  content: string | null;
  mediaKey: string | null;
  createdAt: Date;
  openedAt: Date | null;
  scheduledPurgeAt: Date | null;
  retainedNotifiedAt: Date | null;
}

export type SnapContentType = Exclude<MessageContentType, "TEXT">;

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

// Snap jamais ouvert : purgé 30 jours après l'envoi (ADR-017).
export const UNOPENED_SNAP_RETENTION_MS = 30 * DAY;
// Snap ouvert : purgé 24h après la première ouverture (choix de simplification assumé, ADR-017).
export const OPENED_SNAP_RETENTION_MS = 24 * HOUR;
// Fenêtre pendant laquelle une URL de lecture peut être redemandée après la 1re ouverture :
// requêtes concurrentes et téléchargement échoué (ADR-009).
export const VIEW_GRACE_PERIOD_MS = 5 * 60 * 1000;
// Durée de vie de l'URL pré-signée de lecture : juste de quoi démarrer le téléchargement (ADR-009).
export const VIEW_URL_TTL_SECONDS = 60;

export const MAX_TEXT_LENGTH = 2000;

// Upload attendu pour chaque type de Snap : SNAP_PHOTO_VIDEO est une vidéo (avec son).
export const UPLOAD_PURPOSE_BY_SNAP_TYPE: Record<SnapContentType, MediaPurpose> = {
  SNAP_PHOTO: "SNAP_PHOTO",
  SNAP_PHOTO_VIDEO: "SNAP_VIDEO",
};

export function parseTextContent(raw: string): string {
  const content = raw.trim();
  if (content.length === 0) throw new ValidationError("Le message est vide");
  if (content.length > MAX_TEXT_LENGTH) {
    throw new ValidationError(`Le message dépasse ${MAX_TEXT_LENGTH} caractères`);
  }
  return content;
}

export function snapMediaKey(messageId: string): string {
  return `messages/${messageId}`;
}

export function initialPurgeDate(createdAt: Date): Date {
  return new Date(createdAt.getTime() + UNOPENED_SNAP_RETENTION_MS);
}

// À l'ouverture, la purge est rapprochée à openedAt + 24h, jamais repoussée (ADR-017).
export function purgeDateAfterOpening(message: Pick<Message, "scheduledPurgeAt">, openedAt: Date): Date {
  const afterOpening = new Date(openedAt.getTime() + OPENED_SNAP_RETENTION_MS);
  return message.scheduledPurgeAt && message.scheduledPurgeAt < afterOpening ? message.scheduledPurgeAt : afterOpening;
}

export function isWithinViewGracePeriod(openedAt: Date, now: Date): boolean {
  return now.getTime() - openedAt.getTime() <= VIEW_GRACE_PERIOD_MS;
}

export function isSnap(message: Pick<Message, "contentType">): boolean {
  return message.contentType !== "TEXT";
}
