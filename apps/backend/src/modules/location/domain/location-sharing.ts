import type { LocationSharingMode } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";
import { allowsMessaging, type Friendship } from "../../friendships/domain/friendship";
import { isActive, type UserAccount } from "../../identity/domain/user";

// Snap Map (ADR-031). La dernière position est éphémère : Redis uniquement, jamais en base (ADR-003).
// Sans mise à jour pendant 8 h, l'ami disparaît de la carte.
export const LOCATION_TTL_SECONDS = 8 * 60 * 60;
// Taille maximale de la liste d'amis exclus / autorisés, après déduplication.
export const MAX_SHARING_FRIEND_IDS = 2000;
// Position d'un élément de story arrondie à 3 décimales (~100 m) : pas d'adresse exacte sur la carte.
const STORY_COORDINATE_DECIMALS = 3;

export const LOCATION_SHARING_MODES = ["GHOST", "ALL_FRIENDS", "FRIENDS_EXCEPT", "ONLY_FRIENDS"] as const satisfies readonly LocationSharingMode[];

export interface Coordinates {
  latitude: number;
  longitude: number;
}

export interface StoredLocation extends Coordinates {
  updatedAt: Date;
}

// État de partage d'un utilisateur, vu par un autre (`viewerListed` : le viewer figure-t-il dans sa liste).
export interface LocationOwner extends Pick<UserAccount, "deletionRequestedAt" | "moderationStatus"> {
  locationSharing: LocationSharingMode;
  viewerListed: boolean;
}

// Règle unique de visibilité (ADR-031) : le viewer voit la position (et les stories géolocalisées) de
// `owner` ssi ils sont amis ACCEPTED (un blocage, dans un sens ou l'autre, rend la ligne BLOCKED : ADR-012),
// owner est actif (vérification centralisée ADR-019 / ADR-021), n'est pas en mode fantôme, et sa liste
// l'autorise : FRIENDS_EXCEPT exclut les amis listés, ONLY_FRIENDS n'admet qu'eux.
export function canSeeLocation(friendship: Friendship | null, owner: LocationOwner): boolean {
  if (!allowsMessaging(friendship) || !isActive(owner)) return false;
  switch (owner.locationSharing) {
    case "GHOST":
      return false;
    case "ALL_FRIENDS":
      return true;
    case "FRIENDS_EXCEPT":
      return !owner.viewerListed;
    case "ONLY_FRIENDS":
      return owner.viewerListed;
  }
}

// Liste d'amis d'un réglage : dédupliquée (ordre de première apparition conservé), 2000 au plus.
export function parseSharingFriendIds(raw: string[]): string[] {
  const unique = [...new Set(raw)];
  if (unique.length > MAX_SHARING_FRIEND_IDS) {
    throw new ValidationError(`La liste d'amis dépasse ${MAX_SHARING_FRIEND_IDS} éléments`);
  }
  return unique;
}

export function assertValidCoordinates({ latitude, longitude }: Coordinates): void {
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
    throw new ValidationError("La latitude doit être comprise entre -90 et 90");
  }
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    throw new ValidationError("La longitude doit être comprise entre -180 et 180");
  }
}

// Position facultative d'une publication : les deux coordonnées ou aucune (null vaut absence, comme
// l'envoie un client qui sérialise explicitement ses champs vides).
export function parseOptionalCoordinates(input: { latitude?: number | null; longitude?: number | null }): Coordinates | null {
  const { latitude, longitude } = input;
  if (latitude == null && longitude == null) return null;
  if (latitude == null || longitude == null) {
    throw new ValidationError("latitude et longitude doivent être fournies ensemble");
  }
  const coordinates = { latitude, longitude };
  assertValidCoordinates(coordinates);
  return coordinates;
}

// Position enregistrée sur un élément de story : arrondie, et absente si l'auteur est en mode fantôme
// au moment de la publication (la position est alors ignorée, sans erreur).
export function storyCoordinates(coordinates: Coordinates | null, authorMode: LocationSharingMode): Coordinates | null {
  if (!coordinates || authorMode === "GHOST") return null;
  return { latitude: roundCoordinate(coordinates.latitude), longitude: roundCoordinate(coordinates.longitude) };
}

function roundCoordinate(value: number): number {
  const factor = 10 ** STORY_COORDINATE_DECIMALS;
  // + 0 : -0 (ex. -0.0001 arrondi) devient 0.
  return Math.round(value * factor) / factor + 0;
}
