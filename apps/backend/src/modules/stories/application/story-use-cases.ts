import type {
  OwnStoryItemView,
  PublicUserProfile,
  SnapViewUrl,
  StoryContentType,
  StoryFeedEntry,
  StoryItemView,
} from "@snapcore/types";
import { randomUUID } from "node:crypto";
import { noAnalytics, type Analytics } from "../../../shared/domain/analytics";
import type { Clock } from "../../../shared/domain/clock";
import { NotFoundError } from "../../../shared/domain/errors";
import type { AppLogger } from "../../../shared/domain/logger";
import type { FriendshipRepository } from "../../friendships/application/ports/friendship.repository";
import { allowsMessaging } from "../../friendships/domain/friendship";
import type { UserRepository } from "../../identity/application/ports/user.repository";
import { toPublicProfile, type UserAccount } from "../../identity/domain/user";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import type { UploadPromoter } from "../../media/application/upload-promoter";
import {
  expirationFor,
  isVisible,
  STORY_THUMBNAIL_URL_TTL_SECONDS,
  STORY_VIEW_URL_TTL_SECONDS,
  storyMediaKey,
  storyThumbnailKey,
  UPLOAD_PURPOSE_BY_STORY_TYPE,
  type StoryItem,
} from "../domain/story-item";
import type { StoryPurgeScheduler, StoryRepository, StoryViewStore } from "./ports";

// Même réponse pour un élément inexistant, expiré, ou d'un auteur qui n'est pas (ou plus) ami :
// un utilisateur bloqué ne peut pas deviner que la story existe (ADR-012).
const STORY_NOT_FOUND = "Story introuvable";
const SWEEP_BATCH_SIZE = 500;

export type StoryPurgeOutcome = { kind: "done" } | { kind: "delay"; until: Date };

export class StoryUseCases {
  constructor(
    private readonly stories: StoryRepository,
    private readonly views: StoryViewStore,
    private readonly friendships: FriendshipRepository,
    private readonly users: UserRepository,
    private readonly promoter: UploadPromoter,
    private readonly storage: ObjectStorage,
    private readonly scheduler: StoryPurgeScheduler,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  async publish(
    me: UserAccount,
    input: { uploadId: string; contentType: StoryContentType; thumbnailUploadId?: string },
  ): Promise<OwnStoryItemView> {
    const id = randomUUID();
    const createdAt = this.clock.now();
    // Vignette d'abord : la ligne StoryItem est créée par la promotion du média principal.
    const thumbnailKey = input.thumbnailUploadId ? await this.promoteThumbnail(me, id, input.thumbnailUploadId) : null;
    const item: StoryItem = {
      id,
      authorId: me.id,
      mediaKey: storyMediaKey(id),
      contentType: input.contentType,
      thumbnailKey,
      createdAt,
      // Expiration propre à chaque élément, 24h après SA publication (ADR-013).
      expiresAt: expirationFor(createdAt),
    };

    try {
      await this.promoter.promote({
        userId: me.id,
        uploadId: input.uploadId,
        allowedPurposes: [UPLOAD_PURPOSE_BY_STORY_TYPE[input.contentType]],
        destination: { area: "private", key: item.mediaKey },
        persist: () => this.stories.create(item),
      });
    } catch (error) {
      // Compensation : aucune ligne ne référencerait la vignette, ni purge ne la supprimerait (ADR-026).
      if (thumbnailKey) {
        await this.bestEffort(`suppression compensatoire de ${thumbnailKey}`, () =>
          this.storage.delete({ area: "private", key: thumbnailKey }),
        );
      }
      throw error;
    }
    await this.bestEffort(`programmation de la purge de la story ${id}`, () =>
      this.scheduler.schedule(id, item.expiresAt),
    );
    this.analytics.capture(me.id, "story_published", { content_type: input.contentType });
    return presentOwn(item, 0, await this.thumbnailUrl(item));
  }

  // Stories des amis ACCEPTED (ADR-013), non vues d'abord puis les plus récentes.
  async feed(me: UserAccount): Promise<StoryFeedEntry[]> {
    const friends = await this.friendships.listFriends(me.id);
    const profiles = new Map<string, PublicUserProfile>(friends.map(({ user }) => [user.id, user]));
    const items = await this.stories.listVisibleByAuthors([...profiles.keys()], this.clock.now());
    const viewed = await this.views.viewedAmong(
      items.map((item) => item.id),
      me.id,
    );

    const byAuthor = new Map<string, StoryItem[]>();
    for (const item of items) byAuthor.set(item.authorId, [...(byAuthor.get(item.authorId) ?? []), item]);

    const entries = await Promise.all(
      [...byAuthor].map(async ([authorId, authorItems]): Promise<StoryFeedEntry> => {
        const list = authorItems.map((item) => presentForViewer(item, viewed.has(item.id)));
        // Simple lecture d'une URL pré-signée : aucune vue enregistrée (ADR-026).
        const thumbnailUrl = await this.thumbnailUrl(ringThumbnailItem(authorItems, viewed));
        return {
          author: profiles.get(authorId)!,
          items: list,
          hasUnviewed: list.some((item) => !item.viewed),
          ...(thumbnailUrl && { thumbnailUrl }),
        };
      }),
    );

    return entries.sort(
      (a, b) =>
        Number(b.hasUnviewed) - Number(a.hasUnviewed) ||
        b.items[b.items.length - 1].createdAt.localeCompare(a.items[a.items.length - 1].createdAt),
    );
  }

  async mine(me: UserAccount): Promise<OwnStoryItemView[]> {
    const items = await this.stories.listVisibleByAuthors([me.id], this.clock.now());
    const counts = await this.views.counts(items.map((item) => item.id));
    return Promise.all(items.map(async (item) => presentOwn(item, counts.get(item.id) ?? 0, await this.thumbnailUrl(item))));
  }

  async view(me: UserAccount, storyId: string): Promise<SnapViewUrl> {
    const item = await this.findVisible(storyId);
    if (item.authorId !== me.id) {
      await this.requireFriendAuthor(me, item.authorId);
      await this.views.record(item.id, me.id, item.expiresAt);
      this.analytics.capture(me.id, "story_viewed");
    }
    const now = this.clock.now();
    const url = await this.storage.presignDownload(item.mediaKey, STORY_VIEW_URL_TTL_SECONDS);
    return { url, expiresAt: new Date(now.getTime() + STORY_VIEW_URL_TTL_SECONDS * 1000).toISOString() };
  }

  // Seul l'auteur voit qui a regardé son élément.
  async viewers(me: UserAccount, storyId: string): Promise<PublicUserProfile[]> {
    const item = await this.findVisible(storyId);
    if (item.authorId !== me.id) throw new NotFoundError(STORY_NOT_FOUND);
    const accounts = await Promise.all((await this.views.viewers(item.id)).map((id) => this.users.findActiveById(id)));
    return accounts.filter((user): user is UserAccount => user !== null).map(toPublicProfile);
  }

  // Retrait anticipé par l'auteur : le job de purge trouvera ensuite la ligne absente (cas normal).
  async remove(me: UserAccount, storyId: string): Promise<void> {
    const item = await this.stories.findById(storyId);
    if (!item || item.authorId !== me.id) throw new NotFoundError(STORY_NOT_FOUND);
    await this.deleteItem(item);
  }

  // Exécution d'un job purge-story-<id> : média R2 ET ligne supprimés, contrairement à un Snap
  // privé dont la ligne est conservée (aucun affichage "expiré" pour une story, ADR-013).
  async runPurgeJob(storyId: string): Promise<StoryPurgeOutcome> {
    const item = await this.stories.findById(storyId);
    if (!item) return { kind: "done" };
    if (isVisible(item, this.clock.now())) return { kind: "delay", until: item.expiresAt };
    await this.deleteItem(item);
    return { kind: "done" };
  }

  // Balayage de rattrapage : relance les jobs, ne supprime jamais rien lui-même (ADR-017).
  async sweepOverdue(): Promise<{ overdue: number }> {
    const ids = await this.stories.findExpiredIds(this.clock.now(), SWEEP_BATCH_SIZE);
    for (const id of ids) await this.scheduler.recover(id);
    return { overdue: ids.length };
  }

  private async deleteItem(item: StoryItem): Promise<void> {
    // R2 d'abord : si la base échoue ensuite, le rejeu retente une suppression idempotente.
    await this.storage.delete({ area: "private", key: item.mediaKey });
    if (item.thumbnailKey) await this.storage.delete({ area: "private", key: item.thumbnailKey });
    await this.stories.delete(item.id);
    await this.bestEffort(`nettoyage des vues de ${item.id}`, () => this.views.clear(item.id));
  }

  // La vignette est un confort (ADR-026) : un upload inconnu, d'un autre usage ou trop gros ne
  // bloque pas la publication, l'élément est publié sans vignette.
  private async promoteThumbnail(me: UserAccount, storyId: string, uploadId: string): Promise<string | null> {
    const key = storyThumbnailKey(storyId);
    try {
      await this.promoter.promote({
        userId: me.id,
        uploadId,
        allowedPurposes: ["STORY_THUMBNAIL"],
        destination: { area: "private", key },
        persist: async () => undefined,
      });
      return key;
    } catch (error) {
      this.logger.warn(`Vignette ignorée pour la story ${storyId} : ${(error as Error).message}`);
      return null;
    }
  }

  private async thumbnailUrl(item: StoryItem | undefined): Promise<string | undefined> {
    if (!item?.thumbnailKey) return undefined;
    return this.storage.presignDownload(item.thumbnailKey, STORY_THUMBNAIL_URL_TTL_SECONDS);
  }

  private async findVisible(storyId: string): Promise<StoryItem> {
    const item = await this.stories.findById(storyId);
    if (!item || !isVisible(item, this.clock.now())) throw new NotFoundError(STORY_NOT_FOUND);
    return item;
  }

  // Visibilité = amis ACCEPTED uniquement ; un auteur en cours de suppression est invisible (ADR-019).
  private async requireFriendAuthor(me: UserAccount, authorId: string): Promise<void> {
    const author = await this.users.findActiveById(authorId);
    if (!author || !allowsMessaging(await this.friendships.findByPair(me.id, authorId))) {
      throw new NotFoundError(STORY_NOT_FOUND);
    }
  }

  private async bestEffort(what: string, action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      this.logger.warn(`Échec (non bloquant) : ${what} — ${(error as Error).message}`);
    }
  }
}

function presentForViewer(item: StoryItem, viewed: boolean): StoryItemView {
  return {
    id: item.id,
    contentType: item.contentType,
    createdAt: item.createdAt.toISOString(),
    expiresAt: item.expiresAt.toISOString(),
    viewed,
  };
}

function presentOwn(item: StoryItem, viewCount: number, thumbnailUrl?: string): OwnStoryItemView {
  return {
    id: item.id,
    contentType: item.contentType,
    createdAt: item.createdAt.toISOString(),
    expiresAt: item.expiresAt.toISOString(),
    viewCount,
    ...(thumbnailUrl && { thumbnailUrl }),
  };
}

// Vignette de l'anneau = l'élément que l'utilisateur verra en premier : le premier non vu qui en a
// une, sinon le plus récent qui en a une (ADR-026). `items` va du plus ancien au plus récent.
function ringThumbnailItem(items: StoryItem[], viewed: Set<string>): StoryItem | undefined {
  const withThumbnail = items.filter((item) => item.thumbnailKey !== null);
  return withThumbnail.find((item) => !viewed.has(item.id)) ?? withThumbnail[withThumbnail.length - 1];
}
