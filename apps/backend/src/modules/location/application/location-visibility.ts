import type { LocationSharingMode } from "@snapcore/types";
import type { FriendshipWithUser } from "../../friendships/application/ports/friendship.repository";
import { canSeeLocation } from "../domain/location-sharing";
import type { LocationSharingRepository } from "./ports";

// Application de la règle de visibilité (ADR-031) à une liste d'amis, partagée par la carte des amis
// et par les stories géolocalisées (module stories) : une seule implémentation de la règle.
export class LocationVisibility {
  constructor(private readonly sharing: LocationSharingRepository) {}

  modeOf(userId: string): Promise<LocationSharingMode> {
    return this.sharing.findMode(userId);
  }

  /** Parmi `friends` (lignes vues depuis `viewerId`), ceux qui rendent leur position visible à `viewerId`. */
  async visibleFriends(viewerId: string, friends: FriendshipWithUser[]): Promise<FriendshipWithUser[]> {
    if (friends.length === 0) return [];
    const owners = await this.sharing.findOwners(
      viewerId,
      friends.map(({ user }) => user.id),
    );
    return friends.filter(({ friendship, user }) => {
      const owner = owners.get(user.id);
      return owner !== undefined && canSeeLocation(friendship, owner);
    });
  }
}

// Doublure prudente (tests, scripts) : tout le monde en mode fantôme, personne sur la carte.
export const ghostLocationVisibility: Pick<LocationVisibility, "modeOf" | "visibleFriends"> = {
  modeOf: async () => "GHOST",
  visibleFriends: async () => [],
};
