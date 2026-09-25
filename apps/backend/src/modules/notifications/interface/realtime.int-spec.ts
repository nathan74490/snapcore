import type { INestApplication } from "@nestjs/common";
import { io, type Socket } from "socket.io-client";
import { RedisIoAdapter } from "../../../shared/infrastructure/redis/redis-io.adapter";
import { createTestApp, TestWorld } from "../../../test/test-app";
import { PRESENCE_TRACKER, type PresenceTracker } from "../application/ports";
import { UserEventPublisher } from "../application/user-event-publisher";

// Deux instances du backend (même "base" en mémoire) derrière le même Redis réel :
// vérifie le pub/sub Socket.io (ADR-006) et la présence partagée (ADR-018).
describe("Temps réel multi-instance (Redis réel)", () => {
  const redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379";
  const world = new TestWorld();
  const instances: Array<{ app: INestApplication; adapter: RedisIoAdapter; url: string }> = [];
  const sockets: Socket[] = [];

  beforeAll(async () => {
    for (let i = 0; i < 2; i++) {
      const app = await createTestApp(world, { realPresence: true });
      const adapter = new RedisIoAdapter(app);
      await adapter.connect(redisUrl);
      app.useWebSocketAdapter(adapter);
      await app.listen(0);
      instances.push({ app, adapter, url: await app.getUrl() });
    }
  });

  afterEach(() => {
    sockets.splice(0).forEach((s) => s.disconnect());
  });

  afterAll(async () => {
    for (const { app, adapter } of instances) {
      await app.close();
      await adapter.close();
    }
  });

  const [A, B] = [0, 1];
  const publisherOf = (i: number) => instances[i].app.get(UserEventPublisher);
  const presenceOf = (i: number) => instances[i].app.get<PresenceTracker>(PRESENCE_TRACKER);

  async function account(name: string) {
    const token = world.identity.addAccount(`uid-${name}-${Date.now()}`);
    const user = await world.users.create({
      username: `${name}${Date.now() % 100000}`,
      firebaseUid: token.slice("token-".length),
      email: null,
      phoneNumber: null,
    });
    return { user, token };
  }

  function connect(instance: number, token: string | undefined): Promise<Socket> {
    const socket = io(instances[instance].url, { auth: token ? { token } : {}, transports: ["websocket"], reconnection: false });
    sockets.push(socket);
    return new Promise((resolve, reject) => {
      socket.once("ready", () => resolve(socket));
      socket.once("auth_error", (e: { message: string }) => reject(new Error(e.message)));
      socket.once("connect_error", reject);
    });
  }

  const nextEvent = <T>(socket: Socket, name: string, timeoutMs = 3000) =>
    new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${name} non reçu`)), timeoutMs);
      socket.once(name, (payload: T) => {
        clearTimeout(timer);
        resolve(payload);
      });
    });

  const waitFor = async (condition: () => Promise<boolean>, timeoutMs = 3000) => {
    const start = Date.now();
    while (!(await condition())) {
      if (Date.now() - start > timeoutMs) throw new Error("Délai dépassé");
      await new Promise((r) => setTimeout(r, 50));
    }
  };

  it("refuse une connexion sans token valide", async () => {
    await expect(connect(A, undefined)).rejects.toThrow("ID token manquant");
    await expect(connect(A, "token-inconnu")).rejects.toThrow();
  });

  it("refuse un compte en cours de suppression (ADR-019)", async () => {
    const { user, token } = await account("deleted");
    world.users.rows.get(user.id)!.deletionRequestedAt = new Date();
    await expect(connect(A, token)).rejects.toThrow("Compte introuvable");
  });

  it("un événement publié par l'instance B atteint le client connecté à l'instance A", async () => {
    const { user, token } = await account("bob");
    const socket = await connect(A, token);

    // La présence écrite par A est visible depuis B, via Redis.
    expect(await presenceOf(B).isOnline(user.id)).toBe(true);

    const received = nextEvent(socket, "message.received");
    await publisherOf(B).publish(user.id, { type: "message.received", messageId: "m-42" });

    await expect(received).resolves.toEqual({ type: "message.received", messageId: "m-42" });
    expect(world.push.sent).toEqual([]); // connecté : pas de push
  });

  it("deux appareils : tous deux reçoivent, et l'utilisateur reste en ligne tant qu'un seul est connecté", async () => {
    const { user, token } = await account("carol");
    const phone = await connect(A, token);
    const tablet = await connect(B, token);

    const both = Promise.all([nextEvent(phone, "snap.opened"), nextEvent(tablet, "snap.opened")]);
    await publisherOf(A).publish(user.id, { type: "snap.opened", messageId: "m-1" });
    await both;

    phone.disconnect();
    await new Promise((r) => setTimeout(r, 200));
    expect(await presenceOf(A).isOnline(user.id)).toBe(true);

    tablet.disconnect();
    await waitFor(async () => !(await presenceOf(A).isOnline(user.id)));
  });

  it("hors ligne : bascule sur le push FCM", async () => {
    const { user, token } = await account("dave");
    await world.devices.register(user.id, "fcm-token-dave");
    const socket = await connect(A, token);
    socket.disconnect();
    await waitFor(async () => !(await presenceOf(B).isOnline(user.id)));

    await publisherOf(B).publish(user.id, { type: "friend_request.received", friendshipId: "f-1" });

    expect(world.push.sent.at(-1)).toEqual({
      tokens: ["fcm-token-dave"],
      data: { type: "friend_request.received", friendshipId: "f-1" },
    });
  });
});
