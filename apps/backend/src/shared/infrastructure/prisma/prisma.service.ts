import { Injectable, OnModuleDestroy } from "@nestjs/common";
import { PrismaClient } from "@snapcore/db";

// La connexion est établie paresseusement par Prisma à la première requête :
// l'application (et les tests qui remplacent les dépôts) démarre sans base disponible.
@Injectable()
export class PrismaService extends PrismaClient implements OnModuleDestroy {
  async onModuleDestroy(): Promise<void> {
    await this.$disconnect();
  }
}
