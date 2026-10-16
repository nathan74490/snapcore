import { ConflictError, ValidationError } from "../../../../shared/domain/errors";
import { FakeIdentityProvider } from "../../../../test/fakes";
import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import { SyncEmailFromIdentityProvider } from "./sync-email";
import { UpdateProfile } from "./update-profile";

describe("UpdateProfile", () => {
  let users: InMemoryUserRepository;
  let updateProfile: UpdateProfile;

  beforeEach(() => {
    users = new InMemoryUserRepository();
    updateProfile = new UpdateProfile(users);
  });

  it("permet de changer de pseudo (normalisé en minuscules)", async () => {
    const user = await users.seed("nathan");

    const updated = await updateProfile.execute(user, { username: "Nathan_74" });

    expect(updated.username).toBe("nathan_74");
  });

  it("renvoie 409 sans modifier la ligne si le nouveau pseudo est pris", async () => {
    await users.seed("alice");
    const user = await users.seed("nathan");

    await expect(updateProfile.execute(user, { username: "Alice" })).rejects.toThrow(ConflictError);
    expect(users.rows.get(user.id)!.username).toBe("nathan");
  });

  it("permet de retirer le numéro de téléphone", async () => {
    const user = await users.seed("nathan", { phoneNumber: "+33612345678" });

    const updated = await updateProfile.execute(user, { phoneNumber: null });

    expect(updated.phoneNumber).toBeNull();
  });

  it("permet de changer de prénom (espaces superflus retirés, casse conservée)", async () => {
    const user = await users.seed("nathan");

    const updated = await updateProfile.execute(user, { firstName: "  Marie  Ève " });

    expect(updated.firstName).toBe("Marie Ève");
  });

  it("refuse un prénom invalide sans modifier la ligne", async () => {
    const user = await users.seed("nathan");

    await expect(updateProfile.execute(user, { firstName: "<script>" })).rejects.toThrow(ValidationError);
    expect(users.rows.get(user.id)!.firstName).toBe("Nathan");
  });
});

describe("SyncEmailFromIdentityProvider", () => {
  it("recopie l'email confirmé côté Firebase, source de vérité", async () => {
    const users = new InMemoryUserRepository();
    const identity = new FakeIdentityProvider();
    const user = await users.seed("nathan", { email: "old@example.com" });
    identity.addAccount(user.firebaseUid, "new@example.com");

    const updated = await new SyncEmailFromIdentityProvider(users, identity).execute(user);

    expect(updated.email).toBe("new@example.com");
  });
});
