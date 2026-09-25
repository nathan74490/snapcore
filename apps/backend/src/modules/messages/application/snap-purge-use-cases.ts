import type { Clock } from "../../../shared/domain/clock";
import type { AppLogger } from "../../../shared/domain/logger";
import type { UserRepository } from "../../identity/application/ports/user.repository";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import { decidePurge } from "../domain/snap-purge";
import type { MessageNotifier } from "./ports/message-notifier.port";
import type { MessageRepository } from "./ports/message.repository";
import type { SnapPurgeScheduler } from "./ports/snap-purge.scheduler";

export type PurgeJobOutcome =
  | { kind: "done"; detail: "missing" | "nothing-to-purge" | "purged" }
  // Le job doit reprendre à `until` (ni supprimé ni réajouté : l'adaptateur le re-diffère).
  | { kind: "delay"; until: Date; detail: "not-due" | "retained" };

const SWEEP_BATCH_SIZE = 500;

export class SnapPurgeUseCases {
  constructor(
    private readonly messages: MessageRepository,
    private readonly users: UserRepository,
    private readonly storage: ObjectStorage,
    private readonly scheduler: SnapPurgeScheduler,
    private readonly notifier: MessageNotifier,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
  ) {}

  // Exécution d'un job purge-message-<id> (ADR-017). Toute erreur levée fait échouer le job,
  // qui sera réessayé par BullMQ puis, en dernier recours, relancé par le balayage.
  async runPurgeJob(messageId: string): Promise<PurgeJobOutcome> {
    const message = await this.messages.findById(messageId);
    // Ligne supprimée entre-temps (suppression de compte, ADR-019) : cas normal, rien à faire.
    if (!message) return { kind: "done", detail: "missing" };

    const now = this.clock.now();
    // Abonnement du DESTINATAIRE vérifié au moment même de l'exécution.
    const recipient = await this.users.findActiveById(message.recipientId);
    const decision = decidePurge(message, recipient, now);

    switch (decision.kind) {
      case "nothing":
        return { kind: "done", detail: "nothing-to-purge" };

      case "not-due":
        return { kind: "delay", until: decision.at, detail: "not-due" };

      case "retain":
        // Mis à jour à CHAQUE reprogrammation : sinon le balayage retrouverait ce Snap
        // indéfiniment comme "en retard" (ADR-017).
        await this.messages.setScheduledPurgeAt(message.id, decision.until);
        if (await this.messages.markRetainedNotified(message.id, now)) {
          await this.bestEffort("notification Snap conservé", () =>
            this.notifier.snapRetained(message.senderId, message.id),
          );
        }
        return { kind: "delay", until: decision.until, detail: "retained" };

      case "purge":
        // R2 d'abord : si la base échoue ensuite, le rejeu retente une suppression idempotente.
        // L'ordre inverse perdrait la clé et laisserait l'objet orphelin (ADR-016).
        await this.storage.delete({ area: "private", key: message.mediaKey! });
        await this.messages.clearMedia(message.id);
        await this.bestEffort("notification Snap expiré", () =>
          this.notifier.snapExpired([message.senderId, message.recipientId], message.id),
        );
        return { kind: "done", detail: "purged" };
    }
  }

  // Balayage de rattrapage (ADR-017) : ne supprime jamais rien lui-même, il relance seulement
  // le job normal, qui revérifie l'abonnement avant d'agir.
  async sweepOverdue(): Promise<{ overdue: number; retried: number; reAdded: number }> {
    const ids = await this.messages.findOverdueSnapIds(this.clock.now(), SWEEP_BATCH_SIZE);
    const result = { overdue: ids.length, retried: 0, reAdded: 0 };
    for (const id of ids) {
      const outcome = await this.scheduler.recover(id);
      if (outcome === "retried") result.retried++;
      if (outcome === "re-added") result.reAdded++;
    }
    if (result.retried || result.reAdded) {
      this.logger.warn(`Balayage de purge : ${result.retried} job(s) relancé(s), ${result.reAdded} réajouté(s)`);
    }
    return result;
  }

  private async bestEffort(what: string, action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      this.logger.warn(`Échec (non bloquant) : ${what} — ${(error as Error).message}`);
    }
  }
}
