import { Logger, Module } from "@nestjs/common";
import { ANALYTICS, type Analytics } from "../../shared/domain/analytics";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "../friendships/application/ports/friendship.repository";
import { FriendshipsModule } from "../friendships/friendships.module";
import { USER_REPOSITORY, type UserRepository } from "../identity/application/ports/user.repository";
import { IdentityModule } from "../identity/identity.module";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { UploadPromoter } from "../media/application/upload-promoter";
import { MediaModule } from "../media/media.module";
import { UserEventPublisher } from "../notifications/application/user-event-publisher";
import { NotificationsModule } from "../notifications/notifications.module";
import { PurgeModule } from "../purge/purge.module";
import { MessagingUseCases } from "./application/messaging-use-cases";
import { MESSAGE_NOTIFIER, type MessageNotifier } from "./application/ports/message-notifier.port";
import { MESSAGE_REPOSITORY, type MessageRepository } from "./application/ports/message.repository";
import { SNAP_PURGE_SCHEDULER, type SnapPurgeScheduler } from "./application/ports/snap-purge.scheduler";
import { SnapPurgeUseCases } from "./application/snap-purge-use-cases";
import { EventMessageNotifier } from "./infrastructure/event-message.notifier";
import { PrismaMessageRepository } from "./infrastructure/prisma-message.repository";
import { MessagesController } from "./interface/http/messages.controller";

@Module({
  imports: [IdentityModule, FriendshipsModule, MediaModule, NotificationsModule, PurgeModule],
  controllers: [MessagesController],
  providers: [
    { provide: MESSAGE_REPOSITORY, useClass: PrismaMessageRepository },
    {
      provide: MESSAGE_NOTIFIER,
      inject: [UserEventPublisher],
      useFactory: (events: UserEventPublisher) => new EventMessageNotifier(events),
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
        ANALYTICS,
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
        analytics: Analytics,
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
          analytics,
        ),
    },
  ],
  exports: [MESSAGE_REPOSITORY, SnapPurgeUseCases],
})
export class MessagesModule {}
