import type { Clock } from "../../../shared/domain/clock";
import type { AppLogger } from "../../../shared/domain/logger";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import type { SnapPurgeScheduler } from "../../messages/application/ports/snap-purge.scheduler";
import { decideGroupPurge, purgeDateWhenAllOpened, type GroupMember, type GroupMessage } from "../domain/group";
import type { GroupRepository } from "./ports";

export type GroupPurgeJobOutcome =
  | { kind: "done"; detail: "missing" | "nothing-to-purge" | "purged" }
  // Le job doit reprendre à `until` (ni supprimé ni réajouté : l'adaptateur le re-diffère).
  | { kind: "delay"; until: Date; detail: "not-due" };

const SWEEP_BATCH_SIZE = 500;

// Purge des snaps de groupe (ADR-032, ADR-017) : job `purge-group-message-<id>` dans la même file et le
// même worker que les snaps à deux et les stories, et même balayage de rattrapage.
export class GroupSnapPurge {
  constructor(
    private readonly groups: GroupRepository,
    private readonly storage: ObjectStorage,
    private readonly scheduler: SnapPurgeScheduler,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
  ) {}

  schedule(message: GroupMessage): Promise<void> {
    return this.bestEffort(`programmation de la purge du snap de groupe ${message.id}`, () =>
      this.scheduler.schedule(message.id, message.scheduledPurgeAt!),
    );
  }

  /**
   * Rapproche la purge à 24 h après la dernière ouverture si tous les membres présents à l'envoi l'ont
   * ouvert (appelé à chaque 1re ouverture et au départ d'un membre). `members` = membres actuels actifs.
   */
  async advanceIfAllOpened(message: GroupMessage, members: GroupMember[]): Promise<void> {
    if (message.contentType === "TEXT" || message.mediaKey === null) return;
    const openings = await this.groups.listOpenings([message.id]);
    const purgeAt = purgeDateWhenAllOpened(message, members, openings, this.clock.now());
    if (!purgeAt || purgeAt.getTime() === message.scheduledPurgeAt?.getTime()) return;
    // scheduledPurgeAt est la source de vérité du balayage : mis à jour même si le job échoue (ADR-017).
    await this.groups.setScheduledPurgeAt(message.id, purgeAt);
    await this.bestEffort(`reprogrammation de la purge du snap de groupe ${message.id}`, () =>
      this.scheduler.reschedule(message.id, purgeAt),
    );
  }

  // Exécution d'un job purge-group-message-<id>. Toute erreur levée fait échouer le job, réessayé par
  // BullMQ puis, en dernier recours, relancé par le balayage.
  async runPurgeJob(messageId: string): Promise<GroupPurgeJobOutcome> {
    const message = await this.groups.findMessageById(messageId);
    // Ligne supprimée entre-temps (groupe supprimé, compte supprimé) : cas normal, rien à faire.
    if (!message) return { kind: "done", detail: "missing" };

    const decision = decideGroupPurge(message, this.clock.now());
    switch (decision.kind) {
      case "nothing":
        return { kind: "done", detail: "nothing-to-purge" };
      case "not-due":
        return { kind: "delay", until: decision.at, detail: "not-due" };
      case "purge":
        // R2 d'abord : si la base échoue ensuite, le rejeu retente une suppression idempotente (ADR-016).
        await this.storage.delete({ area: "private", key: message.mediaKey! });
        await this.groups.clearMedia(message.id);
        return { kind: "done", detail: "purged" };
    }
  }

  // Balayage de rattrapage : ne supprime jamais rien lui-même, il relance seulement le job normal.
  async sweepOverdue(): Promise<{ overdue: number; retried: number; reAdded: number }> {
    const ids = await this.groups.findOverdueSnapIds(this.clock.now(), SWEEP_BATCH_SIZE);
    const result = { overdue: ids.length, retried: 0, reAdded: 0 };
    for (const id of ids) {
      const outcome = await this.scheduler.recover(id);
      if (outcome === "retried") result.retried++;
      if (outcome === "re-added") result.reAdded++;
    }
    if (result.retried || result.reAdded) {
      this.logger.warn(
        `Balayage de purge (groupes) : ${result.retried} job(s) relancé(s), ${result.reAdded} réajouté(s)`,
      );
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
