import { randomUUID } from "node:crypto";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import { PrismaSubscriptionRepository } from "./prisma-subscription.repository";

// Contre le Postgres de test : la mise à jour conditionnelle doit ignorer un webhook plus ancien (ADR-025).
describe("PrismaSubscriptionRepository (Postgres réel)", () => {
  const prisma = new PrismaService();
  const users = new PrismaUserRepository(prisma);
  const subscriptions = new PrismaSubscriptionRepository(prisma);

  beforeEach(async () => {
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
  });

  afterAll(() => prisma.$disconnect());

  const newUser = () => users.create({ username: "nathan", firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });
  const at = (iso: string) => new Date(iso);

  it("applique un événement plus récent, ignore un événement plus ancien ou rejoué", async () => {
    const user = await newUser();
    const until = at("2026-11-15T10:00:00Z");

    expect(await subscriptions.applyIfNewer(user.id, { tier: "premium", premiumUntil: until }, at("2026-10-15T10:00:00Z"))).toBe(true);
    // Rejoué à l'identique, puis plus ancien : sans effet.
    expect(await subscriptions.applyIfNewer(user.id, { tier: "premium", premiumUntil: until }, at("2026-10-15T10:00:00Z"))).toBe(false);
    expect(await subscriptions.applyIfNewer(user.id, { tier: "free", premiumUntil: null }, at("2026-10-14T10:00:00Z"))).toBe(false);

    expect(await prisma.user.findUnique({ where: { id: user.id } })).toMatchObject({ subscriptionTier: "premium", premiumUntil: until });
  });

  it("retrouve le compte parmi les identifiants RevenueCat, en ignorant les ids anonymes", async () => {
    const user = await newUser();
    expect(await subscriptions.findUserId(["$RCAnonymousID:abc", randomUUID(), user.id])).toBe(user.id);
    expect(await subscriptions.findUserId(["$RCAnonymousID:abc"])).toBeNull();
  });
});
