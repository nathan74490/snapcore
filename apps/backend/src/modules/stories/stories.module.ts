import { Logger, Module } from "@nestjs/common";
import type Redis from "ioredis";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { REDIS } from "../../shared/infrastructure/redis/redis.module";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "../friendships/application/ports/friendship.repository";
import { FriendshipsModule } from "../friendships/friendships.module";
import { USER_REPOSITORY, type UserRepository } from "../identity/application/ports/user.repository";
import { IdentityModule } from "../identity/identity.module";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { UploadPromoter } from "../media/application/upload-promoter";
import { MediaModule } from "../media/media.module";
import { PurgeModule } from "../purge/purge.module";
import {
  STORY_PURGE_SCHEDULER,
  STORY_REPOSITORY,
  STORY_VIEW_STORE,
  type StoryPurgeScheduler,
  type StoryRepository,
  type StoryViewStore,
} from "./application/ports";
import { StoryUseCases } from "./application/story-use-cases";
import { PrismaStoryRepository } from "./infrastructure/prisma-story.repository";
import { RedisStoryViewStore } from "./infrastructure/redis-story-view.store";
import { StoriesController } from "./interface/stories.controller";

@Module({
  imports: [IdentityModule, FriendshipsModule, MediaModule, PurgeModule],
  controllers: [StoriesController],
  providers: [
    { provide: STORY_REPOSITORY, useClass: PrismaStoryRepository },
    { provide: STORY_VIEW_STORE, inject: [REDIS], useFactory: (redis: Redis) => new RedisStoryViewStore(redis) },
    {
      provide: StoryUseCases,
      inject: [
        STORY_REPOSITORY,
        STORY_VIEW_STORE,
        FRIENDSHIP_REPOSITORY,
        USER_REPOSITORY,
        UploadPromoter,
        OBJECT_STORAGE,
        STORY_PURGE_SCHEDULER,
        CLOCK,
      ],
      useFactory: (
        stories: StoryRepository,
        views: StoryViewStore,
        friendships: FriendshipRepository,
        users: UserRepository,
        promoter: UploadPromoter,
        storage: ObjectStorage,
        scheduler: StoryPurgeScheduler,
        clock: Clock,
      ) =>
        new StoryUseCases(
          stories,
          views,
          friendships,
          users,
          promoter,
          storage,
          scheduler,
          clock,
          new Logger(StoryUseCases.name),
        ),
    },
  ],
  exports: [StoryUseCases, STORY_REPOSITORY, STORY_VIEW_STORE],
})
export class StoriesModule {}
