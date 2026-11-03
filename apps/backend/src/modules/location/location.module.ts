import { Module } from "@nestjs/common";
import type Redis from "ioredis";
import { ANALYTICS, type Analytics } from "../../shared/domain/analytics";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { REDIS } from "../../shared/infrastructure/redis/redis.module";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "../friendships/application/ports/friendship.repository";
import { FriendshipsModule } from "../friendships/friendships.module";
import { LocationUseCases } from "./application/location-use-cases";
import { LocationVisibility } from "./application/location-visibility";
import {
  LOCATION_SHARING_REPOSITORY,
  LOCATION_STORE,
  type LocationSharingRepository,
  type LocationStore,
} from "./application/ports";
import { PrismaLocationSharingRepository } from "./infrastructure/prisma-location-sharing.repository";
import { RedisLocationStore } from "./infrastructure/redis-location.store";
import { LocationController } from "./interface/location.controller";

// Snap Map (ADR-031). Exporte la règle de visibilité (stories géolocalisées) et le stockage des
// positions (effacement à la suppression de compte et à la suspension).
@Module({
  imports: [FriendshipsModule],
  controllers: [LocationController],
  providers: [
    { provide: LOCATION_SHARING_REPOSITORY, useClass: PrismaLocationSharingRepository },
    { provide: LOCATION_STORE, inject: [REDIS], useFactory: (redis: Redis) => new RedisLocationStore(redis) },
    {
      provide: LocationVisibility,
      inject: [LOCATION_SHARING_REPOSITORY],
      useFactory: (sharing: LocationSharingRepository) => new LocationVisibility(sharing),
    },
    {
      provide: LocationUseCases,
      inject: [LOCATION_SHARING_REPOSITORY, LOCATION_STORE, FRIENDSHIP_REPOSITORY, LocationVisibility, CLOCK, ANALYTICS],
      useFactory: (
        sharing: LocationSharingRepository,
        store: LocationStore,
        friendships: FriendshipRepository,
        visibility: LocationVisibility,
        clock: Clock,
        analytics: Analytics,
      ) => new LocationUseCases(sharing, store, friendships, visibility, clock, analytics),
    },
  ],
  exports: [LocationVisibility, LOCATION_STORE],
})
export class LocationModule {}
