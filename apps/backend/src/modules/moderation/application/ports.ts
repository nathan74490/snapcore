import type { ReportStatus, ReportTargetType } from "@snapcore/types";

export const MODERATION_REPOSITORY = Symbol("ModerationRepository");

export type ReportTarget =
  | { type: "USER"; userId: string }
  | { type: "MESSAGE"; messageId: string }
  | { type: "STORY_ITEM"; storyItemId: string };

export interface NewReport {
  reporterId: string;
  target: ReportTarget;
  reason: string;
}

export interface StoredReport {
  id: string;
  reporterId: string;
  targetType: ReportTargetType;
  status: ReportStatus;
}

export type StatusChange = "updated" | "unchanged" | "not-found";

// Données de modération (ADR-021) : suspension de compte, signalements, comptes modérateurs.
export interface ModerationRepository {
  moderatorExists(moderatorId: string): Promise<boolean>;

  /** ACTIVE → SUSPENDED, avec traçabilité. "unchanged" si déjà suspendu ou en cours de suppression. */
  suspendUser(userId: string, suspension: { moderatorId: string; reason: string; at: Date }): Promise<StatusChange>;
  /** SUSPENDED → ACTIVE : les données n'ont jamais été touchées, le compte redevient visible. */
  reactivateUser(userId: string): Promise<StatusChange>;

  createReport(report: NewReport): Promise<StoredReport>;
  /** PENDING → RESOLVED / DISMISSED, attribué au modérateur. "unchanged" si déjà traité. */
  closeReport(
    reportId: string,
    closing: { status: Exclude<ReportStatus, "PENDING">; moderatorId: string; at: Date },
  ): Promise<StatusChange>;
}
