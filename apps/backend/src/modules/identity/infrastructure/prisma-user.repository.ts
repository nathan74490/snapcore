import { Injectable } from "@nestjs/common";
import type { User as UserRow } from "@snapcore/db";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { rethrowUniqueViolation } from "../../../shared/infrastructure/prisma/prisma-errors";
import type { NewUser, ProfilePatch, UserRepository } from "../application/ports/user.repository";
import type { UserAccount } from "../domain/user";

const ACTIVE = { deletionRequestedAt: null };

@Injectable()
export class PrismaUserRepository implements UserRepository {
  constructor(private readonly prisma: PrismaService) {}

  async findActiveById(id: string): Promise<UserAccount | null> {
    return this.prisma.user.findFirst({ where: { id, ...ACTIVE } }).then(toAccount);
  }

  async findActiveByFirebaseUid(firebaseUid: string): Promise<UserAccount | null> {
    return this.prisma.user.findFirst({ where: { firebaseUid, ...ACTIVE } }).then(toAccount);
  }

  async findActiveByUsername(username: string): Promise<UserAccount | null> {
    return this.prisma.user.findFirst({ where: { username, ...ACTIVE } }).then(toAccount);
  }

  async findByFirebaseUidIncludingDeleted(firebaseUid: string): Promise<UserAccount | null> {
    return this.prisma.user.findUnique({ where: { firebaseUid } }).then(toAccount);
  }

  async create(user: NewUser): Promise<UserAccount> {
    const row = await this.prisma.user.create({ data: user }).catch(rethrowUniqueViolation);
    return toAccount(row)!;
  }

  async updateProfile(id: string, patch: ProfilePatch): Promise<UserAccount> {
    const row = await this.prisma.user.update({ where: { id }, data: patch }).catch(rethrowUniqueViolation);
    return toAccount(row)!;
  }
}

function toAccount(row: UserRow | null): UserAccount | null {
  return row && { ...row };
}
