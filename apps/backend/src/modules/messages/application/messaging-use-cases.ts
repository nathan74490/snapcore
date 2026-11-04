import type { ConversationSummary, MessageView, SnapViewUrl } from "@snapcore/types";
import { noAnalytics, type Analytics } from "../../../shared/domain/analytics";
import { randomUUID } from "node:crypto";
import type { Clock } from "../../../shared/domain/clock";
import { ForbiddenError, GoneError, NotFoundError } from "../../../shared/domain/errors";
import type { AppLogger } from "../../../shared/domain/logger";
import type { FriendshipRepository } from "../../friendships/application/ports/friendship.repository";
import { allowsMessaging } from "../../friendships/domain/friendship";
import type { UserRepository } from "../../identity/application/ports/user.repository";
import { hasActiveSubscription, type UserAccount } from "../../identity/domain/user";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import type { UploadPromoter } from "../../media/application/upload-promoter";
import {
  initialPurgeDate,
  isSnap,
  isWithinViewGracePeriod,
  parseTextContent,
  purgeDateAfterOpening,
  snapMediaKey,
  UPLOAD_PURPOSE_BY_SNAP_TYPE,
  VIEW_URL_TTL_SECONDS,
  type Message,
  type SnapContentType,
} from "../domain/message";
import type { MessageNotifier } from "./ports/message-notifier.port";
import type { MessageRepository } from "./ports/message.repository";
import type { SnapPurgeScheduler } from "./ports/snap-purge.scheduler";

// Même réponse que la paire soit PENDING, DECLINED, BLOCKED ou sans ligne (retrait d'ami) :
// un utilisateur bloqué ne peut pas distinguer un blocage d'un simple retrait (ADR-012).
const NOT_FRIENDS = "Vous devez être amis pour échanger des messages";
const USER_NOT_FOUND = "Utilisateur introuvable";
const SNAP_NOT_FOUND = "Snap introuvable";

const DEFAULT_PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 100;

export class MessagingUseCases {
  constructor(
    private readonly messages: MessageRepository,
    private readonly friendships: FriendshipRepository,
    private readonly users: UserRepository,
    private readonly promoter: UploadPromoter,
    private readonly storage: ObjectStorage,
    private readonly purgeScheduler: SnapPurgeScheduler,
    private readonly notifier: MessageNotifier,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  async sendText(me: UserAccount, friendId: string, rawContent: string): Promise<MessageView> {
    const content = parseTextContent(rawContent);
    await this.requireFriend(me, friendId);

    // Texte permanent : jamais de mediaKey, jamais de purge programmée (ADR-009, ADR-017).
    const message = await this.messages.create({
      id: randomUUID(),
      senderId: me.id,
      recipientId: friendId,
      contentType: "TEXT",
      content,
      mediaKey: null,
      createdAt: this.clock.now(),
      scheduledPurgeAt: null,
    });
    await this.notify(() => this.notifier.messageReceived(friendId, message.id));
    this.analytics.capture(me.id, "chat_sent");
    return presentMessage(message);
  }

  async sendSnap(
    me: UserAccount,
    friendId: string,
    input: { uploadId: string; contentType: SnapContentType },
  ): Promise<MessageView> {
    await this.requireFriend(me, friendId);

    const id = randomUUID();
    const mediaKey = snapMediaKey(id);
    const createdAt = this.clock.now();
    const scheduledPurgeAt = initialPurgeDate(createdAt);

    // La ligne Message n'est écrite qu'une fois le média copié à sa clé définitive (ADR-016).
    const message = await this.promoter.promote({
      userId: me.id,
      uploadId: input.uploadId,
      allowedPurposes: [UPLOAD_PURPOSE_BY_SNAP_TYPE[input.contentType]],
      destination: { area: "private", key: mediaKey },
      persist: () =>
        this.messages.create({
          id,
          senderId: me.id,
          recipientId: friendId,
          contentType: input.contentType,
          content: null,
          mediaKey,
          createdAt,
          scheduledPurgeAt,
        }),
    });

    await this.bestEffort(`programmation de la purge du Snap ${id}`, () =>
      this.purgeScheduler.schedule(id, scheduledPurgeAt),
    );
    await this.notify(() => this.notifier.messageReceived(friendId, id));
    this.analytics.capture(me.id, "snap_sent", { content_type: input.contentType });
    return presentMessage(message);
  }

  // Liste de conversations = amis ACCEPTED uniquement, même règle que le droit d'écrire (ADR-012).
  async listConversations(me: UserAccount): Promise<ConversationSummary[]> {
    const friends = await this.friendships.listFriends(me.id);
    const friendIds = friends.map((f) => f.user.id);
    const [lastMessages, unopenedSnaps] = await Promise.all([
      this.messages.findLastMessagesWith(me.id, friendIds),
      // Un snap non ouvert reste « Nouveau snap » même si un chat plus récent le suit (BUG-014 d'ADR-023).
      this.messages.findOldestUnopenedSnapsFrom(me.id, friendIds),
    ]);

    return friends
      .map(({ user }) => {
        const last = lastMessages.get(user.id);
        const unopened = unopenedSnaps.get(user.id);
        return {
          summary: {
            friend: user,
            ...(last && { lastMessage: presentMessage(last) }),
            ...(unopened && { unopenedSnap: presentMessage(unopened) }),
          },
          sortKey: last?.createdAt.getTime() ?? 0,
        };
      })
      .sort((a, b) => b.sortKey - a.sortKey || a.summary.friend.username.localeCompare(b.summary.friend.username))
      .map(({ summary }) => summary);
  }

  // L'historique n'est jamais supprimé par un changement de statut de la relation, mais il
  // n'est consultable que tant que la paire est ACCEPTED (ADR-012).
  async listMessages(
    me: UserAccount,
    friendId: string,
    options: { before?: Date; limit?: number },
  ): Promise<MessageView[]> {
    await this.requireFriend(me, friendId);
    const limit = Math.min(Math.max(options.limit ?? DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
    const messages = await this.messages.listBetween(me.id, friendId, { before: options.before, limit });
    return messages.map(presentMessage);
  }

  // Flèche bleue pleine → contour : les Chats reçus de cet ami passent à "lu" (ADR-009).
  async markConversationRead(me: UserAccount, friendId: string): Promise<void> {
    await this.requireFriend(me, friendId);
    const count = await this.messages.markTextsRead(me.id, friendId, this.clock.now());
    if (count > 0) await this.notify(() => this.notifier.textsRead(friendId, me.id));
  }

  // Indicateur « en train d'écrire » (ADR-027) : relayé à l'ami s'il est connecté, rien n'est
  // enregistré. Mêmes règles d'accès qu'un envoi : amis ACCEPTED uniquement.
  async notifyTyping(me: UserAccount, friendId: string): Promise<void> {
    await this.requireFriend(me, friendId);
    await this.notify(() => this.notifier.typing(friendId, me.id));
  }

  // Règle "vue unique" d'ADR-009 : réservé au destinataire, FRIENDSHIP ACCEPTED requise,
  // 1re ouverture atomique, fenêtre de grâce de 5 min, puis refus sauf abonnement SnapCore+ actif.
  async requestSnapView(me: UserAccount, messageId: string): Promise<SnapViewUrl> {
    const message = await this.messages.findById(messageId);
    // L'expéditeur n'a jamais accès à la lecture de son propre Snap : même réponse qu'un Snap inexistant.
    if (!message || message.recipientId !== me.id || !isSnap(message)) throw new NotFoundError(SNAP_NOT_FOUND);
    await this.requireFriend(me, message.senderId);
    if (!message.mediaKey) throw new GoneError("Ce Snap a expiré");

    const now = this.clock.now();
    if (await this.messages.markOpenedIfUnopened(message.id, now)) {
      await this.onFirstOpening(message, now);
      this.analytics.capture(me.id, "snap_opened");
    } else {
      const openedAt = message.openedAt ?? (await this.messages.findById(message.id))?.openedAt ?? now;
      const replay = !isWithinViewGracePeriod(openedAt, now);
      if (replay && !hasActiveSubscription(me, now)) throw new ForbiddenError("Ce Snap a déjà été vu");
      // Revoir un Snap déjà vu : l'avantage principal de SnapCore+ (ADR-009, ADR-008).
      if (replay) this.analytics.capture(me.id, "snap_replayed");
    }

    const url = await this.storage.presignDownload(message.mediaKey, VIEW_URL_TTL_SECONDS);
    return { url, expiresAt: new Date(now.getTime() + VIEW_URL_TTL_SECONDS * 1000).toISOString() };
  }

  private async onFirstOpening(message: Message, openedAt: Date): Promise<void> {
    const purgeAt = purgeDateAfterOpening(message, openedAt);
    if (purgeAt.getTime() !== message.scheduledPurgeAt?.getTime()) {
      // scheduledPurgeAt est la source de vérité du balayage de rattrapage : mis à jour à chaque
      // reprogrammation, même si la reprogrammation du job lui-même échoue (ADR-017).
      await this.messages.setScheduledPurgeAt(message.id, purgeAt);
      await this.bestEffort(`reprogrammation de la purge du Snap ${message.id}`, () =>
        this.purgeScheduler.reschedule(message.id, purgeAt),
      );
    }
    await this.notify(() => this.notifier.snapOpened(message.senderId, message.id));
  }

  private async requireFriend(me: UserAccount, friendId: string): Promise<UserAccount> {
    // Un compte en cours de suppression est traité comme inexistant (ADR-019).
    const friend = friendId === me.id ? null : await this.users.findActiveById(friendId);
    if (!friend) throw new NotFoundError(USER_NOT_FOUND);
    if (!allowsMessaging(await this.friendships.findByPair(me.id, friendId))) throw new ForbiddenError(NOT_FRIENDS);
    return friend;
  }

  private notify(action: () => Promise<void>): Promise<void> {
    return this.bestEffort("notification", action);
  }

  private async bestEffort(what: string, action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      this.logger.warn(`Échec (non bloquant) : ${what} — ${(error as Error).message}`);
    }
  }
}

export function presentMessage(message: Message): MessageView {
  return {
    id: message.id,
    senderId: message.senderId,
    recipientId: message.recipientId,
    contentType: message.contentType,
    ...(message.content !== null ? { content: message.content } : {}),
    createdAt: message.createdAt.toISOString(),
    ...(message.openedAt ? { openedAt: message.openedAt.toISOString() } : {}),
    // Gris = Snap dont le média a été purgé ; un Chat texte n'est jamais gris (ADR-009).
    mediaPurged: isSnap(message) && message.mediaKey === null,
    // Rédigé par la modération (ADR-021) : le client affiche "retiré" plutôt qu'une bulle vide.
    moderated: message.moderatedAt !== null,
  };
}
