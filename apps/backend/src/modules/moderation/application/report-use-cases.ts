import { noAnalytics, type Analytics } from "../../../shared/domain/analytics";
import type { Clock } from "../../../shared/domain/clock";
import { NotFoundError, ValidationError } from "../../../shared/domain/errors";
import type { FriendshipRepository } from "../../friendships/application/ports/friendship.repository";
import { allowsMessaging } from "../../friendships/domain/friendship";
import type { UserRepository } from "../../identity/application/ports/user.repository";
import type { UserAccount } from "../../identity/domain/user";
import type { MessageRepository } from "../../messages/application/ports/message.repository";
import type { StoryRepository } from "../../stories/application/ports";
import { isVisible } from "../../stories/domain/story-item";
import { parseReason } from "./moderation-use-cases";
import type { ModerationRepository, ReportTarget, StoredReport } from "./ports";

// Même réponse pour une cible inexistante ou inaccessible : on ne révèle rien de ce que
// l'utilisateur ne peut pas déjà voir (ADR-012).
const TARGET_NOT_FOUND = "Contenu introuvable";

const ANALYTICS_TARGET: Record<ReportTarget["type"], string> = { USER: "user", MESSAGE: "message", STORY_ITEM: "story" };

// Signalement depuis l'app (ADR-021) : un utilisateur ne peut signaler que ce qu'il voit.
export class ReportUseCases {
  constructor(
    private readonly moderation: ModerationRepository,
    private readonly users: UserRepository,
    private readonly messages: MessageRepository,
    private readonly stories: StoryRepository,
    private readonly friendships: FriendshipRepository,
    private readonly clock: Clock,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  async report(me: UserAccount, target: ReportTarget, rawReason: string): Promise<StoredReport> {
    const reason = parseReason(rawReason);
    await this.assertReportable(me, target);
    const report = await this.moderation.createReport({ reporterId: me.id, target, reason });
    // Analytics : le type de cible seulement. Le motif est un texte libre : jamais envoyé.
    this.analytics.capture(me.id, "content_reported", { target: ANALYTICS_TARGET[target.type] });
    return report;
  }

  private async assertReportable(me: UserAccount, target: ReportTarget): Promise<void> {
    switch (target.type) {
      case "USER": {
        if (target.userId === me.id) throw new ValidationError("Impossible de se signaler soi-même");
        if (!(await this.users.findActiveById(target.userId))) throw new NotFoundError(TARGET_NOT_FOUND);
        return;
      }
      case "MESSAGE": {
        // Seuls les deux participants d'une conversation voient ses messages.
        const message = await this.messages.findById(target.messageId);
        if (!message || (message.senderId !== me.id && message.recipientId !== me.id)) throw new NotFoundError(TARGET_NOT_FOUND);
        if (message.senderId === me.id) throw new ValidationError("Impossible de signaler son propre message");
        return;
      }
      case "STORY_ITEM": {
        // Même règle de visibilité que la lecture d'une story : amis ACCEPTED, non expirée (ADR-013).
        const item = await this.stories.findById(target.storyItemId);
        if (!item || !isVisible(item, this.clock.now()) || item.authorId === me.id) throw new NotFoundError(TARGET_NOT_FOUND);
        if (!allowsMessaging(await this.friendships.findByPair(me.id, item.authorId))) throw new NotFoundError(TARGET_NOT_FOUND);
        return;
      }
    }
  }
}
