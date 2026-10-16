import type { AvatarOptions } from "@snapcore/types";

export const AVATAR_RENDERER = Symbol("AvatarRenderer");

// Rendu d'un avatar dessiné (ADR-028) : PNG carré de `size` pixels de côté, fond plein compris.
// Déterministe : les mêmes options et la même taille donnent toujours les mêmes octets.
export interface AvatarRenderer {
  renderPng(options: AvatarOptions, size: number): Buffer;
}
