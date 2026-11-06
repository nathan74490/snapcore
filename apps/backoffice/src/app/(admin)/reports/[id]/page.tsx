import Link from "next/link";
import { notFound } from "next/navigation";
import { closeReport, deleteStoryItem, redactMessage, suspendUser } from "@/app/actions";
import { ActionForm } from "@/components/action-form";
import { ReportTarget } from "@/components/report-target";
import { UserLink } from "@/components/user-link";
import { REPORT_STATUS_LABEL, TARGET_TYPE_LABEL, formatDateTime, truncate } from "@/lib/format";
import { getReport, otherReportsAgainst } from "@/lib/queries";

export const dynamic = "force-dynamic";

export default async function ReportPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const report = await getReport(id).catch(() => null);
  if (!report) notFound();

  const returnTo = `/reports/${report.id}`;
  const pending = report.status === "PENDING";
  // Compte visé : directement (signalement de compte) ou auteur du contenu signalé.
  const offender = report.targetUser ?? report.targetMessage?.sender ?? report.targetStoryItem?.author ?? null;
  const history = offender ? await otherReportsAgainst(offender.id, report.id) : [];

  return (
    <div className="stack">
      <p>
        <Link href="/reports">← Signalements</Link>
      </p>
      <h1>
        Signalement · {TARGET_TYPE_LABEL[report.targetType]}{" "}
        <span className={`badge ${pending ? "warning" : "ok"}`}>{REPORT_STATUS_LABEL[report.status]}</span>
      </h1>

      <div className="card">
        <dl>
          <dt>Cible</dt>
          <dd>
            <ReportTarget report={report} full />
          </dd>
          <dt>Raison</dt>
          <dd>{report.reason}</dd>
          <dt>Signalé par</dt>
          <dd>
            <UserLink user={report.reporter} /> le {formatDateTime(report.createdAt)}
          </dd>
          {!pending && (
            <>
              <dt>Traité par</dt>
              <dd>
                {report.resolvedBy?.email ?? "inconnu"} le {formatDateTime(report.resolvedAt)}
              </dd>
            </>
          )}
        </dl>
      </div>

      <h2>Actions sur le contenu</h2>
      <div className="card stack">
        {report.targetMessage && !report.targetMessage.moderatedAt && (
          <ActionForm
            action={redactMessage}
            label="Retirer ce message"
            fields={{ messageId: report.targetMessage.id, returnTo }}
            withReason
            variant="danger"
            confirm="Retirer ce message ? Le texte ou le média est supprimé définitivement."
          />
        )}
        {report.targetStoryItem && (
          <ActionForm
            action={deleteStoryItem}
            label="Supprimer cet élément de story"
            fields={{ storyItemId: report.targetStoryItem.id, returnTo }}
            withReason
            variant="danger"
            confirm="Supprimer cet élément de story ?"
          />
        )}
        {offender && offender.moderationStatus === "ACTIVE" && !offender.deletionRequestedAt && (
          <ActionForm
            action={suspendUser}
            label={`Suspendre @${offender.username}`}
            fields={{ userId: offender.id }}
            withReason
            variant="danger"
            confirm={`Suspendre @${offender.username} ? Le compte deviendra invisible et ne pourra plus se connecter.`}
          />
        )}
        {offender && offender.moderationStatus === "SUSPENDED" && <p className="muted">@{offender.username} est déjà suspendu.</p>}
        {!report.targetMessage && !report.targetStoryItem && !offender && <p className="muted">Le contenu visé n'existe plus.</p>}
      </div>

      {offender && (
        <>
          <h2>
            Autres signalements visant <Link href={`/users/${offender.id}`}>@{offender.username}</Link> ({history.length})
          </h2>
          {history.length === 0 ? (
            <p className="empty">Aucun autre signalement : premier signalement pour ce compte.</p>
          ) : (
            <table>
              <tbody>
                {history.map((other) => (
                  <tr key={other.id}>
                    <td className="nowrap">{formatDateTime(other.createdAt)}</td>
                    <td>
                      <span className="badge">{TARGET_TYPE_LABEL[other.targetType]}</span>
                    </td>
                    <td>{truncate(other.reason, 80)}</td>
                    <td>
                      <span className={`badge ${other.status === "PENDING" ? "warning" : "ok"}`}>{REPORT_STATUS_LABEL[other.status]}</span>
                    </td>
                    <td>
                      <Link href={`/reports/${other.id}`}>Voir</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      {pending && (
        <>
          <h2>Clôturer le signalement</h2>
          <div className="row">
            <ActionForm action={closeReport} label="Résolu" fields={{ reportId: report.id, status: "RESOLVED" }} variant="primary" />
            <ActionForm action={closeReport} label="Classer sans suite" fields={{ reportId: report.id, status: "DISMISSED" }} />
          </div>
        </>
      )}
    </div>
  );
}
