import type { ReportStatus } from "@snapcore/types";
import { randomUUID } from "node:crypto";
import type { ModerationRepository, NewReport, StatusChange, StoredReport } from "../modules/moderation/application/ports";
import type { InMemoryUserRepository } from "./in-memory-user.repository";

export class InMemoryModerationRepository implements ModerationRepository {
  readonly moderators = new Set<string>(["mod-1"]);
  readonly reports = new Map<string, NewReport & StoredReport & { resolvedById?: string }>();
  readonly suspensions = new Map<string, { moderatorId: string; reason: string }>();

  constructor(private readonly users: InMemoryUserRepository) {}

  async moderatorExists(id: string) {
    return this.moderators.has(id);
  }

  async suspendUser(userId: string, s: { moderatorId: string; reason: string }): Promise<StatusChange> {
    const user = this.users.rows.get(userId);
    if (!user) return "not-found";
    if (user.moderationStatus !== "ACTIVE" || user.deletionRequestedAt) return "unchanged";
    user.moderationStatus = "SUSPENDED";
    this.suspensions.set(userId, s);
    return "updated";
  }

  async reactivateUser(userId: string): Promise<StatusChange> {
    const user = this.users.rows.get(userId);
    if (!user) return "not-found";
    if (user.moderationStatus !== "SUSPENDED") return "unchanged";
    user.moderationStatus = "ACTIVE";
    this.suspensions.delete(userId);
    return "updated";
  }

  async createReport(report: NewReport) {
    const stored = { ...report, id: randomUUID(), targetType: report.target.type, status: "PENDING" as ReportStatus };
    this.reports.set(stored.id, stored);
    return { id: stored.id, reporterId: stored.reporterId, targetType: stored.targetType, status: stored.status };
  }

  async closeReport(reportId: string, c: { status: Exclude<ReportStatus, "PENDING">; moderatorId: string }): Promise<StatusChange> {
    const report = this.reports.get(reportId);
    if (!report) return "not-found";
    if (report.status !== "PENDING") return "unchanged";
    Object.assign(report, { status: c.status, resolvedById: c.moderatorId });
    return "updated";
  }
}
