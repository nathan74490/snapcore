import { Injectable } from "@nestjs/common";
import type { ReportStatus } from "@snapcore/types";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { ModerationRepository, NewReport, StatusChange, StoredReport } from "../application/ports";

@Injectable()
export class PrismaModerationRepository implements ModerationRepository {
  constructor(private readonly prisma: PrismaService) {}

  async moderatorExists(moderatorId: string): Promise<boolean> {
    return (await this.prisma.moderator.count({ where: { id: moderatorId } })) === 1;
  }

  async suspendUser(userId: string, s: { moderatorId: string; reason: string; at: Date }): Promise<StatusChange> {
    // Conditionnelle : un compte en cours de suppression (ADR-019) n'a pas à être suspendu.
    const { count } = await this.prisma.user.updateMany({
      where: { id: userId, moderationStatus: "ACTIVE", deletionRequestedAt: null },
      data: { moderationStatus: "SUSPENDED", suspendedAt: s.at, suspendedById: s.moderatorId, suspensionReason: s.reason },
    });
    return count === 1 ? "updated" : this.userChange(userId);
  }

  async reactivateUser(userId: string): Promise<StatusChange> {
    const { count } = await this.prisma.user.updateMany({
      where: { id: userId, moderationStatus: "SUSPENDED" },
      data: { moderationStatus: "ACTIVE", suspendedAt: null, suspendedById: null, suspensionReason: null },
    });
    return count === 1 ? "updated" : this.userChange(userId);
  }

  async createReport(report: NewReport): Promise<StoredReport> {
    const { target } = report;
    const row = await this.prisma.report.create({
      data: {
        reporterId: report.reporterId,
        reason: report.reason,
        targetType: target.type,
        // Une seule clé renseignée, selon le type (contrainte Report_single_target_check).
        targetUserId: target.type === "USER" ? target.userId : null,
        targetMessageId: target.type === "MESSAGE" ? target.messageId : null,
        targetStoryItemId: target.type === "STORY_ITEM" ? target.storyItemId : null,
      },
    });
    return { id: row.id, reporterId: row.reporterId, targetType: row.targetType, status: row.status };
  }

  async closeReport(
    reportId: string,
    c: { status: Exclude<ReportStatus, "PENDING">; moderatorId: string; at: Date },
  ): Promise<StatusChange> {
    const { count } = await this.prisma.report.updateMany({
      where: { id: reportId, status: "PENDING" },
      data: { status: c.status, resolvedAt: c.at, resolvedById: c.moderatorId },
    });
    if (count === 1) return "updated";
    return (await this.prisma.report.count({ where: { id: reportId } })) ? "unchanged" : "not-found";
  }

  private async userChange(userId: string): Promise<StatusChange> {
    return (await this.prisma.user.count({ where: { id: userId } })) ? "unchanged" : "not-found";
  }
}
