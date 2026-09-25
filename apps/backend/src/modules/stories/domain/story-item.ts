import type { StoryContentType } from "@snapcore/types";
import type { MediaPurpose } from "../../media/domain/media-purpose";

// Un élément de story = un snap publié en story, avec sa propre expiration (ADR-013).
export interface StoryItem {
  id: string;
  authorId: string;
  mediaKey: string;
  contentType: StoryContentType;
  createdAt: Date;
  expiresAt: Date;
}

export const STORY_LIFETIME_MS = 24 * 60 * 60 * 1000;
// Même durée de vie courte que la lecture d'un Snap : l'URL n'est pas faite pour être partagée.
export const STORY_VIEW_URL_TTL_SECONDS = 60;

export const UPLOAD_PURPOSE_BY_STORY_TYPE: Record<StoryContentType, MediaPurpose> = {
  PHOTO: "STORY_PHOTO",
  VIDEO: "STORY_VIDEO",
};

export function storyMediaKey(id: string): string {
  return `stories/${id}`;
}

export function expirationFor(createdAt: Date): Date {
  return new Date(createdAt.getTime() + STORY_LIFETIME_MS);
}

// L'API ne se fie jamais à la seule présence de la ligne : un élément expiré mais pas encore
// purgé (retard du job ou du balayage) ne doit plus être visible (ADR-013).
export function isVisible(item: Pick<StoryItem, "expiresAt">, now: Date): boolean {
  return item.expiresAt > now;
}
