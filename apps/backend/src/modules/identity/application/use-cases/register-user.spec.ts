import { ConflictError, NotFoundError, UnauthenticatedError, ValidationError } from "../../../../shared/domain/errors";
import { FakeIdentityProvider } from "../../../../test/fakes";
import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import { RegisterUser } from "./register-user";

describe("RegisterUser", () => {
  let users: InMemoryUserRepository;
  let identity: FakeIdentityProvider;
  let registerUser: RegisterUser;

  beforeEach(() => {
    users = new InMemoryUserRepository();
    identity = new FakeIdentityProvider();
    registerUser = new RegisterUser(users, identity);
  });

  it("crée le compte avec le pseudo normalisé et l'email lu chez Firebase", async () => {
    const idToken = identity.addAccount("uid-1", "nathan@example.com");

    const user = await registerUser.execute({ idToken, username: "  Nathan ", phoneNumber: "+33 6 12 34 56 78" });

    expect(user).toMatchObject({
      username: "nathan",
      firebaseUid: "uid-1",
      email: "nathan@example.com",
      phoneNumber: "+33612345678",
    });
  });

  it("vérifie le token avec checkRevoked pour ne jamais recréer un compte Firebase supprimé", async () => {
    const idToken = identity.addAccount("uid-1");
    identity.deleteAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "nathan" })).rejects.toThrow(UnauthenticatedError);
    expect(identity.calls).toEqual([{ checkRevoked: true }]);
    expect(users.rows.size).toBe(0);
  });

  it("est idempotent : un réessai renvoie le compte déjà créé", async () => {
    const idToken = identity.addAccount("uid-1");
    const first = await registerUser.execute({ idToken, username: "nathan" });

    const retry = await registerUser.execute({ idToken, username: "nathan" });

    expect(retry.id).toBe(first.id);
    expect(users.rows.size).toBe(1);
  });

  it("refuse un compte en cours de suppression plutôt que de le réactiver", async () => {
    const idToken = identity.addAccount("uid-1");
    const user = await registerUser.execute({ idToken, username: "nathan" });
    users.rows.get(user.id)!.deletionRequestedAt = new Date();

    await expect(registerUser.execute({ idToken, username: "nathan" })).rejects.toThrow(NotFoundError);
  });

  it("renvoie un conflit si le pseudo est déjà pris, sans tenir compte de la casse", async () => {
    await users.seed("nathan");
    const idToken = identity.addAccount("uid-2");

    await expect(registerUser.execute({ idToken, username: "NATHAN" })).rejects.toThrow(
      new ConflictError("Ce pseudo est déjà pris"),
    );
  });

  it("refuse un pseudo invalide", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "1abc" })).rejects.toThrow(ValidationError);
    await expect(registerUser.execute({ idToken, username: "ab" })).rejects.toThrow(ValidationError);
  });
});
