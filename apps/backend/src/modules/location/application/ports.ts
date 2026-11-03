import type { LocationSharingMode, LocationSharingSettings } from "@snapcore/types";
import type { LocationOwner, StoredLocation } from "../domain/location-sharing";

export const LOCATION_SHARING_REPOSITORY = Symbol("LocationSharingRepository");
export const LOCATION_STORE = Symbol("LocationStore");

// Réglages durables du partage de position (ADR-031) : mode sur la ligne User, liste dans LocationShareRule.
export interface LocationSharingRepository {
  /** Mode et liste de l'utilisateur (liste triée par identifiant). GHOST et liste vide si le compte n'existe pas. */
  findSettings(userId: string): Promise<LocationSharingSettings>;
  findMode(userId: string): Promise<LocationSharingMode>;
  /** Dans une transaction : met à jour le mode et remplace toute la liste. */
  replaceSettings(userId: string, settings: LocationSharingSettings): Promise<LocationSharingSettings>;
  /**
   * État de partage de chacun des `ownerIds` existants, vu par `viewerId` (une requête pour toute la
   * liste). Volontairement SANS filtre d'activité : la règle de visibilité du domaine s'en charge.
   */
  findOwners(viewerId: string, ownerIds: string[]): Promise<Map<string, LocationOwner>>;
}

// Dernière position, éphémère : clé Redis `location:<userId>` avec un TTL de 8 h (ADR-003, ADR-031).
export interface LocationStore {
  save(userId: string, location: StoredLocation): Promise<void>;
  remove(userId: string): Promise<void>;
  /** Positions présentes parmi `userIds`, lues en une seule commande (MGET). */
  findMany(userIds: string[]): Promise<Map<string, StoredLocation>>;
}
