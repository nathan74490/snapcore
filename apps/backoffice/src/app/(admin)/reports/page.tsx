import type { ReportStatus } from "@snapcore/types";
import Link from "next/link";
import { ReportTarget } from "@/components/report-target";
import { REPORT_STATUS_LABEL, TARGET_TYPE_LABEL, formatDateTime, truncate } from "@/lib/format";
import { listReports } from "@/lib/queries";

export const dynamic = "force-dynamic";

const STATUSES: ReportStatus[] = ["PENDING", "RESOLVED", "DISMISSED"];

export default async function ReportsPage({ searchParams }: { searchParams: Promise<{ status?: string }> }) {
  const { status: raw } = await searchParams;
  const status: ReportStatus = STATUSES.includes(raw as ReportStatus) ? (raw as ReportStatus) : "PENDING";
  const reports = await listReports(status);

  return (
    <>
      <h1>Signalements</h1>
      <div className="tabs">
        {STATUSES.map((s) => (
          <Link key={s} href={`/reports?status=${s}`} className={s === status ? "active" : ""}>
            {REPORT_STATUS_LABEL[s]}
          </Link>
        ))}
      </div>
      {reports.length === 0 ? (
        <p className="muted">Aucun signalement {REPORT_STATUS_LABEL[status].toLowerCase()}.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Type</th>
              <th>Cible</th>
              <th>Raison</th>
              <th>Signalé par</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {reports.map((report) => (
              <tr key={report.id}>
                <td>{formatDateTime(report.createdAt)}</td>
                <td>
                  <span className="badge">{TARGET_TYPE_LABEL[report.targetType]}</span>
                </td>
                <td>
                  <ReportTarget report={report} />
                </td>
                <td>{truncate(report.reason, 60)}</td>
                <td>
                  <Link href={`/users/${report.reporter.id}`}>@{report.reporter.username}</Link>
                </td>
                <td>
                  <Link href={`/reports/${report.id}`}>Traiter</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
