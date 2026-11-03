import { ValidationError } from "../../../shared/domain/errors";

// Usage déclaré d'un upload : il fixe les types et la taille acceptés, et limite
// l'endroit où l'upload pourra être confirmé (un upload PROFILE_PHOTO ne devient jamais un snap).
export const MEDIA_PURPOSES = ["SNAP_PHOTO", "SNAP_VIDEO", "STORY_PHOTO", "STORY_VIDEO", "PROFILE_PHOTO", "STORY_THUMBNAIL"] as const;
export type MediaPurpose = (typeof MEDIA_PURPOSES)[number];

const KB = 1024;
const MB = 1024 * KB;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];
const VIDEO_TYPES = ["video/mp4"];

interface MediaRule {
  contentTypes: string[];
  maxBytes: number;
}

// La taille sert d'approximation de la durée d'une vidéo (60 s max, ADR-011) : ~8 Mbit/s
// pendant 60 s ≈ 60 Mo. Ce n'est pas une vérification exacte de durée (limite assumée, ADR-016).
// Photo de profil : recadrée en 512×512 côté app avant envoi (ADR-014), donc petite.
// Vignette de story : ~320 px fabriquée par l'app (ADR-026), quelques dizaines de Ko.
const RULES: Record<MediaPurpose, MediaRule> = {
  SNAP_PHOTO: { contentTypes: IMAGE_TYPES, maxBytes: 10 * MB },
  STORY_PHOTO: { contentTypes: IMAGE_TYPES, maxBytes: 10 * MB },
  SNAP_VIDEO: { contentTypes: VIDEO_TYPES, maxBytes: 60 * MB },
  STORY_VIDEO: { contentTypes: VIDEO_TYPES, maxBytes: 60 * MB },
  PROFILE_PHOTO: { contentTypes: IMAGE_TYPES, maxBytes: 2 * MB },
  STORY_THUMBNAIL: { contentTypes: IMAGE_TYPES, maxBytes: 512 * KB },
};

export function assertAcceptedMedia(purpose: MediaPurpose, contentType: string, contentLength: number): void {
  const rule = RULES[purpose];
  if (!rule.contentTypes.includes(contentType)) {
    throw new ValidationError(`Type de fichier non accepté pour ${purpose} : ${contentType}`);
  }
  if (!Number.isInteger(contentLength) || contentLength <= 0 || contentLength > rule.maxBytes) {
    throw new ValidationError(`Taille de fichier invalide pour ${purpose} (max ${rule.maxBytes / MB} Mo)`);
  }
}
