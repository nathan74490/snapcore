import { Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type { Queue } from "bullmq";
import { workersEnabled } from "../../shared/infrastructure/workers";
import { GroupSnapPurge } from "../groups/application/group-snap-purge";
import { GroupsModule } from "../groups/groups.module";
import { SnapPurgeUseCases } from "../messages/application/snap-purge-use-cases";
import { MessagesModule } from "../messages/messages.module";
import { StoryUseCases } from "../stories/application/story-use-cases";
import { StoriesModule } from "../stories/stories.module";
import { bullmqConnection, PURGE_QUEUE } from "./infrastructure/purge-queue";
import { PurgeWorker } from "./infrastructure/purge.worker";
import { PurgeModule, redisUrlFrom } from "./purge.module";

@Module({
  imports: [PurgeModule, MessagesModule, StoriesModule, GroupsModule],
  providers: [
    {
      provide: PurgeWorker,
      inject: [PURGE_QUEUE, ConfigService, SnapPurgeUseCases, StoryUseCases, GroupSnapPurge],
      useFactory: (
        queue: Queue,
        config: ConfigService,
        snaps: SnapPurgeUseCases,
        stories: StoryUseCases,
        groupSnaps: GroupSnapPurge,
      ) =>
        new PurgeWorker(
          queue,
          bullmqConnection(redisUrlFrom(config)),
          {
            message: { run: (id) => snaps.runPurgeJob(id), sweepOverdue: () => snaps.sweepOverdue() },
            story: { run: (id) => stories.runPurgeJob(id), sweepOverdue: () => stories.sweepOverdue() },
            groupMessage: { run: (id) => groupSnaps.runPurgeJob(id), sweepOverdue: () => groupSnaps.sweepOverdue() },
          },
          workersEnabled(config),
        ),
    },
  ],
})
export class PurgeWorkerModule {}
