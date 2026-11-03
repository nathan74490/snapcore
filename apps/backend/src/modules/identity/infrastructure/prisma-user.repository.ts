import { Injectable } from "@nestjs/common";
import { Prisma, type User as UserRow } from "@snapcore/db";
import type { AvatarOptions } from "@snapcore/types";
import { readStoredAvatar } from "../../avatars/domain/avatar-catalog";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { ACTIVE_USER_WHERE } from "../../../shared/infrastructure/prisma/active-user";
import { rethrowUniqueViolation } from "../../../shared/infrastructure/prisma/prisma-errors";
import type { NewUser, ProfilePatch, UserRepository } from "../application/ports/user.repository";
import type { UserAccount } from "../domain/user";

const ACTIVE = ACTIVE_USER_WHERE;

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

  // Sans ACTIVE_USER_WHERE : la ligne d'un compte supprimé ou suspendu garde son pseudo (contrainte @unique).
  async isUsernameTaken(username: string): Promise<boolean> {
    return (await this.prisma.user.count({ where: { username } })) > 0;
  }

  async findActiveByPhoneNumberSuffixes(suffixes: string[]): Promise<UserAccount[]> {
    if (suffixes.length === 0) return [];
    // Le numéro stocké est normalisé ("+" facultatif puis chiffres, parsePhoneNumber) : ses derniers
    // caractères sont des chiffres. right() n'a pas d'équivalent Prisma, d'où deux requêtes : les
    // identifiants par SQL brut (une seule comparaison par ligne), puis les comptes sous le filtre centralisé.
    const ids = await this.prisma.$queryRaw<Array<{ id: string }>>`
      SELECT id FROM "User"
      WHERE "phoneNumber" IS NOT NULL AND right("phoneNumber", ${suffixes[0].length}::int) = ANY(${suffixes}::text[])`;
    if (ids.length === 0) return [];
    const rows = await this.prisma.user.findMany({ where: { id: { in: ids.map((row) => row.id) }, ...ACTIVE } });
    return rows.map((row) => toAccount(row)!);
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

  async replaceProfilePhoto(id: string, expectedUrl: string | null, newUrl: string | null): Promise<UserAccount | null> {
    const { count } = await this.prisma.user.updateMany({
      where: { id, profilePhotoUrl: expectedUrl, ...ACTIVE },
      data: { profilePhotoUrl: newUrl },
    });
    return count === 0 ? null : this.findActiveById(id);
  }

  async updateAvatar(id: string, avatar: AvatarOptions | null): Promise<UserAccount> {
    // Colonne Json nullable : le NULL SQL s'écrit Prisma.DbNull (null seul est refusé par Prisma).
    const row = await this.prisma.user.update({ where: { id }, data: { avatar: avatar ? { ...avatar } : Prisma.DbNull } });
    return toAccount(row)!;
  }
}

function toAccount(row: UserRow | null): UserAccount | null {
  return row && { ...row, avatar: readStoredAvatar(row.avatar) };
}
