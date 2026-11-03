import type { AvatarCatalog, AvatarOptions } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";

// Catalogue de l'avatar dessiné (ADR-028) : valeurs du style avataaars de DiceBear, recopiées ici
// pour que le domaine ne charge pas DiceBear (paquet ESM uniquement, que les tests d'intégration
// en mode ESM natif ne savent pas charger depuis du CommonJS). Un test unitaire les compare au schéma
// du style installé : une mise à jour de DiceBear qui les change le fait échouer.

export const AVATAR_KEYS = [
  "top",
  "hairColor",
  "skinColor",
  "eyes",
  "eyebrows",
  "mouth",
  "facialHair",
  "accessories",
  "clothing",
  "clothesColor",
  "backgroundColor",
] as const satisfies readonly (keyof AvatarOptions)[];

export type AvatarKey = (typeof AVATAR_KEYS)[number];

/** Élément masqué (rendu avec une probabilité de 0) : uniquement pour ces clés. */
export const AVATAR_NONE = "none";
export const OPTIONAL_PARTS = ["top", "facialHair", "accessories"] as const satisfies readonly AvatarKey[];

// Expressions retirées du catalogue proposé (jugées déplacées pour un avatar de profil).
const EXCLUDED: Partial<Record<AvatarKey, readonly string[]>> = { mouth: ["vomit"] };

// Le fond n'est pas une couleur libre : palette fixe et claire, lisible sous le cercle découpé par l'app.
const BACKGROUND_PALETTE = ["b6e3f4", "c0aede", "d1d4f9", "ffd5dc", "ffdfbf", "fffaa1", "a7ffc4", "ffffff"];

export const DEFAULT_AVATAR: Readonly<AvatarOptions> = Object.freeze({
  top: "shortFlat",
  hairColor: "2c1b18",
  skinColor: "edb98a",
  eyes: "default",
  eyebrows: "defaultNatural",
  mouth: "smile",
  facialHair: "none",
  accessories: "none",
  clothing: "hoodie",
  clothesColor: "65c9ff",
  backgroundColor: "b6e3f4",
});

/** Valeurs du schéma avataaars 9.x (`schema.properties[clé]`), dans son ordre. */
export const AVATAAARS_VALUES: Record<Exclude<AvatarKey, "backgroundColor">, readonly string[]> = {
  top: ["hat", "hijab", "turban", "winterHat1", "winterHat02", "winterHat03", "winterHat04", "bob", "bun", "curly", "curvy", "dreads", "frida", "fro", "froBand", "longButNotTooLong", "miaWallace", "shavedSides", "straight02", "straight01", "straightAndStrand", "dreads01", "dreads02", "frizzle", "shaggy", "shaggyMullet", "shortCurly", "shortFlat", "shortRound", "shortWaved", "sides", "theCaesar", "theCaesarAndSidePart", "bigHair"],
  hairColor: ["a55728", "2c1b18", "b58143", "d6b370", "724133", "4a312c", "f59797", "ecdcbf", "c93305", "e8e1e1"],
  skinColor: ["614335", "d08b5b", "ae5d29", "edb98a", "ffdbb4", "fd9841", "f8d25c"],
  eyes: ["closed", "cry", "default", "eyeRoll", "happy", "hearts", "side", "squint", "surprised", "winkWacky", "wink", "xDizzy"],
  eyebrows: ["angryNatural", "defaultNatural", "flatNatural", "frownNatural", "raisedExcitedNatural", "sadConcernedNatural", "unibrowNatural", "upDownNatural", "angry", "default", "raisedExcited", "sadConcerned", "upDown"],
  mouth: ["concerned", "default", "disbelief", "eating", "grimace", "sad", "screamOpen", "serious", "smile", "tongue", "twinkle", "vomit"],
  facialHair: ["beardLight", "beardMajestic", "beardMedium", "moustacheFancy", "moustacheMagnum"],
  accessories: ["kurt", "prescription01", "prescription02", "round", "sunglasses", "wayfarers", "eyepatch"],
  clothing: ["blazerAndShirt", "blazerAndSweater", "collarAndSweater", "graphicShirt", "hoodie", "overall", "shirtCrewNeck", "shirtScoopNeck", "shirtVNeck"],
  clothesColor: ["262e33", "65c9ff", "5199e4", "25557c", "e6e6e6", "929598", "3c4f5c", "b1e2ff", "a7ffc4", "ffafb9", "ffffb1", "ff488e", "ff5c5c", "ffffff"],
};

function buildCatalog(): Record<AvatarKey, readonly string[]> {
  const catalog = {} as Record<AvatarKey, readonly string[]>;
  for (const key of AVATAR_KEYS) {
    const base = key === "backgroundColor" ? BACKGROUND_PALETTE : AVATAAARS_VALUES[key];
    const excluded = EXCLUDED[key] ?? [];
    const values = base.filter((value) => !excluded.includes(value));
    catalog[key] = Object.freeze((OPTIONAL_PARTS as readonly string[]).includes(key) ? [AVATAR_NONE, ...values] : values);
  }
  return Object.freeze(catalog);
}

export const AVATAR_CATALOG = buildCatalog();

// L'avatar par défaut doit rester dans le catalogue, même après une mise à jour du style.
for (const key of AVATAR_KEYS) {
  if (!AVATAR_CATALOG[key].includes(DEFAULT_AVATAR[key])) {
    throw new Error(`Avatar par défaut hors catalogue : ${key}=${DEFAULT_AVATAR[key]}`);
  }
}

export function avatarCatalog(): AvatarCatalog {
  const options = {} as Record<AvatarKey, string[]>;
  for (const key of AVATAR_KEYS) options[key] = [...AVATAR_CATALOG[key]];
  return { defaults: { ...DEFAULT_AVATAR }, options };
}

/**
 * Validation stricte d'un objet d'options : exactement les 11 clés, chacune à une valeur du catalogue.
 * @throws ValidationError sinon (400).
 */
export function parseAvatarOptions(raw: unknown): AvatarOptions {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    throw new ValidationError("Options d'avatar invalides : objet attendu");
  }
  const input = raw as Record<string, unknown>;
  const unknownKeys = Object.keys(input).filter((key) => !(AVATAR_KEYS as readonly string[]).includes(key));
  if (unknownKeys.length > 0) {
    throw new ValidationError(`Options d'avatar inconnues : ${unknownKeys.join(", ")}`);
  }
  const options = {} as AvatarOptions;
  for (const key of AVATAR_KEYS) {
    const value = input[key];
    if (value === undefined) throw new ValidationError(`Option d'avatar manquante : ${key}`);
    if (typeof value !== "string" || !AVATAR_CATALOG[key].includes(value)) {
      throw new ValidationError(`Valeur d'avatar invalide pour ${key}`);
    }
    options[key] = value;
  }
  return options;
}

// Lecture d'un avatar stocké : une valeur sortie du catalogue (style mis à jour, ligne modifiée à la
// main) est ignorée plutôt que de casser une liste d'amis ; l'utilisateur retombe sur sa photo.
export function readStoredAvatar(raw: unknown): AvatarOptions | null {
  if (raw === null || raw === undefined) return null;
  try {
    return parseAvatarOptions(raw);
  } catch {
    return null;
  }
}
