import type { LocationSharingMode } from "@snapcore/types";
import type { FriendshipWithUser } from "../../friendships/application/ports/friendship.repository";
import type { StoryItem } from "../domain/story-item";

export const STORY_REPOSITORY = Symbol("StoryRepository");
export const STORY_VIEW_STORE = Symbol("StoryViewStore");
export const STORY_PURGE_SCHEDULER = Symbol("StoryPurgeScheduler");

export interface StoryRepository {
  create(item: StoryItem): Promise<StoryItem>;
  findById(id: string): Promise<StoryItem | null>;
  /** @returns false si la ligne n'existait déjà plus. */
  delete(id: string): Promise<boolean>;
  /** Éléments encore visibles (expiresAt > now) des auteurs donnés, du plus ancien au plus récent. */
  listVisibleByAuthors(authorIds: string[], now: Date): Promise<StoryItem[]>;
  /** Éléments expirés pas encore purgés (balayage de rattrapage, ADR-017). */
  findExpiredIds(now: Date, limit: number): Promise<string[]>;
}

// Viewers d'un élément dans un Set Redis `story:views:<id>` dont le TTL suit expiresAt :
// la liste n'a pas besoin de survivre au contenu (ADR-013, ADR-003).
export interface StoryViewStore {
  record(storyId: string, viewerId: string, expiresAt: Date): Promise<void>;
  /** Parmi `storyIds`, ceux que `viewerId` a déjà vus. */
  viewedAmong(storyIds: string[], viewerId: string): Promise<Set<string>>;
  viewers(storyId: string): Promise<string[]>;
  counts(storyIds: string[]): Promise<Map<string, number>>;
  clear(storyId: string): Promise<void>;
}

// Règle de visibilité de la Snap Map (ADR-031), fournie par le module location (LocationVisibility).
export interface StoryLocationPolicy {
  /** Mode de partage de l'auteur, lu au moment de la publication. */
  modeOf(userId: string): Promise<LocationSharingMode>;
  /** Parmi `friends`, ceux qui rendent leur position (et leurs stories géolocalisées) visible à `viewerId`. */
  visibleFriends(viewerId: string, friends: FriendshipWithUser[]): Promise<FriendshipWithUser[]>;
}

// Même job de purge que les Snaps, exécuté à expiresAt (ADR-013, ADR-017).
export interface StoryPurgeScheduler {
  schedule(storyId: string, at: Date): Promise<void>;
  recover(storyId: string): Promise<"retried" | "re-added" | "untouched">;
  /**
   * Retire le job devenu inutile (contenu déjà traité par la modération, ADR-021). Sans effet s'il
   * n'existe pas ; laissé en place s'il est en cours d'exécution (le handler ne trouvera rien à faire).
   */
  cancel(targetId: string): Promise<void>;
}
