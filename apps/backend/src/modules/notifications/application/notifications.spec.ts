import { NotFoundError } from "../../../shared/domain/errors";
import { silentLogger } from "../../../test/in-memory-media";
import {
  InMemoryDeviceTokens,
  InMemoryPresence,
  RecordingPush,
  RecordingRealtime,
} from "../../../test/in-memory-notifications";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import type { UserAccount } from "../../identity/domain/user";
import { DeviceTokenUseCases } from "./device-tokens";
import { UserEventPublisher } from "./user-event-publisher";

describe("Notifications (ADR-018)", () => {
  let presence: InMemoryPresence;
  let realtime: RecordingRealtime;
  let push: RecordingPush;
  let devices: InMemoryDeviceTokens;
  let publisher: UserEventPublisher;
  let deviceUseCases: DeviceTokenUseCases;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    presence = new InMemoryPresence();
    realtime = new RecordingRealtime();
    push = new RecordingPush();
    devices = new InMemoryDeviceTokens();
    publisher = new UserEventPublisher(presence, realtime, push, devices, silentLogger);
    deviceUseCases = new DeviceTokenUseCases(devices);
    const users = new InMemoryUserRepository();
    alice = await users.seed("alice");
    bob = await users.seed("bob");
  });

  const received = { type: "message.received", messageId: "m1" } as const;

  describe("UserEventPublisher", () => {
    it("utilisateur connecté : livraison temps réel, pas de push", async () => {
      await presence.touch(bob.id, "socket-1");
      await deviceUseCases.register(bob, "token-bob");

      await publisher.publish(bob.id, received);

      expect(realtime.emitted).toEqual([{ userId: bob.id, event: received }]);
      expect(push.sent).toEqual([]);
    });

    it("utilisateur hors ligne : push FCM sur TOUS ses appareils, contenu minimal", async () => {
      await deviceUseCases.register(bob, "phone");
      await deviceUseCases.register(bob, "tablet");

      await publisher.publish(bob.id, received);

      expect(realtime.emitted).toEqual([]);
      expect(push.sent).toEqual([{ tokens: ["phone", "tablet"], data: { type: "message.received", messageId: "m1" } }]);
    });

    it("pas de push pour une simple mise à jour d'affichage (Snap ouvert)", async () => {
      await deviceUseCases.register(alice, "phone");
      await publisher.publish(alice.id, { type: "snap.opened", messageId: "m1" });
      expect(push.sent).toEqual([]);
    });

    it("supprime les tokens rejetés définitivement par FCM", async () => {
      await deviceUseCases.register(bob, "alive");
      await deviceUseCases.register(bob, "uninstalled");
      push.deadTokens.add("uninstalled");

      await publisher.publish(bob.id, received);

      expect(await devices.listTokens(bob.id)).toEqual(["alive"]);
    });

    it("n'échoue jamais : une panne de push n'interrompt pas l'action métier", async () => {
      await deviceUseCases.register(bob, "phone");
      jest.spyOn(push, "send").mockRejectedValue(new Error("FCM indisponible"));

      await expect(publisher.publish(bob.id, received)).resolves.toBeUndefined();
    });

    it("après déconnexion du dernier appareil, repasse au push", async () => {
      await presence.touch(bob.id, "socket-1");
      await presence.touch(bob.id, "socket-2");
      await presence.disconnect(bob.id, "socket-1");
      await publisher.publish(bob.id, received);
      expect(realtime.emitted).toHaveLength(1);

      await presence.disconnect(bob.id, "socket-2");
      await deviceUseCases.register(bob, "phone");
      await publisher.publish(bob.id, received);
      expect(push.sent).toHaveLength(1);
    });
  });

  describe("tokens d'appareil", () => {
    it("un changement de compte sur le même appareil réaffecte le token (upsert)", async () => {
      await deviceUseCases.register(alice, "shared-phone");
      await deviceUseCases.register(bob, "shared-phone");

      expect(await devices.listTokens(alice.id)).toEqual([]);
      expect(await devices.listTokens(bob.id)).toEqual(["shared-phone"]);
    });

    it("au logout, on ne peut supprimer que son propre token", async () => {
      await deviceUseCases.register(alice, "phone");

      await expect(deviceUseCases.unregister(bob, "phone")).rejects.toThrow(NotFoundError);
      await deviceUseCases.unregister(alice, "phone");
      expect(await devices.listTokens(alice.id)).toEqual([]);
    });
  });
});
