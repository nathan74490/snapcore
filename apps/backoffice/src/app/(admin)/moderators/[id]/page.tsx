import Link from "next/link";
import { notFound } from "next/navigation";
import { AuditLog } from "@/components/audit-log";
import { prisma } from "@/lib/prisma";
import { moderationLog } from "@/lib/queries";

export const dynamic = "force-dynamic";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Actions d'un modérateur.
export default async function ModeratorPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!UUID.test(id)) notFound();
  const moderator = await prisma.moderator.findUnique({ where: { id }, select: { id: true, email: true } });
  if (!moderator) notFound();
  const entries = await moderationLog(moderator.id);
  return (
    <div className="stack">
      <p>
        <Link href="/moderators">← Modérateurs</Link>
      </p>
      <h1>{moderator.email}</h1>
      <AuditLog entries={entries} showModerator={false} />
    </div>
  );
}
