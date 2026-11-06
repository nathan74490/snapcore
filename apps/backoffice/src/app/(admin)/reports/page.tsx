import type { ReportStatus } from "@snapcore/types";
import Link from "next/link";
import { Pagination } from "@/components/pagination";
import { ReportTarget } from "@/components/report-target";
import { Tabs } from "@/components/tabs";
import { UserLink } from "@/components/user-link";
import { REPORT_STATUS_LABEL, TARGET_TYPE_LABEL, formatDateTime, parsePage, truncate } from "@/lib/format";
import { listReports, reportCounts, type ReportTypeFilter } from "@/lib/queries";

export const dynamic = "force-dynamic";

const STATUSES: ReportStatus[] = ["PENDING", "RESOLVED", "DISMISSED"];
const TYPES = ["USER", "MESSAGE", "STORY_ITEM"] as const;

export default async function ReportsPage({ searchParams }: { searchParams: Promise<{ status?: string; type?: string; page?: string }> }) {
  const { status: rawStatus, type: rawType, page: rawPage } = await searchParams;
  const status: ReportStatus = STATUSES.includes(rawStatus as ReportStatus) ? (rawStatus as ReportStatus) : "PENDING";
  const type: ReportTypeFilter = TYPES.includes(rawType as (typeof TYPES)[number]) ? (rawType as ReportTypeFilter) : null;
  const page = parsePage(rawPage);
  const [reports, counts] = await Promise.all([listReports(status, type, page), reportCounts()]);
  const href = (s: ReportStatus, t: ReportTypeFilter) => `/reports?status=${s}${t ? `&type=${t}` : ""}`;

  return (
    <>
      <h1>Signalements</h1>
      <Tabs tabs={STATUSES.map((s) => ({ href: href(s, type), label: REPORT_STATUS_LABEL[s], count: counts[s], active: s === status }))} />
      <div className="chips">
        <Link href={href(status, null)} className={type === null ? "active" : undefined}>
          Tous les types
        </Link>
        {TYPES.map((t) => (
          <Link key={t} href={href(status, t)} className={type === t ? "active" : undefined}>
            {TARGET_TYPE_LABEL[t]}
          </Link>
        ))}
      </div>
      {reports.items.length === 0 ? (
        <p className="empty">Aucun signalement {REPORT_STATUS_LABEL[status].toLowerCase()}.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Type</th>
              <th>Cible</th>
              <th>Raison</th>
              <th>Signalé par</th>
              {status !== "PENDING" && <th>Traité par</th>}
              <th />
            </tr>
          </thead>
          <tbody>
            {reports.items.map((report) => (
              <tr key={report.id}>
                <td className="nowrap">{formatDateTime(report.createdAt)}</td>
                <td>
                  <span className="badge">{TARGET_TYPE_LABEL[report.targetType]}</span>
                </td>
                <td>
                  <ReportTarget report={report} />
                </td>
                <td>{truncate(report.reason, 60)}</td>
                <td>
                  <UserLink user={report.reporter} avatar={false} />
                </td>
                {status !== "PENDING" && (
                  <td>
                    {report.resolvedBy?.email ?? "-"}
                    <div className="muted small">{formatDateTime(report.resolvedAt)}</div>
                  </td>
                )}
                <td>
                  <Link href={`/reports/${report.id}`}>{status === "PENDING" ? "Traiter" : "Voir"}</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pagination path="/reports" params={{ status, type: type ?? undefined }} page={reports.page} hasNext={reports.hasNext} />
    </>
  );
}
