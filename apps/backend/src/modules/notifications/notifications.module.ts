import { Logger, Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type { App } from "firebase-admin/app";
import type Redis from "ioredis";
import { REDIS } from "../../shared/infrastructure/redis/redis.module";
import { IdentityModule } from "../identity/identity.module";
import { FIREBASE_APP } from "../identity/infrastructure/firebase-app.provider";
import { DeviceTokenUseCases } from "./application/device-tokens";
import {
  DEVICE_TOKEN_REPOSITORY,
  PRESENCE_TRACKER,
  PUSH_SENDER,
  REALTIME_CHANNEL,
  type DeviceTokenRepository,
  type PresenceTracker,
  type PushSender,
  type RealtimeChannel,
} from "./application/ports";
import { UserEventPublisher } from "./application/user-event-publisher";
import { FcmPushSender } from "./infrastructure/fcm-push.sender";
import { PrismaDeviceTokenRepository } from "./infrastructure/prisma-device-token.repository";
import { RedisPresenceTracker } from "./infrastructure/redis-presence.tracker";
import { DevicesController } from "./interface/devices.controller";
import { RealtimeGateway } from "./interface/realtime.gateway";

@Module({
  imports: [IdentityModule],
  controllers: [DevicesController],
  providers: [
    { provide: PRESENCE_TRACKER, inject: [REDIS], useFactory: (redis: Redis) => new RedisPresenceTracker(redis) },
    {
      provide: PUSH_SENDER,
      inject: [FIREBASE_APP, ConfigService],
      // FCM_DRY_RUN=true : FCM valide les envois sans rien délivrer (tests, démo).
      useFactory: (app: App, config: ConfigService) => new FcmPushSender(app, config.get("FCM_DRY_RUN") === "true"),
    },
    { provide: DEVICE_TOKEN_REPOSITORY, useClass: PrismaDeviceTokenRepository },
    RealtimeGateway,
    { provide: REALTIME_CHANNEL, useExisting: RealtimeGateway },
    {
      provide: UserEventPublisher,
      inject: [PRESENCE_TRACKER, REALTIME_CHANNEL, PUSH_SENDER, DEVICE_TOKEN_REPOSITORY],
      useFactory: (
        presence: PresenceTracker,
        realtime: RealtimeChannel,
        push: PushSender,
        devices: DeviceTokenRepository,
      ) => new UserEventPublisher(presence, realtime, push, devices, new Logger(UserEventPublisher.name)),
    },
    {
      provide: DeviceTokenUseCases,
      inject: [DEVICE_TOKEN_REPOSITORY],
      useFactory: (devices: DeviceTokenRepository) => new DeviceTokenUseCases(devices),
    },
  ],
  exports: [UserEventPublisher, REALTIME_CHANNEL],
})
export class NotificationsModule {}
