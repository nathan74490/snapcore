import type { AvatarOptions } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";
import { AVATAR_KEYS, parseAvatarOptions } from "../domain/avatar-catalog";
import type { AvatarRenderer } from "./ports";

export const AVATAR_SIZE = { min: 64, max: 512, default: 256 } as const;
const CACHE_ENTRIES = 300;

// Rendu public de GET /avatars/avataaars.png (ADR-028) : ne lit aucune donnée utilisateur,
// le PNG ne dépend que des paramètres de la requête (aucune fuite possible).
// Petit cache LRU en mémoire : le rendu resvg coûte quelques millisecondes et les mêmes
// avatars (amis, conversations) sont redemandés en boucle par les clients.
export class RenderAvatar {
  private readonly cache = new Map<string, Buffer>();

  constructor(
    private readonly renderer: AvatarRenderer,
    private readonly maxEntries = CACHE_ENTRIES,
  ) {}

  /** @throws ValidationError si un paramètre est manquant, inconnu ou hors catalogue. */
  execute(query: Record<string, unknown>): Buffer {
    const { size, ...rest } = query;
    const options = parseAvatarOptions(rest);
    return this.render(options, parseSize(size));
  }

  private render(options: AvatarOptions, size: number): Buffer {
    const key = `${size}:${AVATAR_KEYS.map((name) => options[name]).join(",")}`;
    const cached = this.cache.get(key);
    if (cached) {
      // Réinsertion : la Map garde l'ordre d'insertion, la première entrée est la moins récente.
      this.cache.delete(key);
      this.cache.set(key, cached);
      return cached;
    }
    const png = this.renderer.renderPng(options, size);
    this.cache.set(key, png);
    if (this.cache.size > this.maxEntries) this.cache.delete(this.cache.keys().next().value!);
    return png;
  }
}

function parseSize(raw: unknown): number {
  if (raw === undefined) return AVATAR_SIZE.default;
  const size = typeof raw === "string" && /^\d{1,4}$/.test(raw) ? Number(raw) : NaN;
  if (!(size >= AVATAR_SIZE.min && size <= AVATAR_SIZE.max)) {
    throw new ValidationError(`Taille d'avatar invalide : entier de ${AVATAR_SIZE.min} à ${AVATAR_SIZE.max} attendu`);
  }
  return size;
}
