import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { AppModule } from "../app.module";
import { configureApp } from "../app.setup";
import { FRIENDSHIP_REPOSITORY } from "../modules/friendships/application/ports/friendship.repository";
import { IDENTITY_PROVIDER } from "../modules/identity/application/ports/identity-provider.port";
import { USER_REPOSITORY } from "../modules/identity/application/ports/user.repository";
import { FIREBASE_APP } from "../modules/identity/infrastructure/firebase-app.provider";
import { OBJECT_STORAGE } from "../modules/media/application/ports/object-storage.port";
import { UPLOAD_TICKET_STORE } from "../modules/media/application/ports/upload-ticket.store";
import { MESSAGE_REPOSITORY } from "../modules/messages/application/ports/message.repository";
import { SNAP_PURGE_SCHEDULER } from "../modules/messages/application/ports/snap-purge.scheduler";
import { PurgeWorker } from "../modules/messages/infrastructure/bullmq/purge.worker";
import { PURGE_QUEUE } from "../modules/messages/infrastructure/bullmq/purge-queue";
import { DEVICE_TOKEN_REPOSITORY, PRESENCE_TRACKER, PUSH_SENDER } from "../modules/notifications/application/ports";
import { FakeIdentityProvider } from "./fakes";
import { InMemoryFriendshipRepository } from "./in-memory-friendship.repository";
import { InMemoryObjectStorage, InMemoryUploadTicketStore } from "./in-memory-media";
import { InMemoryMessageRepository, RecordingPurgeScheduler } from "./in-memory-messages";
import { InMemoryDeviceTokens, InMemoryPresence, RecordingPush } from "./in-memory-notifications";
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
}

// Application Nest complète, adaptateurs externes (Postgres, Firebase, R2, BullMQ, FCM) remplacés.
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
    .overrideProvider(PurgeWorker)
    .useValue({})
    .overrideProvider(PUSH_SENDER)
    .useValue(world.push)
    .overrideProvider(DEVICE_TOKEN_REPOSITORY)
    .useValue(world.devices);
  if (!options.realPresence) builder = builder.overrideProvider(PRESENCE_TRACKER).useValue(world.presence);

  return configureApp((await builder.compile()).createNestApplication());
}
