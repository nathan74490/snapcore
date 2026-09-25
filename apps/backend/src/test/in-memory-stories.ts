import type { StoryPurgeScheduler, StoryRepository, StoryViewStore } from "../modules/stories/application/ports";
import type { StoryItem } from "../modules/stories/domain/story-item";

export class InMemoryStoryRepository implements StoryRepository {
  readonly rows = new Map<string, StoryItem>();

  async create(item: StoryItem) {
    this.rows.set(item.id, { ...item });
    return { ...item };
  }
  async findById(id: string) {
    const row = this.rows.get(id);
    return row ? { ...row } : null;
  }
  async delete(id: string) {
    return this.rows.delete(id);
  }
  async listVisibleByAuthors(authorIds: string[], now: Date) {
    return [...this.rows.values()]
      .filter((s) => authorIds.includes(s.authorId) && s.expiresAt > now)
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime() || a.id.localeCompare(b.id))
      .map((s) => ({ ...s }));
  }
  async findExpiredIds(now: Date, limit: number) {
    return [...this.rows.values()]
      .filter((s) => s.expiresAt <= now)
      .sort((a, b) => a.expiresAt.getTime() - b.expiresAt.getTime())
      .slice(0, limit)
      .map((s) => s.id);
  }
}

export class InMemoryStoryViews implements StoryViewStore {
  readonly sets = new Map<string, Set<string>>();

  async record(storyId: string, viewerId: string) {
    this.sets.set(storyId, (this.sets.get(storyId) ?? new Set()).add(viewerId));
  }
  async viewedAmong(storyIds: string[], viewerId: string) {
    return new Set(storyIds.filter((id) => this.sets.get(id)?.has(viewerId)));
  }
  async viewers(storyId: string) {
    return [...(this.sets.get(storyId) ?? [])];
  }
  async counts(storyIds: string[]) {
    return new Map(storyIds.map((id) => [id, this.sets.get(id)?.size ?? 0]));
  }
  async clear(storyId: string) {
    this.sets.delete(storyId);
  }
}

export class RecordingStoryPurgeScheduler implements StoryPurgeScheduler {
  readonly scheduled: Array<{ storyId: string; at: Date }> = [];
  readonly recovered: string[] = [];

  async schedule(storyId: string, at: Date) {
    this.scheduled.push({ storyId, at });
  }
  async recover(storyId: string) {
    this.recovered.push(storyId);
    return "untouched" as const;
  }
}
