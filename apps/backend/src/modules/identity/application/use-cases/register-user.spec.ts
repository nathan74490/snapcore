import { ConflictError, ForbiddenError, NotFoundError, UnauthenticatedError, ValidationError } from "../../../../shared/domain/errors";
import { FakeIdentityProvider, FixedClock } from "../../../../test/fakes";
import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import { RegisterUser } from "./register-user";

// FixedClock : 25 septembre 2026. Né le 25/09/2013 = 13 ans le jour même.
const BIRTHDAY = "2000-01-01";

describe("RegisterUser", () => {
  let users: InMemoryUserRepository;
  let identity: FakeIdentityProvider;
  let registerUser: RegisterUser;

  beforeEach(() => {
    users = new InMemoryUserRepository();
    identity = new FakeIdentityProvider();
    registerUser = new RegisterUser(users, identity, undefined, new FixedClock());
  });

  it("crée le compte avec le pseudo normalisé et l'email lu chez Firebase", async () => {
    const idToken = identity.addAccount("uid-1", "nathan@example.com");

    const user = await registerUser.execute({
      idToken,
      username: "  Nathan ",
      firstName: " Jean-Marc ",
      lastName: "  de  La Fontaine ",
      birthday: "2005-03-14",
      phoneNumber: "+33 6 12 34 56 78",
    });

    expect(user).toMatchObject({
      username: "nathan",
      firstName: "Jean-Marc",
      lastName: "de La Fontaine",
      birthday: new Date("2005-03-14T00:00:00Z"),
      firebaseUid: "uid-1",
      email: "nathan@example.com",
      phoneNumber: "+33612345678",
    });
  });

  it("vérifie le token avec checkRevoked pour ne jamais recréer un compte Firebase supprimé", async () => {
    const idToken = identity.addAccount("uid-1");
    identity.deleteAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY })).rejects.toThrow(UnauthenticatedError);
    expect(identity.calls).toEqual([{ checkRevoked: true }]);
    expect(users.rows.size).toBe(0);
  });

  it("est idempotent : un réessai renvoie le compte déjà créé", async () => {
    const idToken = identity.addAccount("uid-1");
    const first = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY });

    const retry = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY });

    expect(retry.id).toBe(first.id);
    expect(users.rows.size).toBe(1);
  });

  it("refuse un compte en cours de suppression plutôt que de le réactiver", async () => {
    const idToken = identity.addAccount("uid-1");
    const user = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY });
    users.rows.get(user.id)!.deletionRequestedAt = new Date();

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY })).rejects.toThrow(NotFoundError);
  });

  it("refuse un compte suspendu par la modération avec un message explicite (ADR-021)", async () => {
    const idToken = identity.addAccount("uid-1");
    const user = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY });
    users.rows.get(user.id)!.moderationStatus = "SUSPENDED";

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: BIRTHDAY })).rejects.toThrow(new ForbiddenError("Ce compte est suspendu"));
  });

  it("renvoie un conflit si le pseudo est déjà pris, sans tenir compte de la casse", async () => {
    await users.seed("nathan");
    const idToken = identity.addAccount("uid-2");

    await expect(registerUser.execute({ idToken, username: "NATHAN", firstName: "Nathan", birthday: BIRTHDAY })).rejects.toThrow(
      new ConflictError("Ce pseudo est déjà pris"),
    );
  });

  it("refuse un pseudo invalide", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "1abc", firstName: "Nathan", birthday: BIRTHDAY })).rejects.toThrow(ValidationError);
    await expect(registerUser.execute({ idToken, username: "ab", firstName: "Nathan", birthday: BIRTHDAY })).rejects.toThrow(ValidationError);
  });

  it("refuse un prénom invalide sans créer de compte", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "   ", birthday: BIRTHDAY })).rejects.toThrow(ValidationError);
    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan74", birthday: BIRTHDAY })).rejects.toThrow(ValidationError);
    expect(users.rows.size).toBe(0);
  });

  it("sans nom de famille (absent ou vide), enregistre null", async () => {
    const user = await registerUser.execute({ idToken: identity.addAccount("uid-1"), username: "nathan", firstName: "Nathan", birthday: BIRTHDAY });
    const other = await registerUser.execute({
      idToken: identity.addAccount("uid-2"),
      username: "marie",
      firstName: "Marie",
      lastName: "   ",
      birthday: BIRTHDAY,
    });

    expect(user.lastName).toBeNull();
    expect(other.lastName).toBeNull();
  });

  it("refuse un nom de famille invalide sans créer de compte", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(
      registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", lastName: "Dupont2", birthday: BIRTHDAY }),
    ).rejects.toThrow(ValidationError);
    expect(users.rows.size).toBe(0);
  });

  it("exige 13 ans révolus à la date de l'horloge, sans créer de compte", async () => {
    const idToken = identity.addAccount("uid-1");

    await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: "2013-09-26" })).rejects.toThrow(
      new ValidationError("Tu dois avoir au moins 13 ans pour t'inscrire"),
    );
    expect(users.rows.size).toBe(0);

    const user = await registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday: "2013-09-25" });
    expect(user.birthday).toEqual(new Date("2013-09-25T00:00:00Z"));
  });

  it("refuse une date de naissance invalide ou future", async () => {
    const idToken = identity.addAccount("uid-1");

    for (const birthday of ["", "25/09/2000", "2001-02-29", "2027-01-01"]) {
      await expect(registerUser.execute({ idToken, username: "nathan", firstName: "Nathan", birthday })).rejects.toThrow(ValidationError);
    }
    expect(users.rows.size).toBe(0);
  });
});
