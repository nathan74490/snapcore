import { Logger, Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { Queue } from "bullmq";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "../friendships/application/ports/friendship.repository";
import { FriendshipsModule } from "../friendships/friendships.module";
import { USER_REPOSITORY, type UserRepository } from "../identity/application/ports/user.repository";
import { IdentityModule } from "../identity/identity.module";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { UploadPromoter } from "../media/application/upload-promoter";
import { MediaModule } from "../media/media.module";
import { MessagingUseCases } from "./application/messaging-use-cases";
import { MESSAGE_NOTIFIER, type MessageNotifier } from "./application/ports/message-notifier.port";
import { MESSAGE_REPOSITORY, type MessageRepository } from "./application/ports/message.repository";
import { SNAP_PURGE_SCHEDULER, type SnapPurgeScheduler } from "./application/ports/snap-purge.scheduler";
import { SnapPurgeUseCases } from "./application/snap-purge-use-cases";
import { BullMqSnapPurgeScheduler } from "./infrastructure/bullmq/bullmq-snap-purge.scheduler";
import { PurgeWorker } from "./infrastructure/bullmq/purge.worker";
import { bullmqConnection, PURGE_QUEUE } from "./infrastructure/bullmq/purge-queue";
import { UserEventPublisher } from "../notifications/application/user-event-publisher";
import { NotificationsModule } from "../notifications/notifications.module";
import { EventMessageNotifier } from "./infrastructure/event-message.notifier";
import { PrismaMessageRepository } from "./infrastructure/prisma-message.repository";
import { MessagesController } from "./interface/http/messages.controller";

const redisUrl = (config: ConfigService) => config.get("REDIS_URL", "redis://localhost:6379");

@Module({
  imports: [IdentityModule, FriendshipsModule, MediaModule, NotificationsModule],
  controllers: [MessagesController],
  providers: [
    { provide: MESSAGE_REPOSITORY, useClass: PrismaMessageRepository },
    {
      provide: MESSAGE_NOTIFIER,
      inject: [UserEventPublisher],
      useFactory: (events: UserEventPublisher) => new EventMessageNotifier(events),
    },
    {
      provide: PURGE_QUEUE,
      inject: [ConfigService],
      useFactory: (config: ConfigService) =>
        new Queue(config.get("PURGE_QUEUE_NAME", "media-purge"), { connection: bullmqConnection(redisUrl(config)) }),
    },
    {
      provide: SNAP_PURGE_SCHEDULER,
      inject: [PURGE_QUEUE, CLOCK],
      useFactory: (queue: Queue, clock: Clock) => new BullMqSnapPurgeScheduler(queue, clock),
    },
    {
      provide: SnapPurgeUseCases,
      inject: [MESSAGE_REPOSITORY, USER_REPOSITORY, OBJECT_STORAGE, SNAP_PURGE_SCHEDULER, MESSAGE_NOTIFIER, CLOCK],
      useFactory: (
        messages: MessageRepository,
        users: UserRepository,
        storage: ObjectStorage,
        scheduler: SnapPurgeScheduler,
        notifier: MessageNotifier,
        clock: Clock,
      ) => new SnapPurgeUseCases(messages, users, storage, scheduler, notifier, clock, new Logger(SnapPurgeUseCases.name)),
    },
    {
      provide: PurgeWorker,
      inject: [PURGE_QUEUE, ConfigService, SnapPurgeUseCases],
      useFactory: (queue: Queue, config: ConfigService, useCases: SnapPurgeUseCases) =>
        new PurgeWorker(queue, bullmqConnection(redisUrl(config)), useCases, config.get("PURGE_WORKER_ENABLED") !== "false"),
    },
    {
      provide: MessagingUseCases,
      inject: [
        MESSAGE_REPOSITORY,
        FRIENDSHIP_REPOSITORY,
        USER_REPOSITORY,
        UploadPromoter,
        OBJECT_STORAGE,
        SNAP_PURGE_SCHEDULER,
        MESSAGE_NOTIFIER,
        CLOCK,
      ],
      useFactory: (
        messages: MessageRepository,
        friendships: FriendshipRepository,
        users: UserRepository,
        promoter: UploadPromoter,
        storage: ObjectStorage,
        scheduler: SnapPurgeScheduler,
        notifier: MessageNotifier,
        clock: Clock,
      ) =>
        new MessagingUseCases(
          messages,
          friendships,
          users,
          promoter,
          storage,
          scheduler,
          notifier,
          clock,
          new Logger(MessagingUseCases.name),
        ),
    },
  ],
  exports: [MESSAGE_REPOSITORY],
})
export class MessagesModule {}
