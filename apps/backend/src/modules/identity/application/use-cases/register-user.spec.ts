import { ConflictError, ForbiddenError, NotFoundError, UnauthenticatedError, ValidationError } from "../../../../shared/domain/errors";
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

    const user = await registerUser.execute({ idToken, username: "  Nathan ", firstName: " Jean-Marc ", phoneNumber: "+33 6 12 34 56 78" });

    expect(user).toMatchObject({
      username: "nathan",
      firstName: "Jean-Marc",
      firebaseUid: "uid-1",
      email: "nathan@example.com",
      phoneNumber: "+33612345678",
    });
  });

  it("vérifie le token avec checkRevoked pour ne jamais recréer un compte Firebase supprimé", async () => {
    const idToken = identity.addAccount("uid-1");
    identity.deleteAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" })).rejects.toThrow(UnauthenticatedError);
    expect(identity.calls).toEqual([{ checkRevoked: true }]);
    expect(users.rows.size).toBe(0);
  });

  it("est idempotent : un réessai renvoie le compte déjà créé", async () => {
    const idToken = identity.addAccount("uid-1");
    const first = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" });

    const retry = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" });

    expect(retry.id).toBe(first.id);
    expect(users.rows.size).toBe(1);
  });

  it("refuse un compte en cours de suppression plutôt que de le réactiver", async () => {
    const idToken = identity.addAccount("uid-1");
    const user = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" });
    users.rows.get(user.id)!.deletionRequestedAt = new Date();

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" })).rejects.toThrow(NotFoundError);
  });

  it("refuse un compte suspendu par la modération avec un message explicite (ADR-021)", async () => {
    const idToken = identity.addAccount("uid-1");
    const user = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" });
    users.rows.get(user.id)!.moderationStatus = "SUSPENDED";

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan" })).rejects.toThrow(new ForbiddenError("Ce compte est suspendu"));
  });

  it("renvoie un conflit si le pseudo est déjà pris, sans tenir compte de la casse", async () => {
    await users.seed("nathan");
    const idToken = identity.addAccount("uid-2");

    await expect(registerUser.execute({ idToken, username: "NATHAN", firstName: "Nathan" })).rejects.toThrow(
      new ConflictError("Ce pseudo est déjà pris"),
    );
  });

  it("refuse un pseudo invalide", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "1abc", firstName: "Nathan" })).rejects.toThrow(ValidationError);
    await expect(registerUser.execute({ idToken, username: "ab", firstName: "Nathan" })).rejects.toThrow(ValidationError);
  });

  it("refuse un prénom invalide sans créer de compte", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "   " })).rejects.toThrow(ValidationError);
    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan74" })).rejects.toThrow(ValidationError);
    expect(users.rows.size).toBe(0);
  });
});
