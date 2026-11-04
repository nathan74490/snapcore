import type { GroupMessageView, SnapViewUrl } from "@snapcore/types";
import { randomUUID } from "node:crypto";
import { noAnalytics, type Analytics } from "../../../shared/domain/analytics";
import type { Clock } from "../../../shared/domain/clock";
import { ForbiddenError, GoneError, NotFoundError } from "../../../shared/domain/errors";
import type { AppLogger } from "../../../shared/domain/logger";
import { hasActiveSubscription, type UserAccount } from "../../identity/domain/user";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import type { UploadPromoter } from "../../media/application/upload-promoter";
import {
  initialPurgeDate,
  isWithinViewGracePeriod,
  parseTextContent,
  UPLOAD_PURPOSE_BY_SNAP_TYPE,
  VIEW_URL_TTL_SECONDS,
  type SnapContentType,
} from "../../messages/domain/message";
import { groupSnapMediaKey, isVisibleTo, type GroupMember, type GroupMessage } from "../domain/group";
import { presentGroupMessage, requireMember } from "./group-presenter";
import type { GroupSnapPurge } from "./group-snap-purge";
import type { ActiveGroupMember, GroupNotifier, GroupRepository } from "./ports";

const SNAP_NOT_FOUND = "Snap introuvable";
const DEFAULT_PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 100;

// Messages de groupe (ADR-032) : réservés aux membres actuels. Les règles d'amitié ne s'appliquent pas
// à l'intérieur du groupe (un blocage entre deux membres ne cache pas leurs messages).
export class GroupMessagingUseCases {
  constructor(
    private readonly groups: GroupRepository,
    private readonly promoter: UploadPromoter,
    private readonly storage: ObjectStorage,
    private readonly purge: GroupSnapPurge,
    private readonly notifier: GroupNotifier,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  // Historique depuis mon arrivée, du plus récent au plus ancien ; `before` = createdAt du plus ancien affiché.
  async listMessages(
    me: UserAccount,
    groupId: string,
    options: { before?: Date; limit?: number },
  ): Promise<GroupMessageView[]> {
    const member = await requireMember(this.groups, groupId, me.id);
    const limit = Math.min(Math.max(options.limit ?? DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
    const [messages, members] = await Promise.all([
      this.groups.listMessages(groupId, { since: member.joinedAt, before: options.before, limit }),
      this.activeMembers(groupId),
    ]);
    const openings = await this.groups.listOpenings(messages.filter((m) => m.contentType !== "TEXT").map((m) => m.id));
    return messages.map((message) => presentGroupMessage(message, member, members, openings));
  }

  async sendText(me: UserAccount, groupId: string, rawContent: string): Promise<GroupMessageView> {
    const content = parseTextContent(rawContent);
    const member = await requireMember(this.groups, groupId, me.id);

    // Texte permanent : jamais de mediaKey, jamais de purge programmée (ADR-009, ADR-017).
    const message = await this.groups.createMessage({
      id: randomUUID(),
      groupId,
      senderId: me.id,
      contentType: "TEXT",
      content,
      mediaKey: null,
      createdAt: this.clock.now(),
      scheduledPurgeAt: null,
    });
    return this.afterSend(me, member, message);
  }

  async sendSnap(
    me: UserAccount,
    groupId: string,
    input: { uploadId: string; contentType: SnapContentType },
  ): Promise<GroupMessageView> {
    const member = await requireMember(this.groups, groupId, me.id);

    const id = randomUUID();
    const mediaKey = groupSnapMediaKey(id);
    const createdAt = this.clock.now();
    // Le message n'est écrit qu'une fois le média copié à sa clé définitive (ADR-016).
    const message = await this.promoter.promote({
      userId: me.id,
      uploadId: input.uploadId,
      allowedPurposes: [UPLOAD_PURPOSE_BY_SNAP_TYPE[input.contentType]],
      destination: { area: "private", key: mediaKey },
      persist: () =>
        this.groups.createMessage({
          id,
          groupId,
          senderId: me.id,
          contentType: input.contentType,
          content: null,
          mediaKey,
          createdAt,
          scheduledPurgeAt: initialPurgeDate(createdAt),
        }),
    });
    await this.purge.schedule(message);
    return this.afterSend(me, member, message);
  }

  // Ouverture de la conversation : tous les chats reçus jusqu'ici passent à « lu » (lastReadAt).
  async markRead(me: UserAccount, groupId: string): Promise<void> {
    await requireMember(this.groups, groupId, me.id);
    const lastUnread = await this.groups.findLastUnreadFromOthers(groupId, me.id);
    await this.groups.markRead(groupId, me.id, this.clock.now());
    if (!lastUnread) return;
    await this.notify(async () =>
      this.notifier.messageSeen(await this.otherMemberIds(groupId, me.id), groupId, lastUnread.id, me.id),
    );
  }

  // « En train d'écrire » (ADR-027) : relayé aux autres membres connectés, rien n'est enregistré.
  async notifyTyping(me: UserAccount, groupId: string): Promise<void> {
    await requireMember(this.groups, groupId, me.id);
    await this.notify(async () => this.notifier.typing(await this.otherMemberIds(groupId, me.id), groupId, me.id));
  }

  // Vue unique par membre (ADR-009, ADR-032) : 1re ouverture atomique, fenêtre de grâce de 5 min, puis
  // refus sauf abonnement SnapCore+ actif (ADR-025).
  async requestSnapView(me: UserAccount, messageId: string): Promise<SnapViewUrl> {
    const message = await this.groups.findMessageById(messageId);
    // Mon propre snap, un chat, un snap d'un groupe dont je ne suis pas membre ou envoyé avant mon
    // arrivée : même réponse qu'un snap inexistant.
    if (!message || message.contentType === "TEXT" || message.senderId === me.id) throw new NotFoundError(SNAP_NOT_FOUND);
    const member = await this.groups.findMember(message.groupId, me.id);
    if (!member || !isVisibleTo(message, member)) throw new NotFoundError(SNAP_NOT_FOUND);
    if (!message.mediaKey) throw new GoneError("Ce Snap a expiré");

    const now = this.clock.now();
    if (await this.groups.recordOpening(message.id, me.id, now)) {
      await this.onFirstOpening(me, message);
    } else {
      const openedAt = (await this.groups.findOpening(message.id, me.id))?.openedAt ?? now;
      const replay = !isWithinViewGracePeriod(openedAt, now);
      if (replay && !hasActiveSubscription(me, now)) throw new ForbiddenError("Ce Snap a déjà été vu");
    }

    const url = await this.storage.presignDownload(message.mediaKey, VIEW_URL_TTL_SECONDS);
    return { url, expiresAt: new Date(now.getTime() + VIEW_URL_TTL_SECONDS * 1000).toISOString() };
  }

  private async onFirstOpening(me: UserAccount, message: GroupMessage): Promise<void> {
    const members = await this.activeMembers(message.groupId);
    await this.purge.advanceIfAllOpened(message, members);
    const others = members.filter((m) => m.userId !== me.id).map((m) => m.userId);
    await this.notify(() => this.notifier.messageSeen(others, message.groupId, message.id, me.id));
  }

  private async afterSend(me: UserAccount, member: GroupMember, message: GroupMessage): Promise<GroupMessageView> {
    const [members, group] = await Promise.all([this.activeMembers(message.groupId), this.groups.findById(message.groupId)]);
    const recipients = members.filter((m) => m.userId !== me.id).map((m) => m.userId);
    await this.notify(() =>
      this.notifier.messageReceived(recipients, {
        groupId: message.groupId,
        messageId: message.id,
        senderId: me.id,
        senderName: me.firstName ?? me.username,
        groupName: group?.name ?? null,
        contentType: message.contentType,
      }),
    );
    this.analytics.capture(me.id, "group_message_sent", { content_type: message.contentType });
    return presentGroupMessage(message, member, members, []);
  }

  private async activeMembers(groupId: string): Promise<ActiveGroupMember[]> {
    return (await this.groups.listActiveMembers([groupId])).get(groupId) ?? [];
  }

  private async otherMemberIds(groupId: string, myId: string): Promise<string[]> {
    return (await this.activeMembers(groupId)).filter((m) => m.userId !== myId).map((m) => m.userId);
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
