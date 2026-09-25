import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import request from "supertest";
import { AppModule } from "./app.module";
import { configureApp } from "./app.setup";
import { FRIENDSHIP_NOTIFIER } from "./modules/friendships/application/ports/friendship-notifier.port";
import { FRIENDSHIP_REPOSITORY } from "./modules/friendships/application/ports/friendship.repository";
import { IDENTITY_PROVIDER } from "./modules/identity/application/ports/identity-provider.port";
import { USER_REPOSITORY } from "./modules/identity/application/ports/user.repository";
import { FIREBASE_APP } from "./modules/identity/infrastructure/firebase-app.provider";
import { MESSAGE_REPOSITORY } from "./modules/messages/application/ports/message.repository";
import { SNAP_PURGE_SCHEDULER } from "./modules/messages/application/ports/snap-purge.scheduler";
import { PurgeWorker } from "./modules/messages/infrastructure/bullmq/purge.worker";
import { PURGE_QUEUE } from "./modules/messages/infrastructure/bullmq/purge-queue";
import { InMemoryMessageRepository, RecordingPurgeScheduler } from "./test/in-memory-messages";
import { OBJECT_STORAGE } from "./modules/media/application/ports/object-storage.port";
import { UPLOAD_TICKET_STORE } from "./modules/media/application/ports/upload-ticket.store";
import { InMemoryObjectStorage, InMemoryUploadTicketStore } from "./test/in-memory-media";
import { FakeIdentityProvider, RecordingFriendshipNotifier } from "./test/fakes";
import { InMemoryFriendshipRepository } from "./test/in-memory-friendship.repository";
import { InMemoryUserRepository } from "./test/in-memory-user.repository";

// Application Nest complète, avec les adaptateurs externes (Postgres, Firebase, R2, Redis, BullMQ) remplacés.
describe("API HTTP", () => {
  let app: INestApplication;
  let identity: FakeIdentityProvider;
  let users: InMemoryUserRepository;

  beforeEach(async () => {
    identity = new FakeIdentityProvider();
    users = new InMemoryUserRepository();
    const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(FIREBASE_APP)
      .useValue({})
      .overrideProvider(IDENTITY_PROVIDER)
      .useValue(identity)
      .overrideProvider(USER_REPOSITORY)
      .useValue(users)
      .overrideProvider(FRIENDSHIP_REPOSITORY)
      .useValue(new InMemoryFriendshipRepository(users))
      .overrideProvider(OBJECT_STORAGE)
      .useValue(new InMemoryObjectStorage())
      .overrideProvider(UPLOAD_TICKET_STORE)
      .useValue(new InMemoryUploadTicketStore())
      .overrideProvider(MESSAGE_REPOSITORY)
      .useValue(new InMemoryMessageRepository())
      .overrideProvider(PURGE_QUEUE)
      .useValue({})
      .overrideProvider(SNAP_PURGE_SCHEDULER)
      .useValue(new RecordingPurgeScheduler())
      .overrideProvider(PurgeWorker)
      .useValue({})
      .overrideProvider(FRIENDSHIP_NOTIFIER)
      .useValue(new RecordingFriendshipNotifier())
      .compile();
    app = configureApp(moduleRef.createNestApplication());
    await app.init();
  });

  afterEach(() => app.close());

  const register = async (uid: string, username: string) => {
    const token = identity.addAccount(uid, `${username.toLowerCase()}@example.com`);
    const res = await request(app.getHttpServer())
      .post("/auth/register")
      .set("Authorization", `Bearer ${token}`)
      .send({ username });
    expect(res.status).toBe(200);
    return { token, id: res.body.id as string };
  };

  it("GET /health est public", async () => {
    await request(app.getHttpServer()).get("/health").expect(200, { status: "ok" });
  });

  it("refuse une requête sans ID token", async () => {
    await request(app.getHttpServer()).get("/me").expect(401);
  });

  it("ne crée jamais de compte à la volée sur une requête authentifiée", async () => {
    const token = identity.addAccount("uid-x");
    await request(app.getHttpServer()).get("/me").set("Authorization", `Bearer ${token}`).expect(404);
    expect(users.rows.size).toBe(0);
  });

  it("inscription puis profil : firebaseUid n'est jamais exposé", async () => {
    const { token } = await register("uid-1", "Nathan");

    const me = await request(app.getHttpServer()).get("/me").set("Authorization", `Bearer ${token}`).expect(200);

    expect(me.body).toMatchObject({ username: "nathan", email: "nathan@example.com", subscriptionTier: "free" });
    expect(me.body.firebaseUid).toBeUndefined();
  });

  it("un compte en cours de suppression perd immédiatement l'accès", async () => {
    const { token, id } = await register("uid-1", "nathan");
    users.rows.get(id)!.deletionRequestedAt = new Date();

    await request(app.getHttpServer()).get("/me").set("Authorization", `Bearer ${token}`).expect(404);
  });

  it("rejette les champs inconnus (ex. email) à la mise à jour du profil", async () => {
    const { token } = await register("uid-1", "nathan");

    await request(app.getHttpServer())
      .patch("/me")
      .set("Authorization", `Bearer ${token}`)
      .send({ email: "pirate@example.com" })
      .expect(400);
  });

  it("parcours complet : demande, acceptation, liste d'amis", async () => {
    const alice = await register("uid-a", "alice");
    const bob = await register("uid-b", "bob");
    const http = request(app.getHttpServer());

    const sent = await http
      .post("/friendships/requests")
      .set("Authorization", `Bearer ${alice.token}`)
      .send({ username: "bob" })
      .expect(201);
    await http
      .post(`/friendships/requests/${sent.body.friendshipId}/accept`)
      .set("Authorization", `Bearer ${bob.token}`)
      .expect(200);

    const friends = await http.get("/friendships").set("Authorization", `Bearer ${alice.token}`).expect(200);

    expect(friends.body).toEqual([
      expect.objectContaining({ status: "ACCEPTED", user: { id: bob.id, username: "bob" } }),
    ]);
  });

  it("pseudo déjà pris → 409", async () => {
    await register("uid-a", "alice");
    const token = identity.addAccount("uid-b");

    await request(app.getHttpServer())
      .post("/auth/register")
      .set("Authorization", `Bearer ${token}`)
      .send({ username: "ALICE" })
      .expect(409);
  });
});
