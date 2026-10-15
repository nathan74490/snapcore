import Link from "next/link";
import { describeMessage, formatDateTime, truncate } from "@/lib/format";
import type { ReportWithTarget } from "@/lib/queries";

// Résumé de la cible d'un signalement. La cible peut avoir disparu (story expirée, compte supprimé) :
// le signalement est conservé, sa clé passe à NULL (ADR-021, révision 1).
export function ReportTarget({ report, full = false }: { report: ReportWithTarget; full?: boolean }) {
  switch (report.targetType) {
    case "USER":
      return report.targetUser ? (
        <Link href={`/users/${report.targetUser.id}`}>@{report.targetUser.username}</Link>
      ) : (
        <span className="muted">Compte supprimé</span>
      );
    case "MESSAGE": {
      const m = report.targetMessage;
      if (!m) return <span className="muted">Message supprimé</span>;
      const text = describeMessage(m);
      return (
        <span>
          <Link href={`/users/${m.sender.id}`}>@{m.sender.username}</Link> → @{m.recipient.username} :{" "}
          <q>{full ? text : truncate(text)}</q> <span className="muted">({formatDateTime(m.createdAt)})</span>
        </span>
      );
    }
    case "STORY_ITEM": {
      const s = report.targetStoryItem;
      if (!s) return <span className="muted">Story expirée ou supprimée</span>;
      return (
        <span>
          Story {s.contentType === "VIDEO" ? "vidéo" : "photo"} de <Link href={`/users/${s.author.id}`}>@{s.author.username}</Link>{" "}
          <span className="muted">(expire {formatDateTime(s.expiresAt)})</span>
        </span>
      );
    }
  }
}
