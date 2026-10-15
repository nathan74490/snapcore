import { Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { Queue } from "bullmq";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { SNAP_PURGE_SCHEDULER } from "../messages/application/ports/snap-purge.scheduler";
import { STORY_PURGE_SCHEDULER } from "../stories/application/ports";
import { BullMqPurgeScheduler } from "./infrastructure/bullmq-purge.scheduler";
import { bullmqConnection, PURGE_QUEUE } from "./infrastructure/purge-queue";

export const redisUrlFrom = (config: ConfigService): string => config.get("REDIS_URL", "redis://localhost:6379");

// File BullMQ de purge des médias (ADR-017) et un planificateur par type de cible.
// Le worker qui la consomme vit dans PurgeWorkerModule (il dépend des contextes messages et stories).
@Module({
  providers: [
    {
      provide: PURGE_QUEUE,
      inject: [ConfigService],
      useFactory: (config: ConfigService) =>
        new Queue(config.get("PURGE_QUEUE_NAME", "media-purge"), { connection: bullmqConnection(redisUrlFrom(config)) }),
    },
    {
      provide: SNAP_PURGE_SCHEDULER,
      inject: [PURGE_QUEUE, CLOCK],
      useFactory: (queue: Queue, clock: Clock) => new BullMqPurgeScheduler(queue, clock, "message"),
    },
    {
      provide: STORY_PURGE_SCHEDULER,
      inject: [PURGE_QUEUE, CLOCK],
      useFactory: (queue: Queue, clock: Clock) => new BullMqPurgeScheduler(queue, clock, "story"),
    },
  ],
  exports: [PURGE_QUEUE, SNAP_PURGE_SCHEDULER, STORY_PURGE_SCHEDULER],
})
export class PurgeModule {}
