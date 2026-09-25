import { Logger, Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type Redis from "ioredis";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { REDIS } from "../../shared/infrastructure/redis/redis.module";
import { OBJECT_STORAGE, type ObjectStorage } from "./application/ports/object-storage.port";
import { UPLOAD_TICKET_STORE, type UploadTicketStore } from "./application/ports/upload-ticket.store";
import { RequestUpload } from "./application/request-upload";
import { UploadPromoter } from "./application/upload-promoter";
import { PublicMediaUrls } from "./domain/public-media-urls";
import { R2ObjectStorage } from "./infrastructure/r2-object-storage";
import { RedisUploadTicketStore } from "./infrastructure/redis-upload-ticket.store";
import { MediaController } from "./interface/http/media.controller";

@Module({
  controllers: [MediaController],
  providers: [
    {
      provide: OBJECT_STORAGE,
      inject: [ConfigService],
      useFactory: (config: ConfigService) =>
        new R2ObjectStorage({
          accountId: config.get("R2_ACCOUNT_ID", ""),
          accessKeyId: config.get("R2_ACCESS_KEY_ID", ""),
          secretAccessKey: config.get("R2_SECRET_ACCESS_KEY", ""),
          privateBucket: config.get("R2_BUCKET", "snapcore-media"),
          publicBucket: config.get("R2_PUBLIC_BUCKET", ""),
        }),
    },
    {
      provide: PublicMediaUrls,
      inject: [ConfigService],
      useFactory: (config: ConfigService) => new PublicMediaUrls(config.get("R2_PUBLIC_BASE_URL", "")),
    },
    {
      provide: UPLOAD_TICKET_STORE,
      inject: [REDIS],
      useFactory: (redis: Redis) => new RedisUploadTicketStore(redis),
    },
    {
      provide: RequestUpload,
      inject: [OBJECT_STORAGE, UPLOAD_TICKET_STORE, CLOCK],
      useFactory: (storage: ObjectStorage, tickets: UploadTicketStore, clock: Clock) =>
        new RequestUpload(storage, tickets, clock),
    },
    {
      provide: UploadPromoter,
      inject: [OBJECT_STORAGE, UPLOAD_TICKET_STORE],
      useFactory: (storage: ObjectStorage, tickets: UploadTicketStore) =>
        new UploadPromoter(storage, tickets, new Logger(UploadPromoter.name)),
    },
  ],
  exports: [OBJECT_STORAGE, PublicMediaUrls, UploadPromoter],
})
export class MediaModule {}
