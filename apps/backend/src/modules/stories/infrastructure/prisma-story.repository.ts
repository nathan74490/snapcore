import { Injectable } from "@nestjs/common";
import type { StoryItem as StoryItemRow } from "@snapcore/db";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { StoryRepository } from "../application/ports";
import type { StoryItem } from "../domain/story-item";

@Injectable()
export class PrismaStoryRepository implements StoryRepository {
  constructor(private readonly prisma: PrismaService) {}

  async create(item: StoryItem): Promise<StoryItem> {
    return toStoryItem(await this.prisma.storyItem.create({ data: item }));
  }

  async findById(id: string): Promise<StoryItem | null> {
    const row = await this.prisma.storyItem.findUnique({ where: { id } });
    return row && toStoryItem(row);
  }

  async delete(id: string): Promise<boolean> {
    const { count } = await this.prisma.storyItem.deleteMany({ where: { id } });
    return count > 0;
  }

  async listVisibleByAuthors(authorIds: string[], now: Date): Promise<StoryItem[]> {
    if (authorIds.length === 0) return [];
    // Filtre explicite sur expiresAt : jamais de dépendance à la seule purge effective (ADR-013).
    const rows = await this.prisma.storyItem.findMany({
      where: { authorId: { in: authorIds }, expiresAt: { gt: now } },
      orderBy: [{ createdAt: "asc" }, { id: "asc" }],
    });
    return rows.map(toStoryItem);
  }

  async findExpiredIds(now: Date, limit: number): Promise<string[]> {
    // Utilise @@index([expiresAt]).
    const rows = await this.prisma.storyItem.findMany({
      where: { expiresAt: { lte: now } },
      orderBy: { expiresAt: "asc" },
      take: limit,
      select: { id: true },
    });
    return rows.map((row) => row.id);
  }
}

function toStoryItem(row: StoryItemRow): StoryItem {
  return {
    id: row.id,
    authorId: row.authorId,
    mediaKey: row.mediaKey,
    contentType: row.contentType,
    thumbnailKey: row.thumbnailKey,
    latitude: row.latitude,
    longitude: row.longitude,
    createdAt: row.createdAt,
    expiresAt: row.expiresAt,
  };
}
