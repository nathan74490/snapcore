import type { ReportStatus } from "@snapcore/types";
import type { Clock } from "../../../shared/domain/clock";
import { ConflictError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import type { AppLogger } from "../../../shared/domain/logger";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import type { MessageRepository } from "../../messages/application/ports/message.repository";
import type { SnapPurgeScheduler } from "../../messages/application/ports/snap-purge.scheduler";
import type { RealtimeChannel } from "../../notifications/application/ports";
import type { UserEventPublisher } from "../../notifications/application/user-event-publisher";
import type { StoryPurgeScheduler, StoryRepository, StoryViewStore } from "../../stories/application/ports";
import type { ModerationRepository, StatusChange } from "./ports";

const MAX_REASON_LENGTH = 500;

export function parseReason(raw: string): string {
  const reason = raw.trim();
  if (!reason) throw new ValidationError("Une raison est requise");
  if (reason.length > MAX_REASON_LENGTH) throw new ValidationError(`La raison dépasse ${MAX_REASON_LENGTH} caractères`);
  return reason;
}

// Actions des modérateurs (ADR-021), appelées par le backoffice via l'API admin. Centralisées ici
// pour que les effets de bord (R2, jobs BullMQ, temps réel) ne soient écrits qu'une seule fois.
export class ModerationUseCases {
  constructor(
    private readonly moderation: ModerationRepository,
    private readonly messages: MessageRepository,
    private readonly stories: StoryRepository,
    private readonly storyViews: StoryViewStore,
    private readonly storage: ObjectStorage,
    private readonly snapPurge: SnapPurgeScheduler,
    private readonly storyPurge: StoryPurgeScheduler,
    private readonly realtime: RealtimeChannel,
    private readonly events: UserEventPublisher,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
  ) {}

  // Suspension réversible : le compte devient inexistant pour les autres et ne peut plus se
  // connecter (filtre centralisé ACTIVE_USER_WHERE), sans qu'aucune donnée ne soit touchée.
  async suspendUser(userId: string, moderatorId: string, rawReason: string): Promise<void> {
    await this.requireModerator(moderatorId);
    const reason = parseReason(rawReason);
    expectUpdated(await this.moderation.suspendUser(userId, { moderatorId, reason, at: this.clock.now() }), "Compte introuvable", "Ce compte est déjà suspendu ou en cours de suppression");
    // Coupe immédiatement ses connexions temps réel ; l'API les refuse déjà à la requête suivante.
    await this.bestEffort(`déconnexion temps réel de ${userId}`, () => this.realtime.disconnectUser(userId));
  }

  async reactivateUser(userId: string, moderatorId: string): Promise<void> {
    await this.requireModerator(moderatorId);
    expectUpdated(await this.moderation.reactivateUser(userId), "Compte introuvable", "Ce compte n'est pas suspendu");
  }

  // Rédaction d'un message : ligne conservée pour la conversation de l'autre participant (ADR-021).
  async redactMessage(messageId: string, moderatorId: string, rawReason: string): Promise<void> {
    await this.requireModerator(moderatorId);
    const reason = parseReason(rawReason);
    const message = await this.messages.findById(messageId);
    if (!message) throw new NotFoundError("Message introuvable");
    if (message.moderatedAt) throw new ConflictError("Ce message a déjà été retiré");

    // R2 d'abord, comme la purge : si la base échoue ensuite, un nouvel essai retente une
    // suppression idempotente. L'ordre inverse perdrait la clé et laisserait l'objet orphelin.
    if (message.mediaKey) await this.storage.delete({ area: "private", key: message.mediaKey });
    if (!(await this.messages.redact(message.id, { moderatorId, reason, at: this.clock.now() }))) {
      throw new ConflictError("Ce message a déjà été retiré");
    }
    // Le job de purge d'un Snap n'a plus rien à faire (ADR-021).
    if (message.contentType !== "TEXT") await this.bestEffort(`retrait du job de purge ${message.id}`, () => this.snapPurge.cancel(message.id));
    for (const participant of [message.senderId, message.recipientId]) {
      await this.events.publish(participant, { type: "message.moderated", messageId: message.id });
    }
  }

  // Un élément de story est supprimé (pas d'affichage "expiré" pour une story, ADR-013/ADR-021).
  async deleteStoryItem(storyItemId: string, moderatorId: string, rawReason: string): Promise<void> {
    await this.requireModerator(moderatorId);
    const reason = parseReason(rawReason);
    const item = await this.stories.findById(storyItemId);
    if (!item) throw new NotFoundError("Élément de story introuvable");

    await this.storage.delete({ area: "private", key: item.mediaKey });
    if (item.thumbnailKey) await this.storage.delete({ area: "private", key: item.thumbnailKey });
    await this.stories.delete(item.id);
    await this.bestEffort(`nettoyage des vues de ${item.id}`, () => this.storyViews.clear(item.id));
    await this.bestEffort(`retrait du job de purge ${item.id}`, () => this.storyPurge.cancel(item.id));
    // StoryItem ne porte pas de champ d'audit (ADR-021) : trace dans les logs du backend.
    this.logger.warn(`Story ${item.id} de ${item.authorId} supprimée par le modérateur ${moderatorId} : ${reason}`);
  }

  async closeReport(reportId: string, status: Exclude<ReportStatus, "PENDING">, moderatorId: string): Promise<void> {
    await this.requireModerator(moderatorId);
    expectUpdated(
      await this.moderation.closeReport(reportId, { status, moderatorId, at: this.clock.now() }),
      "Signalement introuvable",
      "Ce signalement a déjà été traité",
    );
  }

  // L'appelant (serveur du backoffice) est de confiance, mais un identifiant de modérateur
  // inexistant est une erreur de programmation à refuser plutôt qu'à enregistrer.
  private async requireModerator(moderatorId: string): Promise<void> {
    if (!(await this.moderation.moderatorExists(moderatorId))) throw new ValidationError("Modérateur inconnu");
  }

  private async bestEffort(what: string, action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      this.logger.warn(`Échec (non bloquant) : ${what} — ${(error as Error).message}`);
    }
  }
}

function expectUpdated(change: StatusChange, notFound: string, unchanged: string): void {
  if (change === "not-found") throw new NotFoundError(notFound);
  if (change === "unchanged") throw new ConflictError(unchanged);
}
