import Link from "next/link";
import { ReportTarget } from "@/components/report-target";
import { UserLink } from "@/components/user-link";
import { REPORT_STATUS_LABEL, TARGET_TYPE_LABEL, formatDateTime, truncate } from "@/lib/format";
import { userReports, type ReportWithTarget } from "@/lib/queries";

function ReportTable({ reports, showReporter }: { reports: ReportWithTarget[]; showReporter: boolean }) {
  if (reports.length === 0) return <p className="empty">Aucun.</p>;
  return (
    <table>
      <tbody>
        {reports.map((r) => (
          <tr key={r.id}>
            <td className="nowrap">{formatDateTime(r.createdAt)}</td>
            <td>
              <span className="badge">{TARGET_TYPE_LABEL[r.targetType]}</span>
            </td>
            <td>{showReporter ? <UserLink user={r.reporter} avatar={false} /> : <ReportTarget report={r} />}</td>
            <td>{truncate(r.reason, 70)}</td>
            <td>
              <span className={`badge ${r.status === "PENDING" ? "warning" : "ok"}`}>{REPORT_STATUS_LABEL[r.status]}</span>
            </td>
            <td>
              <Link href={`/reports/${r.id}`}>Voir</Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// Signalements reçus (le compte, ses messages ou ses stories) et envoyés par un compte.
export async function UserReports({ userId }: { userId: string }) {
  const { received, made } = await userReports(userId);
  return (
    <>
      <h2>Reçus ({received.length})</h2>
      <p className="muted small">Signalements visant ce compte, ses messages ou ses stories, avec la personne qui a signalé.</p>
      <ReportTable reports={received} showReporter />
      <h2>Envoyés ({made.length})</h2>
      <ReportTable reports={made} showReporter={false} />
    </>
  );
}
