import type { FriendLocation, LocationSharingSettings } from "@snapcore/types";
import { noAnalytics, type Analytics } from "../../../shared/domain/analytics";
import type { Clock } from "../../../shared/domain/clock";
import type { FriendshipRepository } from "../../friendships/application/ports/friendship.repository";
import { otherMemberOf } from "../../friendships/domain/friendship";
import type { UserAccount } from "../../identity/domain/user";
import { assertValidCoordinates, parseSharingFriendIds, type Coordinates } from "../domain/location-sharing";
import type { LocationVisibility } from "./location-visibility";
import type { LocationSharingRepository, LocationStore } from "./ports";

// Snap Map (ADR-031) : ma position, mes réglages de partage, et la carte de mes amis.
export class LocationUseCases {
  constructor(
    private readonly sharing: LocationSharingRepository,
    private readonly store: LocationStore,
    private readonly friendships: FriendshipRepository,
    private readonly visibility: LocationVisibility,
    private readonly clock: Clock,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  // En mode fantôme, rien n'est stocké et une éventuelle position restante est effacée.
  async updateLocation(me: UserAccount, coordinates: Coordinates): Promise<void> {
    assertValidCoordinates(coordinates);
    if ((await this.sharing.findMode(me.id)) === "GHOST") {
      await this.store.remove(me.id);
      return;
    }
    await this.store.save(me.id, {
      latitude: coordinates.latitude,
      longitude: coordinates.longitude,
      updatedAt: this.clock.now(),
    });
  }

  settings(me: UserAccount): Promise<LocationSharingSettings> {
    return this.sharing.findSettings(me.id);
  }

  // La liste remplace l'ancienne, filtrée en silence aux amis ACCEPTED actuels (un ami suspendu le reste :
  // sa ligne d'amitié existe toujours). Passer en mode fantôme efface immédiatement la position.
  async updateSettings(me: UserAccount, input: LocationSharingSettings): Promise<LocationSharingSettings> {
    const requested = parseSharingFriendIds(input.friendIds);
    const friendIds = new Set(
      (await this.friendships.findForPairs(me.id, requested))
        .filter((friendship) => friendship.status === "ACCEPTED")
        .map((friendship) => otherMemberOf(friendship, me.id)),
    );
    const saved = await this.sharing.replaceSettings(me.id, {
      mode: input.mode,
      friendIds: requested.filter((id) => friendIds.has(id)),
    });
    if (saved.mode === "GHOST") await this.store.remove(me.id);
    this.analytics.capture(me.id, "location_sharing_changed", { mode: saved.mode });
    return saved;
  }

  // Amis dont la position m'est visible selon la règle ET encore présente dans Redis (moins de 8 h).
  async friendsOnMap(me: UserAccount): Promise<FriendLocation[]> {
    const visible = await this.visibility.visibleFriends(me.id, await this.friendships.listFriends(me.id));
    const locations = await this.store.findMany(visible.map(({ user }) => user.id));
    return visible.flatMap(({ user }) => {
      const location = locations.get(user.id);
      return location
        ? [{ user, latitude: location.latitude, longitude: location.longitude, updatedAt: location.updatedAt.toISOString() }]
        : [];
    });
  }
}
