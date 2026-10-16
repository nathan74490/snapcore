import type { INestApplication } from "@nestjs/common";
import request from "supertest";
import { FakeIdentityProvider } from "./test/fakes";
import { InMemoryUserRepository } from "./test/in-memory-user.repository";
import { createTestApp, TestWorld } from "./test/test-app";

// Application Nest complète, avec les adaptateurs externes (Postgres, Firebase, R2, Redis, BullMQ, FCM) remplacés.
const ADMIN_SECRET = "s".repeat(40);
const WEBHOOK_SECRET = "w".repeat(40);
const MODERATOR_ID = "5f0c8d2e-2b7a-4c1e-9d3f-6a8b1c2d3e4f";

describe("API HTTP", () => {
  let app: INestApplication;
  let identity: FakeIdentityProvider;
  let users: InMemoryUserRepository;
  let world: TestWorld;

  beforeEach(async () => {
    process.env.ADMIN_API_SECRET = ADMIN_SECRET;
    process.env.REVENUECAT_WEBHOOK_SECRET = WEBHOOK_SECRET;
    process.env.REVENUECAT_PURCHASE_LINK = "https://pay.rev.cat/sandbox-token/";
    process.env.REVENUECAT_ENTITLEMENT_ID = "snapcore_plus";
    world = new TestWorld();
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
      .send({ username, firstName: username.charAt(0).toUpperCase() + username.slice(1) });
    expect(res.status).toBe(200);
    return { token, id: res.body.id as string };
  };

  it("API admin : refusée sans secret ou avec un mauvais secret ; une suspension coupe l'accès (403)", async () => {
    const { token, id } = await register("uid-1", "nathan");
    const http = request(app.getHttpServer());
    world.moderation.moderators.add(MODERATOR_ID);
    const body = { moderatorId: MODERATOR_ID, reason: "Spam" };

    await http.post(`/admin/users/${id}/suspend`).send(body).expect(401);
    await http.post(`/admin/users/${id}/suspend`).set("x-admin-secret", "mauvais").send(body).expect(401);
    // Un jeton Firebase valide ne donne pas accès à l'API admin (ADR-021).
    await http.post(`/admin/users/${id}/suspend`).set("Authorization", `Bearer ${token}`).send(body).expect(401);

    await http.post(`/admin/users/${id}/suspend`).set("x-admin-secret", ADMIN_SECRET).send(body).expect(204);
    const me = await http.get("/me").set("Authorization", `Bearer ${token}`).expect(403);
    expect(me.body.message).toBe("Ce compte est suspendu");

    await http.post(`/admin/users/${id}/reactivate`).set("x-admin-secret", ADMIN_SECRET).send({ moderatorId: MODERATOR_ID }).expect(204);
    await http.get("/me").set("Authorization", `Bearer ${token}`).expect(200);
  });

  it("SnapCore+ : webhook RevenueCat protégé, achat puis expiration visibles dans /me (ADR-025)", async () => {
    const { token, id } = await register("uid-1", "nathan");
    const http = request(app.getHttpServer());
    const event = (type: string, at: number, expires: number) => ({
      api_version: "1.0",
      event: { type, app_user_id: id, entitlement_ids: ["snapcore_plus"], event_timestamp_ms: at, expiration_at_ms: expires },
    });
    const now = Date.now();
    const in30Days = now + 30 * 86_400_000;

    await http.post("/webhooks/revenuecat").send(event("INITIAL_PURCHASE", now, in30Days)).expect(401);
    await http.post("/webhooks/revenuecat").set("Authorization", "mauvais").send(event("INITIAL_PURCHASE", now, in30Days)).expect(401);
    // Un jeton Firebase valide ne donne pas accès au webhook.
    await http.post("/webhooks/revenuecat").set("Authorization", `Bearer ${token}`).send(event("INITIAL_PURCHASE", now, in30Days)).expect(401);

    const webhook = (body: object) => http.post("/webhooks/revenuecat").set("Authorization", WEBHOOK_SECRET).send(body);
    await webhook(event("INITIAL_PURCHASE", now, in30Days)).expect(200, { outcome: "applied" });
    let me = await http.get("/me").set("Authorization", `Bearer ${token}`).expect(200);
    expect(me.body).toMatchObject({ subscriptionTier: "premium", premiumUntil: new Date(in30Days).toISOString() });

    // Événement plus ancien arrivé en retard : ignoré.
    await webhook(event("EXPIRATION", now - 1000, now - 1000)).expect(200, { outcome: "stale" });
    await webhook(event("EXPIRATION", now + 1000, in30Days)).expect(200, { outcome: "applied" });
    me = await http.get("/me").set("Authorization", `Bearer ${token}`).expect(200);
    expect(me.body.subscriptionTier).toBe("free");

    // Compte inconnu ou événement de test : acquittés sans erreur, RevenueCat ne réessaie pas.
    await webhook({ event: { ...event("RENEWAL", now + 2000, in30Days).event, app_user_id: "inconnu" } }).expect(200, { outcome: "unknown-user" });
    await webhook({ event: { type: "TEST", event_timestamp_ms: now } }).expect(200, { outcome: "ignored" });
  });

  it("SnapCore+ : lien de paiement propre au compte", async () => {
    const { token, id } = await register("uid-1", "nathan");
    const res = await request(app.getHttpServer()).post("/subscription/checkout").set("Authorization", `Bearer ${token}`).expect(200);
    expect(res.body.url).toBe(`https://pay.rev.cat/sandbox-token/${id}?email=nathan%40example.com`);
  });

  it("SnapCore+ : la page des conditions est publique (liée depuis la page de paiement)", async () => {
    const res = await request(app.getHttpServer()).get("/legal/cgu").expect(200);
    expect(res.headers["content-type"]).toContain("text/html");
    expect(res.text).toContain("Conditions d'utilisation de SnapCore+");
  });

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

    expect(me.body).toMatchObject({ username: "nathan", firstName: "Nathan", email: "nathan@example.com", subscriptionTier: "free" });
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
      expect.objectContaining({ status: "ACCEPTED", user: { id: bob.id, username: "bob", firstName: "Bob" } }),
    ]);
  });

  it("pseudo déjà pris → 409", async () => {
    await register("uid-a", "alice");
    const token = identity.addAccount("uid-b");

    await request(app.getHttpServer())
      .post("/auth/register")
      .set("Authorization", `Bearer ${token}`)
      .send({ username: "ALICE", firstName: "Alice" })
      .expect(409);
  });

  it("le prénom est obligatoire à l'inscription et validé", async () => {
    const token = identity.addAccount("uid-1");
    const http = request(app.getHttpServer());

    await http.post("/auth/register").set("Authorization", `Bearer ${token}`).send({ username: "nathan" }).expect(400);
    await http.post("/auth/register").set("Authorization", `Bearer ${token}`).send({ username: "nathan", firstName: "N4than" }).expect(400);
    expect(users.rows.size).toBe(0);
  });

  it("le prénom est modifiable depuis le profil", async () => {
    const { token } = await register("uid-1", "nathan");

    const res = await request(app.getHttpServer())
      .patch("/me")
      .set("Authorization", `Bearer ${token}`)
      .send({ firstName: "Jean-Marc" })
      .expect(200);

    expect(res.body).toMatchObject({ username: "nathan", firstName: "Jean-Marc" });
  });
});
