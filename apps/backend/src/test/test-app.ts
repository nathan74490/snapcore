import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { AppModule } from "../app.module";
import { configureApp } from "../app.setup";
import { ACCOUNT_DELETION_REPOSITORY, DELETION_JOB_QUEUE, SUBSCRIPTION_CANCELLER } from "../modules/account-deletion/application/ports";
import { ACCOUNT_DELETION_QUEUE, AccountDeletionWorker } from "../modules/account-deletion/infrastructure/bullmq-deletion";
import { FRIENDSHIP_REPOSITORY } from "../modules/friendships/application/ports/friendship.repository";
import { IDENTITY_PROVIDER } from "../modules/identity/application/ports/identity-provider.port";
import { LOCATION_SHARING_REPOSITORY, LOCATION_STORE } from "../modules/location/application/ports";
import { USER_REPOSITORY } from "../modules/identity/application/ports/user.repository";
import { FIREBASE_APP } from "../modules/identity/infrastructure/firebase-app.provider";
import { OBJECT_STORAGE } from "../modules/media/application/ports/object-storage.port";
import { UPLOAD_TICKET_STORE } from "../modules/media/application/ports/upload-ticket.store";
import { MESSAGE_REPOSITORY } from "../modules/messages/application/ports/message.repository";
import { SNAP_PURGE_SCHEDULER } from "../modules/messages/application/ports/snap-purge.scheduler";
import { DEVICE_TOKEN_REPOSITORY, PRESENCE_TRACKER, PUSH_SENDER } from "../modules/notifications/application/ports";
import { PurgeWorker } from "../modules/purge/infrastructure/purge.worker";
import { PURGE_QUEUE } from "../modules/purge/infrastructure/purge-queue";
import { STORY_PURGE_SCHEDULER, STORY_REPOSITORY, STORY_VIEW_STORE } from "../modules/stories/application/ports";
import { MODERATION_REPOSITORY } from "../modules/moderation/application/ports";
import { SUBSCRIPTION_REPOSITORY } from "../modules/subscriptions/application/ports";
import { FakeIdentityProvider } from "./fakes";
import { InMemoryAccountDeletionRepository, RecordingDeletionQueue } from "./in-memory-account-deletion";
import { InMemoryFriendshipRepository } from "./in-memory-friendship.repository";
import { InMemoryLocationSharingRepository, InMemoryLocationStore } from "./in-memory-location";
import { InMemoryObjectStorage, InMemoryUploadTicketStore } from "./in-memory-media";
import { InMemoryMessageRepository, RecordingPurgeScheduler } from "./in-memory-messages";
import { InMemoryModerationRepository } from "./in-memory-moderation";
import { InMemoryDeviceTokens, InMemoryPresence, RecordingPush } from "./in-memory-notifications";
import { InMemoryStoryRepository, InMemoryStoryViews, RecordingStoryPurgeScheduler } from "./in-memory-stories";
import { InMemorySubscriptionRepository } from "./in-memory-subscriptions";
import { InMemoryUserRepository } from "./in-memory-user.repository";

// État partagé entre instances : deux applications construites avec le même `TestWorld`
// se comportent comme deux instances du backend devant la même base.
export class TestWorld {
  readonly identity = new FakeIdentityProvider();
  readonly users = new InMemoryUserRepository();
  readonly friendships = new InMemoryFriendshipRepository(this.users);
  readonly messages = new InMemoryMessageRepository();
  readonly storage = new InMemoryObjectStorage();
  readonly tickets = new InMemoryUploadTicketStore();
  readonly devices = new InMemoryDeviceTokens();
  readonly push = new RecordingPush();
  readonly presence = new InMemoryPresence();
  readonly stories = new InMemoryStoryRepository();
  readonly storyViews = new InMemoryStoryViews();
  readonly accountDeletion = new InMemoryAccountDeletionRepository(this.users, this.messages, this.stories, this.friendships);
  readonly deletionQueue = new RecordingDeletionQueue();
  readonly moderation = new InMemoryModerationRepository(this.users);
  readonly subscriptions = new InMemorySubscriptionRepository(this.users);
  readonly locationSharing = new InMemoryLocationSharingRepository(this.users);
  readonly locations = new InMemoryLocationStore();
}

// Application Nest complète, adaptateurs externes (Postgres, Firebase, R2, Redis, BullMQ, FCM) remplacés.
// `realPresence` garde la présence Redis réelle (tests d'intégration multi-instance).
export async function createTestApp(
  world: TestWorld,
  options: { realPresence?: boolean } = {},
): Promise<INestApplication> {
  let builder = Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(FIREBASE_APP)
    .useValue({})
    .overrideProvider(IDENTITY_PROVIDER)
    .useValue(world.identity)
    .overrideProvider(USER_REPOSITORY)
    .useValue(world.users)
    .overrideProvider(FRIENDSHIP_REPOSITORY)
    .useValue(world.friendships)
    .overrideProvider(OBJECT_STORAGE)
    .useValue(world.storage)
    .overrideProvider(UPLOAD_TICKET_STORE)
    .useValue(world.tickets)
    .overrideProvider(MESSAGE_REPOSITORY)
    .useValue(world.messages)
    .overrideProvider(PURGE_QUEUE)
    .useValue({})
    .overrideProvider(SNAP_PURGE_SCHEDULER)
    .useValue(new RecordingPurgeScheduler())
    .overrideProvider(STORY_REPOSITORY)
    .useValue(world.stories)
    .overrideProvider(STORY_VIEW_STORE)
    .useValue(world.storyViews)
    .overrideProvider(STORY_PURGE_SCHEDULER)
    .useValue(new RecordingStoryPurgeScheduler())
    .overrideProvider(PurgeWorker)
    .useValue({})
    .overrideProvider(ACCOUNT_DELETION_REPOSITORY)
    .useValue(world.accountDeletion)
    .overrideProvider(ACCOUNT_DELETION_QUEUE)
    .useValue({})
    .overrideProvider(DELETION_JOB_QUEUE)
    .useValue(world.deletionQueue)
    .overrideProvider(AccountDeletionWorker)
    .useValue({})
    .overrideProvider(MODERATION_REPOSITORY)
    .useValue(world.moderation)
    .overrideProvider(SUBSCRIPTION_REPOSITORY)
    .useValue(world.subscriptions)
    .overrideProvider(SUBSCRIPTION_CANCELLER)
    .useValue({ cancelFor: async () => undefined })
    .overrideProvider(PUSH_SENDER)
    .useValue(world.push)
    .overrideProvider(DEVICE_TOKEN_REPOSITORY)
    .useValue(world.devices)
    .overrideProvider(LOCATION_SHARING_REPOSITORY)
    .useValue(world.locationSharing)
    .overrideProvider(LOCATION_STORE)
    .useValue(world.locations);
  if (!options.realPresence) builder = builder.overrideProvider(PRESENCE_TRACKER).useValue(world.presence);

  return configureApp((await builder.compile()).createNestApplication());
}
