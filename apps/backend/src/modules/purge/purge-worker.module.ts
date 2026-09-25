import { Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type { Queue } from "bullmq";
import { SnapPurgeUseCases } from "../messages/application/snap-purge-use-cases";
import { MessagesModule } from "../messages/messages.module";
import { StoryUseCases } from "../stories/application/story-use-cases";
import { StoriesModule } from "../stories/stories.module";
import { bullmqConnection, PURGE_QUEUE } from "./infrastructure/purge-queue";
import { PurgeWorker } from "./infrastructure/purge.worker";
import { PurgeModule, redisUrlFrom } from "./purge.module";

@Module({
  imports: [PurgeModule, MessagesModule, StoriesModule],
  providers: [
    {
      provide: PurgeWorker,
      inject: [PURGE_QUEUE, ConfigService, SnapPurgeUseCases, StoryUseCases],
      useFactory: (queue: Queue, config: ConfigService, snaps: SnapPurgeUseCases, stories: StoryUseCases) =>
        new PurgeWorker(
          queue,
          bullmqConnection(redisUrlFrom(config)),
          {
            message: { run: (id) => snaps.runPurgeJob(id), sweepOverdue: () => snaps.sweepOverdue() },
            story: { run: (id) => stories.runPurgeJob(id), sweepOverdue: () => stories.sweepOverdue() },
          },
          config.get("PURGE_WORKER_ENABLED") !== "false",
        ),
    },
  ],
})
export class PurgeWorkerModule {}
