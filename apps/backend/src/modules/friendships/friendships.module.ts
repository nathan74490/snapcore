import { Module } from "@nestjs/common";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { USER_REPOSITORY, type UserRepository } from "../identity/application/ports/user.repository";
import { IdentityModule } from "../identity/identity.module";
import { FriendshipUseCases } from "./application/friendship-use-cases";
import { FRIENDSHIP_NOTIFIER, type FriendshipNotifier } from "./application/ports/friendship-notifier.port";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "./application/ports/friendship.repository";
import { LoggingFriendshipNotifier } from "./infrastructure/logging-friendship.notifier";
import { PrismaFriendshipRepository } from "./infrastructure/prisma-friendship.repository";
import { FriendshipsController } from "./interface/http/friendships.controller";

@Module({
  imports: [IdentityModule],
  controllers: [FriendshipsController],
  providers: [
    { provide: FRIENDSHIP_REPOSITORY, useClass: PrismaFriendshipRepository },
    { provide: FRIENDSHIP_NOTIFIER, useClass: LoggingFriendshipNotifier },
    {
      provide: FriendshipUseCases,
      inject: [FRIENDSHIP_REPOSITORY, USER_REPOSITORY, FRIENDSHIP_NOTIFIER, CLOCK],
      useFactory: (
        friendships: FriendshipRepository,
        users: UserRepository,
        notifier: FriendshipNotifier,
        clock: Clock,
      ) => new FriendshipUseCases(friendships, users, notifier, clock),
    },
  ],
  exports: [FRIENDSHIP_REPOSITORY],
})
export class FriendshipsModule {}
