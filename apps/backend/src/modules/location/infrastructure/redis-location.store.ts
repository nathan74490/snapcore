import type Redis from "ioredis";
import type { LocationStore } from "../application/ports";
import { LOCATION_TTL_SECONDS, type StoredLocation } from "../domain/location-sharing";

export const locationKey = (userId: string) => `location:${userId}`;

interface StoredJson {
  latitude: number;
  longitude: number;
  updatedAt: string;
}

// Dernière position d'un utilisateur, JSON { latitude, longitude, updatedAt } avec un TTL de 8 h :
// sans mise à jour, elle disparaît d'elle-même (ADR-003, ADR-031). Jamais copiée en base.
export class RedisLocationStore implements LocationStore {
  constructor(private readonly redis: Redis) {}

  async save(userId: string, location: StoredLocation): Promise<void> {
    const value: StoredJson = {
      latitude: location.latitude,
      longitude: location.longitude,
      updatedAt: location.updatedAt.toISOString(),
    };
    await this.redis.set(locationKey(userId), JSON.stringify(value), "EX", LOCATION_TTL_SECONDS);
  }

  async remove(userId: string): Promise<void> {
    await this.redis.del(locationKey(userId));
  }

  async findMany(userIds: string[]): Promise<Map<string, StoredLocation>> {
    if (userIds.length === 0) return new Map();
    const values = await this.redis.mget(userIds.map(locationKey));
    const found = new Map<string, StoredLocation>();
    userIds.forEach((userId, i) => {
      const location = parse(values[i]);
      if (location) found.set(userId, location);
    });
    return found;
  }
}

// Une valeur illisible est ignorée (l'ami n'apparaît simplement pas) plutôt que de casser la carte.
function parse(raw: string | null | undefined): StoredLocation | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<StoredJson>;
    const updatedAt = new Date(value.updatedAt ?? "");
    if (typeof value.latitude !== "number" || typeof value.longitude !== "number" || Number.isNaN(updatedAt.getTime())) {
      return null;
    }
    return { latitude: value.latitude, longitude: value.longitude, updatedAt };
  } catch {
    return null;
  }
}
