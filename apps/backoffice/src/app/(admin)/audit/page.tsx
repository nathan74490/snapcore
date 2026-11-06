import { AuditLog } from "@/components/audit-log";
import { moderationLog } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Journal des actions de modération, reconstitué depuis les champs d'audit en base (ADR-021).
export default async function AuditPage() {
  const entries = await moderationLog();
  return (
    <>
      <h1>Journal de modération</h1>
      <p className="muted">
        Les 150 dernières actions : suspensions en cours, messages retirés et signalements clos. Une suspension levée n&apos;y
        figure plus, et une suppression de story n&apos;est tracée que dans les logs du backend.
      </p>
      <AuditLog entries={entries} />
    </>
  );
}
