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
      .send({ username, firstName: username.charAt(0).toUpperCase() + username.slice(1), birthday: "2000-01-01" });
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
      .send({ username: "ALICE", firstName: "Alice", birthday: "2000-01-01" })
      .expect(409);
  });

  it("le prénom est obligatoire à l'inscription et validé", async () => {
    const token = identity.addAccount("uid-1");
    const http = request(app.getHttpServer());

    await http.post("/auth/register").set("Authorization", `Bearer ${token}`).send({ username: "nathan", birthday: "2000-01-01" }).expect(400);
    await http
      .post("/auth/register")
      .set("Authorization", `Bearer ${token}`)
      .send({ username: "nathan", firstName: "N4than", birthday: "2000-01-01" })
      .expect(400);
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

  describe("onboarding : nom, date de naissance, pseudo, contacts (ADR-029)", () => {
    it("nom de famille et date de naissance enregistrés à l'inscription, visibles dans /me seulement", async () => {
      const token = identity.addAccount("uid-1");
      const http = request(app.getHttpServer());

      const registered = await http
        .post("/auth/register")
        .set("Authorization", `Bearer ${token}`)
        .send({ username: "nathan", firstName: "Nathan", lastName: "Boudet", birthday: "2004-05-12" })
        .expect(200);
      expect(registered.body).toMatchObject({ firstName: "Nathan", lastName: "Boudet", birthday: "2004-05-12" });

      const me = await http.get("/me").set("Authorization", `Bearer ${token}`).expect(200);
      expect(me.body).toMatchObject({ lastName: "Boudet", birthday: "2004-05-12" });

      const alice = await register("uid-a", "alice");
      const search = await http.get("/users/search?username=nathan").set("Authorization", `Bearer ${alice.token}`).expect(200);
      expect(search.body).not.toHaveProperty("lastName");
      expect(search.body).not.toHaveProperty("birthday");
    });

    it("la date de naissance est obligatoire ; moins de 13 ans → 400 avec le message attendu", async () => {
      const token = identity.addAccount("uid-1");
      const http = request(app.getHttpServer());
      const year = new Date().getUTCFullYear();

      await http.post("/auth/register").set("Authorization", `Bearer ${token}`).send({ username: "nathan", firstName: "Nathan" }).expect(400);
      const tooYoung = await http
        .post("/auth/register")
        .set("Authorization", `Bearer ${token}`)
        .send({ username: "nathan", firstName: "Nathan", birthday: `${year - 12}-01-01` })
        .expect(400);
      expect(tooYoung.body.message).toBe("Tu dois avoir au moins 13 ans pour t'inscrire");
      await http
        .post("/auth/register")
        .set("Authorization", `Bearer ${token}`)
        .send({ username: "nathan", firstName: "Nathan", birthday: "2001-02-30" })
        .expect(400);
      expect(users.rows.size).toBe(0);
    });

    it("PATCH /me : nom de famille ajouté puis retiré (chaîne vide), absent de /me une fois retiré", async () => {
      const { token } = await register("uid-1", "nathan");
      const http = request(app.getHttpServer());

      await http.patch("/me").set("Authorization", `Bearer ${token}`).send({ lastName: "Boudet" }).expect(200, /"lastName":"Boudet"/);
      const cleared = await http.patch("/me").set("Authorization", `Bearer ${token}`).send({ lastName: "" }).expect(200);
      expect(cleared.body).not.toHaveProperty("lastName");
      expect(cleared.body.birthday).toBe("2000-01-01");
    });

    it("GET /users/username-availability est public et ne renvoie que disponible / raison", async () => {
      await register("uid-1", "nathan");
      const http = request(app.getHttpServer());

      await http.get("/users/username-availability?username=NaThAn").expect(200, { available: false, reason: "Ce pseudo est déjà pris" });
      await http.get("/users/username-availability?username=marie").expect(200, { available: true });
      const invalid = await http.get("/users/username-availability?username=1abc").expect(200);
      expect(invalid.body).toMatchObject({ available: false, reason: expect.stringContaining("Pseudo invalide") });
      await http.get("/users/username-availability").expect(400);
    });

    it("POST /users/contacts/match : authentifié, borné, renvoie la chaîne envoyée et la relation", async () => {
      const alice = await register("uid-a", "alice");
      const bob = await register("uid-b", "bob");
      const http = request(app.getHttpServer());
      await http.patch("/me").set("Authorization", `Bearer ${bob.token}`).send({ phoneNumber: "+33612345678" }).expect(200);

      await http.post("/users/contacts/match").send({ phoneNumbers: ["0612345678"] }).expect(401);
      const auth = { Authorization: `Bearer ${alice.token}` };
      await http.post("/users/contacts/match").set(auth).send({ phoneNumbers: [] }).expect(400);
      await http.post("/users/contacts/match").set(auth).send({ phoneNumbers: Array(2001).fill("0612345678") }).expect(400);
      await http.post("/users/contacts/match").set(auth).send({ phoneNumbers: ["0".repeat(33)] }).expect(400);
      await http.post("/users/contacts/match").set(auth).send({ phoneNumbers: [612345678] }).expect(400);

      const res = await http.post("/users/contacts/match").set(auth).send({ phoneNumbers: ["06 12 34 56 78", "123"] }).expect(200);
      expect(res.body).toEqual([
        { phoneNumber: "06 12 34 56 78", user: { id: bob.id, username: "bob", firstName: "Bob" }, relation: "NONE" },
      ]);
    });
  });

  describe("avatars dessinés (ADR-028)", () => {
    const AVATAR = {
      top: "bob",
      hairColor: "724133",
      skinColor: "ffdbb4",
      eyes: "happy",
      eyebrows: "defaultNatural",
      mouth: "smile",
      facialHair: "none",
      accessories: "round",
      clothing: "overall",
      clothesColor: "ffafb9",
      backgroundColor: "fffaa1",
    };

    it("PUT /me/avatar enregistre, l'avatar apparaît dans /me et chez les amis ; DELETE le retire (204)", async () => {
      const alice = await register("uid-a", "alice");
      const bob = await register("uid-b", "bob");
      const http = request(app.getHttpServer());
      const sent = await http.post("/friendships/requests").set("Authorization", `Bearer ${alice.token}`).send({ username: "bob" });
      await http.post(`/friendships/requests/${sent.body.friendshipId}/accept`).set("Authorization", `Bearer ${bob.token}`).expect(200);

      const saved = await http.put("/me/avatar").set("Authorization", `Bearer ${alice.token}`).send({ options: AVATAR }).expect(200);
      expect(saved.body).toMatchObject({ id: alice.id, username: "alice", avatar: AVATAR });

      await http.get("/me").set("Authorization", `Bearer ${alice.token}`).expect(200).expect((res) => expect(res.body.avatar).toEqual(AVATAR));
      const friends = await http.get("/friendships").set("Authorization", `Bearer ${bob.token}`).expect(200);
      expect(friends.body[0].user).toEqual({ id: alice.id, username: "alice", firstName: "Alice", avatar: AVATAR });
      const conversations = await http.get("/conversations").set("Authorization", `Bearer ${bob.token}`).expect(200);
      expect(conversations.body[0].friend.avatar).toEqual(AVATAR);

      await http.delete("/me/avatar").set("Authorization", `Bearer ${alice.token}`).expect(204, "");
      const me = await http.get("/me").set("Authorization", `Bearer ${alice.token}`).expect(200);
      expect(me.body).not.toHaveProperty("avatar");
    });

    it("PUT /me/avatar refuse des options invalides (400) et exige un compte", async () => {
      const { token } = await register("uid-1", "nathan");
      const http = request(app.getHttpServer());
      const put = (body: object) => http.put("/me/avatar").set("Authorization", `Bearer ${token}`).send(body);

      await put({ options: { ...AVATAR, mouth: "vomit" } }).expect(400);
      await put({ options: { ...AVATAR, seed: "x" } }).expect(400);
      await put({ options: { top: "bob" } }).expect(400);
      await put({ options: "bob" }).expect(400);
      await put({ ...AVATAR }).expect(400);
      await http.put("/me/avatar").send({ options: AVATAR }).expect(401);
      expect([...users.rows.values()][0].avatar).toBeNull();
    });

    it("GET /avatars/catalog exige un ID token et renvoie défauts et valeurs", async () => {
      const { token } = await register("uid-1", "nathan");
      await request(app.getHttpServer()).get("/avatars/catalog").expect(401);

      const res = await request(app.getHttpServer()).get("/avatars/catalog").set("Authorization", `Bearer ${token}`).expect(200);
      expect(res.body.defaults).toMatchObject({ top: "shortFlat", facialHair: "none", backgroundColor: "b6e3f4" });
      expect(res.body.options.top[0]).toBe("none");
      expect(res.body.options.mouth).not.toContain("vomit");
      expect(Object.keys(res.body.options)).toHaveLength(11);
    });

    it("GET /avatars/avataaars.png est public, PNG immuable en cache ; paramètre invalide → 400", async () => {
      const http = request(app.getHttpServer());

      const res = await http.get("/avatars/avataaars.png").query({ ...AVATAR, size: 128 }).buffer(true).expect(200);
      expect(res.headers["content-type"]).toBe("image/png");
      expect(res.headers["cache-control"]).toBe("public, max-age=31536000, immutable");
      const png = res.body as Buffer;
      expect(png.subarray(1, 4).toString()).toBe("PNG");
      expect(png.readUInt32BE(16)).toBe(128);

      await http.get("/avatars/avataaars.png").query({ ...AVATAR, size: 1024 }).expect(400);
      await http.get("/avatars/avataaars.png").query({ ...AVATAR, mouth: "vomit" }).expect(400);
      await http.get("/avatars/avataaars.png").query({ ...AVATAR, userId: "x" }).expect(400);
      await http.get("/avatars/avataaars.png").query({ top: "bob" }).expect(400);
    });
  });

  describe("Snap Map (ADR-031)", () => {
    const befriend = async (a: { token: string }, b: { token: string }, username: string) => {
      const http = request(app.getHttpServer());
      const sent = await http.post("/friendships/requests").set("Authorization", `Bearer ${a.token}`).send({ username });
      await http.post(`/friendships/requests/${sent.body.friendshipId}/accept`).set("Authorization", `Bearer ${b.token}`).expect(200);
    };

    const publishStory = async (token: string, extra: object) => {
      const http = request(app.getHttpServer());
      const ticket = await http
        .post("/media/uploads")
        .set("Authorization", `Bearer ${token}`)
        .send({ purpose: "STORY_PHOTO", contentType: "image/jpeg", contentLength: 1000 })
        .expect(201);
      world.storage.simulateUpload(`tmp/${ticket.body.uploadId}`, 1000, "image/jpeg");
      return http.post("/stories").set("Authorization", `Bearer ${token}`).send({ uploadId: ticket.body.uploadId, contentType: "PHOTO", ...extra });
    };

    it("réglages : GHOST par défaut, validation du corps, liste filtrée aux amis", async () => {
      const alice = await register("uid-a", "alice");
      const bob = await register("uid-b", "bob");
      const carol = await register("uid-c", "carol");
      await befriend(alice, bob, "bob");
      const http = request(app.getHttpServer());
      const auth = { Authorization: `Bearer ${alice.token}` };

      await http.get("/me/location-sharing").expect(401);
      await http.get("/me/location-sharing").set(auth).expect(200, { mode: "GHOST", friendIds: [] });

      const saved = await http
        .put("/me/location-sharing")
        .set(auth)
        .send({ mode: "FRIENDS_EXCEPT", friendIds: [bob.id, bob.id, carol.id] })
        .expect(200);
      expect(saved.body).toEqual({ mode: "FRIENDS_EXCEPT", friendIds: [bob.id] });
      await http.get("/me/location-sharing").set(auth).expect(200, saved.body);

      await http.put("/me/location-sharing").set(auth).send({ mode: "INVISIBLE", friendIds: [] }).expect(400);
      await http.put("/me/location-sharing").set(auth).send({ mode: "GHOST" }).expect(400);
      await http.put("/me/location-sharing").set(auth).send({ mode: "GHOST", friendIds: [1] }).expect(400);
      const tooMany = Array.from({ length: 2001 }, (_, i) => `id-${i}`);
      await http.put("/me/location-sharing").set(auth).send({ mode: "ONLY_FRIENDS", friendIds: tooMany }).expect(400);
    });

    it("position : 204, ignorée en mode fantôme, visible des amis sur GET /map/friends", async () => {
      const alice = await register("uid-a", "alice");
      const bob = await register("uid-b", "bob");
      await befriend(alice, bob, "bob");
      const http = request(app.getHttpServer());
      const auth = { Authorization: `Bearer ${alice.token}` };
      const position = { latitude: 45.899247, longitude: 6.129384 };

      await http.put("/me/location").set(auth).send({ latitude: 91, longitude: 0 }).expect(400);
      await http.put("/me/location").set(auth).send({ latitude: 45 }).expect(400);
      await http.put("/me/location").set(auth).send({ latitude: "45", longitude: "6" }).expect(400);

      await http.put("/me/location").set(auth).send(position).expect(204);
      expect(world.locations.entries.has(alice.id)).toBe(false); // mode fantôme par défaut

      await http.put("/me/location-sharing").set(auth).send({ mode: "ALL_FRIENDS", friendIds: [] }).expect(200);
      await http.put("/me/location").set(auth).send(position).expect(204);

      const map = await http.get("/map/friends").set("Authorization", `Bearer ${bob.token}`).expect(200);
      expect(map.body).toEqual([
        { user: { id: alice.id, username: "alice", firstName: "Alice" }, ...position, updatedAt: expect.any(String) },
      ]);
      await http.get("/map/friends").set(auth).expect(200, []);

      await http.put("/me/location-sharing").set(auth).send({ mode: "GHOST", friendIds: [] }).expect(200);
      expect(world.locations.entries.has(alice.id)).toBe(false);
      await http.get("/map/friends").set("Authorization", `Bearer ${bob.token}`).expect(200, []);
    });

    it("stories : coordonnées facultatives (les deux ou aucune), GET /map/stories", async () => {
      const alice = await register("uid-a", "alice");
      const bob = await register("uid-b", "bob");
      await befriend(alice, bob, "bob");
      const http = request(app.getHttpServer());

      expect((await publishStory(alice.token, { latitude: 45.9 })).status).toBe(400);
      expect((await publishStory(alice.token, { latitude: 45.9, longitude: 200 })).status).toBe(400);
      expect((await publishStory(alice.token, { latitude: null, longitude: null })).status).toBe(201); // null = absent

      await http.put("/me/location-sharing").set("Authorization", `Bearer ${alice.token}`).send({ mode: "ALL_FRIENDS", friendIds: [] }).expect(200);
      const published = await publishStory(alice.token, { latitude: 45.899247, longitude: 6.129384 });
      expect(published.status).toBe(201);
      await publishStory(alice.token, {}); // sans position

      const map = await http.get("/map/stories").set("Authorization", `Bearer ${bob.token}`).expect(200);
      expect(map.body).toEqual([
        {
          id: published.body.id,
          author: { id: alice.id, username: "alice", firstName: "Alice" },
          contentType: "PHOTO",
          latitude: 45.899,
          longitude: 6.129,
          createdAt: published.body.createdAt,
          expiresAt: published.body.expiresAt,
          viewed: false,
        },
      ]);

      await http.post(`/stories/${published.body.id}/view`).set("Authorization", `Bearer ${bob.token}`).expect(200);
      expect((await http.get("/map/stories").set("Authorization", `Bearer ${bob.token}`)).body[0].viewed).toBe(true);
    });
  });

  describe("groupes (ADR-032)", () => {
    const befriend = async (a: { token: string }, b: { token: string }, username: string) => {
      const http = request(app.getHttpServer());
      const sent = await http.post("/friendships/requests").set("Authorization", `Bearer ${a.token}`).send({ username });
      await http.post(`/friendships/requests/${sent.body.friendshipId}/accept`).set("Authorization", `Bearer ${b.token}`).expect(200);
    };

    it("parcours : création, liste, détail, renommage, ajout, chat, lecture, départ ; codes et validation", async () => {
      const alice = await register("uid-a", "alice");
      const bob = await register("uid-b", "bob");
      const carol = await register("uid-c", "carol");
      const dave = await register("uid-d", "dave");
      for (const [friend, name] of [[bob, "bob"], [carol, "carol"], [dave, "dave"]] as const) await befriend(alice, friend, name);
      const http = request(app.getHttpServer());
      const as = (user: { token: string }) => ({ Authorization: `Bearer ${user.token}` });

      await http.post("/groups").send({ memberIds: [bob.id, carol.id] }).expect(401);
      await http.post("/groups").set(as(alice)).send({ memberIds: ["pas-un-uuid"] }).expect(400);
      await http.post("/groups").set(as(alice)).send({ memberIds: [bob.id, carol.id], admin: true }).expect(400);
      const tooFew = await http.post("/groups").set(as(alice)).send({ memberIds: [bob.id] }).expect(400);
      expect(tooFew.body.message).toBe("Choisis au moins 2 amis pour créer un groupe");
      const notFriend = await http.post("/groups").set(as(bob)).send({ memberIds: [carol.id, dave.id] }).expect(403);
      expect(notFriend.body.message).toBe("Tu ne peux ajouter que tes amis à un groupe");

      const created = await http.post("/groups").set(as(alice)).send({ name: " Soirée ", memberIds: [bob.id, carol.id] }).expect(201);
      expect(created.body).toMatchObject({ name: "Soirée", formerMembers: [] });
      expect(created.body.members).toHaveLength(3);
      const groupId = created.body.id as string;

      const notMember = await http.get(`/groups/${groupId}`).set(as(dave)).expect(404);
      expect(notMember.body.message).toBe("Groupe introuvable");
      await http.get("/groups/pas-un-uuid").set(as(alice)).expect(400);
      await http.get(`/groups/${groupId}`).set(as(bob)).expect(200);

      await http.patch(`/groups/${groupId}`).set(as(bob)).send({}).expect(400);
      const renamed = await http.patch(`/groups/${groupId}`).set(as(bob)).send({ name: "" }).expect(200);
      expect(renamed.body).not.toHaveProperty("name");
      await http.patch(`/groups/${groupId}`).set(as(bob)).send({ name: null }).expect(200);
      await http.patch(`/groups/${groupId}`).set(as(bob)).send({ name: "x".repeat(51) }).expect(400);

      await http.post(`/groups/${groupId}/members`).set(as(alice)).send({ userIds: [] }).expect(400);
      const added = await http.post(`/groups/${groupId}/members`).set(as(alice)).send({ userIds: [dave.id] }).expect(200);
      expect(added.body.members).toHaveLength(4);

      const chat = await http.post(`/groups/${groupId}/messages/text`).set(as(bob)).send({ content: "salut" }).expect(201);
      expect(chat.body).toMatchObject({ groupId, senderId: bob.id, contentType: "TEXT", content: "salut", seenBy: [], openedByMe: false });
      await http.post(`/groups/${groupId}/typing`).set(as(bob)).expect(204);

      const [summary] = (await http.get("/groups").set(as(alice)).expect(200)).body;
      expect(summary).toMatchObject({ group: { id: groupId }, lastMessage: { id: chat.body.id }, unread: true });
      await http.post(`/groups/${groupId}/read`).set(as(alice)).expect(204);
      const history = await http.get(`/groups/${groupId}/messages?limit=10`).set(as(bob)).expect(200);
      expect(history.body[0].seenBy).toEqual([alice.id]);
      await http.get(`/groups/${groupId}/messages?limit=0`).set(as(bob)).expect(400);

      await http.post(`/group-messages/${chat.body.id}/view`).set(as(alice)).expect(404);
      await http.post(`/groups/${groupId}/messages/snap`).set(as(bob)).send({ uploadId: chat.body.id, contentType: "PHOTO" }).expect(400);

      await http.delete(`/groups/${groupId}/members/me`).set(as(carol)).expect(204);
      await http.delete(`/groups/${groupId}/members/me`).set(as(carol)).expect(404);
      expect((await http.get("/groups").set(as(carol)).expect(200)).body).toEqual([]);
    });
  });
});
