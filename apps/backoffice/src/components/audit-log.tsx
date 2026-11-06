import Link from "next/link";
import { REPORT_STATUS_LABEL, TARGET_TYPE_LABEL, formatDateTime, truncate } from "@/lib/format";
import type { AuditEntry } from "@/lib/queries";

const KIND_LABEL = { suspension: "Suspension", redaction: "Message retiré", report: "Signalement clos" } as const;

// Liste des actions des modérateurs (voir moderationLog).
export function AuditLog({ entries, showModerator = true }: { entries: AuditEntry[]; showModerator?: boolean }) {
  if (entries.length === 0) return <p className="empty">Aucune action enregistrée.</p>;
  return (
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Action</th>
          <th>Cible</th>
          <th>Détail</th>
          {showModerator && <th>Modérateur</th>}
        </tr>
      </thead>
      <tbody>
        {entries.map((entry, i) => (
          <tr key={`${entry.kind}-${i}`}>
            <td className="nowrap">{formatDateTime(entry.at)}</td>
            <td>
              <span className={`badge ${entry.kind === "suspension" || entry.kind === "redaction" ? "danger" : "ok"}`}>{KIND_LABEL[entry.kind]}</span>
            </td>
            <td>
              {entry.kind === "report" ? (
                <Link href={`/reports/${entry.reportId}`}>
                  {TARGET_TYPE_LABEL[entry.targetType]} · {REPORT_STATUS_LABEL[entry.status]}
                </Link>
              ) : (
                <Link href={`/users/${entry.userId}`}>@{entry.username}</Link>
              )}
            </td>
            <td>{entry.detail ? truncate(entry.detail, 80) : <span className="muted">-</span>}</td>
            {showModerator && <td>{entry.moderator ?? <span className="muted">Modérateur supprimé</span>}</td>}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
