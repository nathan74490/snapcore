import { ValidationError } from "../../../../shared/domain/errors";
import { RecordingAnalytics } from "../../../../test/fakes";
import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import { DEFAULT_AVATAR } from "../../../avatars/domain/avatar-catalog";
import { toPublicProfile } from "../../domain/user";
import { presentSelf } from "../../interface/http/user.presenter";
import { UserAvatarUseCases } from "./user-avatar";

describe("UserAvatarUseCases (ADR-028)", () => {
  let users: InMemoryUserRepository;
  let avatars: UserAvatarUseCases;
  let analytics: RecordingAnalytics;

  beforeEach(() => {
    users = new InMemoryUserRepository();
    analytics = new RecordingAnalytics();
    avatars = new UserAvatarUseCases(users, analytics);
  });

  it("enregistre des options valides et les remplace au changement suivant", async () => {
    const user = await users.seed("nathan");

    const first = await avatars.set(user, { ...DEFAULT_AVATAR });
    expect(first.avatar).toEqual(DEFAULT_AVATAR);

    const changed = { ...DEFAULT_AVATAR, top: "none", accessories: "round" };
    const second = await avatars.set(first, changed);
    expect(second.avatar).toEqual(changed);
    expect(users.rows.get(user.id)!.avatar).toEqual(changed);
  });

  it("refuse des options invalides sans toucher l'avatar existant", async () => {
    const user = await avatars.set(await users.seed("nathan"), { ...DEFAULT_AVATAR });

    await expect(avatars.set(user, { ...DEFAULT_AVATAR, mouth: "vomit" })).rejects.toThrow(ValidationError);
    await expect(avatars.set(user, { ...DEFAULT_AVATAR, extra: "x" })).rejects.toThrow(ValidationError);
    await expect(avatars.set(user, { top: "shortFlat" })).rejects.toThrow(ValidationError);
    expect(users.rows.get(user.id)!.avatar).toEqual(DEFAULT_AVATAR);
  });

  it("retire l'avatar ; sans avatar, le retrait est sans effet", async () => {
    const user = await avatars.set(await users.seed("nathan"), { ...DEFAULT_AVATAR });

    const removed = await avatars.remove(user);
    expect(removed.avatar).toBeNull();
    expect(users.rows.get(user.id)!.avatar).toBeNull();
    await expect(avatars.remove(removed)).resolves.toMatchObject({ avatar: null });
  });

  it("présente l'avatar dans GET /me et dans le profil public, et l'omet s'il est absent", async () => {
    const alice = await users.seed("alice");
    expect(presentSelf(alice)).not.toHaveProperty("avatar");
    expect(toPublicProfile(alice)).toEqual({ id: alice.id, username: "alice", firstName: "Alice" });

    const bob = await avatars.set(await users.seed("bob"), { ...DEFAULT_AVATAR });
    expect(presentSelf(bob).avatar).toEqual(DEFAULT_AVATAR);
    expect(toPublicProfile(bob)).toEqual({ id: bob.id, username: "bob", firstName: "Bob", avatar: DEFAULT_AVATAR });
  });

  it("analytics (ADR-008) : avatar_saved puis avatar_removed, rien si les options sont refusées ou sans avatar", async () => {
    const user = await users.seed("nathan");
    await avatars.remove(user); // sans avatar : sans effet
    await expect(avatars.set(user, { top: "shortFlat" })).rejects.toThrow(ValidationError);
    const withAvatar = await avatars.set(user, { ...DEFAULT_AVATAR });
    await avatars.remove(withAvatar);

    expect(analytics.events).toEqual([
      { userId: user.id, event: "avatar_saved" },
      { userId: user.id, event: "avatar_removed" },
    ]);
  });
});
