import type { LocationSharingMode, LocationSharingSettings } from "@snapcore/types";
import type { LocationSharingRepository, LocationStore } from "../modules/location/application/ports";
import { LOCATION_TTL_SECONDS, type LocationOwner, type StoredLocation } from "../modules/location/domain/location-sharing";
import type { InMemoryUserRepository } from "./in-memory-user.repository";

// Mode par défaut GHOST et liste triée, comme le schéma Prisma et l'adaptateur (ADR-031).
export class InMemoryLocationSharingRepository implements LocationSharingRepository {
  readonly modes = new Map<string, LocationSharingMode>();
  readonly rules = new Map<string, Set<string>>();

  constructor(private readonly users: InMemoryUserRepository) {}

  async findSettings(userId: string): Promise<LocationSharingSettings> {
    return { mode: await this.findMode(userId), friendIds: [...(this.rules.get(userId) ?? [])].sort() };
  }

  async findMode(userId: string): Promise<LocationSharingMode> {
    return this.users.rows.has(userId) ? (this.modes.get(userId) ?? "GHOST") : "GHOST";
  }

  async replaceSettings(userId: string, settings: LocationSharingSettings): Promise<LocationSharingSettings> {
    this.modes.set(userId, settings.mode);
    this.rules.set(userId, new Set(settings.friendIds));
    return this.findSettings(userId);
  }

  async findOwners(viewerId: string, ownerIds: string[]): Promise<Map<string, LocationOwner>> {
    const owners = new Map<string, LocationOwner>();
    for (const id of ownerIds) {
      const user = this.users.rows.get(id);
      if (!user) continue;
      owners.set(id, {
        deletionRequestedAt: user.deletionRequestedAt,
        moderationStatus: user.moderationStatus,
        locationSharing: this.modes.get(id) ?? "GHOST",
        viewerListed: this.rules.get(id)?.has(viewerId) ?? false,
      });
    }
    return owners;
  }

  /** Raccourci de test. */
  set(userId: string, mode: LocationSharingMode, friendIds: string[] = []): void {
    this.modes.set(userId, mode);
    this.rules.set(userId, new Set(friendIds));
  }
}

// Reproduit le TTL Redis de 8 h à partir d'une horloge fournie par le test (sinon jamais expiré).
export class InMemoryLocationStore implements LocationStore {
  readonly entries = new Map<string, StoredLocation>();
  readonly removed: string[] = [];

  constructor(private readonly now: () => Date = () => new Date(0)) {}

  async save(userId: string, location: StoredLocation): Promise<void> {
    this.entries.set(userId, { ...location });
  }

  async remove(userId: string): Promise<void> {
    this.removed.push(userId);
    this.entries.delete(userId);
  }

  async findMany(userIds: string[]): Promise<Map<string, StoredLocation>> {
    const found = new Map<string, StoredLocation>();
    for (const id of userIds) {
      const location = this.entries.get(id);
      if (location && this.now().getTime() - location.updatedAt.getTime() < LOCATION_TTL_SECONDS * 1000) {
        found.set(id, { ...location });
      }
    }
    return found;
  }
}
