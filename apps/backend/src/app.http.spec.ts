import type { INestApplication } from "@nestjs/common";
import request from "supertest";
import { FakeIdentityProvider } from "./test/fakes";
import { InMemoryUserRepository } from "./test/in-memory-user.repository";
import { createTestApp, TestWorld } from "./test/test-app";

// Application Nest complète, avec les adaptateurs externes (Postgres, Firebase, R2, Redis, BullMQ, FCM) remplacés.
describe("API HTTP", () => {
  let app: INestApplication;
  let identity: FakeIdentityProvider;
  let users: InMemoryUserRepository;

  beforeEach(async () => {
    const world = new TestWorld();
    identity = world.identity;
    users = world.users;
    app = await createTestApp(world);
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
