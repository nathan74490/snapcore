import { Injectable } from "@nestjs/common";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { DeviceTokenRepository } from "../application/ports";

@Injectable()
export class PrismaDeviceTokenRepository implements DeviceTokenRepository {
  constructor(private readonly prisma: PrismaService) {}

  async register(userId: string, token: string): Promise<void> {
    // Atomique grâce à @unique sur token : un appareil n'appartient qu'à un compte à la fois.
    await this.prisma.deviceToken.upsert({ where: { token }, create: { userId, token }, update: { userId } });
  }

  async unregister(userId: string, token: string): Promise<boolean> {
    const { count } = await this.prisma.deviceToken.deleteMany({ where: { userId, token } });
    return count > 0;
  }

  async listTokens(userId: string): Promise<string[]> {
    const rows = await this.prisma.deviceToken.findMany({ where: { userId }, select: { token: true } });
    return rows.map((row) => row.token);
  }

  async deleteTokens(tokens: string[]): Promise<void> {
    await this.prisma.deviceToken.deleteMany({ where: { token: { in: tokens } } });
  }
}
