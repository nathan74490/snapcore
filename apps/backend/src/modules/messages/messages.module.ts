import { Logger, Module } from "@nestjs/common";
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
import { LoggingMessageNotifier, LoggingSnapPurgeScheduler } from "./infrastructure/logging-adapters";
import { PrismaMessageRepository } from "./infrastructure/prisma-message.repository";
import { MessagesController } from "./interface/http/messages.controller";

@Module({
  imports: [IdentityModule, FriendshipsModule, MediaModule],
  controllers: [MessagesController],
  providers: [
    { provide: MESSAGE_REPOSITORY, useClass: PrismaMessageRepository },
    { provide: SNAP_PURGE_SCHEDULER, useClass: LoggingSnapPurgeScheduler },
    { provide: MESSAGE_NOTIFIER, useClass: LoggingMessageNotifier },
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
