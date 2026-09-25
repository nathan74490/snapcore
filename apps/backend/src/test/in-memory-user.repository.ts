import { randomUUID } from "node:crypto";
import { UniqueViolationError } from "../shared/domain/errors";
import type { NewUser, ProfilePatch, UserRepository } from "../modules/identity/application/ports/user.repository";
import type { UserAccount } from "../modules/identity/domain/user";

const UNIQUE_FIELDS = ["username", "firebaseUid", "email", "phoneNumber"] as const;

// Reproduit les contraintes @unique du schéma Prisma, pour tester les cas d'usage sans base.
export class InMemoryUserRepository implements UserRepository {
  readonly rows = new Map<string, UserAccount>();

  async findActiveById(id: string) {
    return this.active((u) => u.id === id);
  }
  async findActiveByFirebaseUid(firebaseUid: string) {
    return this.active((u) => u.firebaseUid === firebaseUid);
  }
  async findActiveByUsername(username: string) {
    return this.active((u) => u.username === username);
  }
  async findByFirebaseUidIncludingDeleted(firebaseUid: string) {
    return [...this.rows.values()].find((u) => u.firebaseUid === firebaseUid) ?? null;
  }

  async create(user: NewUser): Promise<UserAccount> {
    const row: UserAccount = {
      id: randomUUID(),
      profilePhotoUrl: null,
      subscriptionTier: "free",
      premiumUntil: null,
      createdAt: new Date(),
      deletionRequestedAt: null,
      ...user,
    };
    this.assertUnique(row);
    this.rows.set(row.id, row);
    return { ...row };
  }

  async updateProfile(id: string, patch: ProfilePatch): Promise<UserAccount> {
    const current = this.rows.get(id);
    if (!current) throw new Error(`User ${id} introuvable`);
    const next = { ...current, ...patch };
    this.assertUnique(next);
    this.rows.set(id, next);
    return { ...next };
  }

  /** Raccourci de test : crée directement un compte actif. */
  async seed(username: string, overrides: Partial<UserAccount> = {}): Promise<UserAccount> {
    const user = await this.create({ username, firebaseUid: `uid-${username}`, email: null, phoneNumber: null });
    const seeded = { ...user, ...overrides };
    this.rows.set(user.id, seeded);
    return { ...seeded };
  }

  private active(predicate: (u: UserAccount) => boolean): UserAccount | null {
    const found = [...this.rows.values()].find((u) => predicate(u) && u.deletionRequestedAt === null);
    return found ? { ...found } : null;
  }

  private assertUnique(candidate: UserAccount): void {
    for (const field of UNIQUE_FIELDS) {
      const value = candidate[field];
      if (value === null) continue;
      if ([...this.rows.values()].some((u) => u.id !== candidate.id && u[field] === value)) {
        throw new UniqueViolationError(field);
      }
    }
  }
}
