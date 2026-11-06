import Link from "next/link";
import { notFound } from "next/navigation";
import { Pagination } from "@/components/pagination";
import { UserLink } from "@/components/user-link";
import { UserStatusBadge } from "@/components/user-status-badge";
import { describeMessage, formatDateTime, parsePage } from "@/lib/format";
import { getGroup } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Un groupe, ses membres et son historique (ADR-032). Lecture seule : l'API admin du backend ne
// propose pas encore de retrait d'un message de groupe.
export default async function GroupPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ page?: string }> }) {
  const [{ id }, query] = await Promise.all([params, searchParams]);
  const data = await getGroup(id, parsePage(query.page));
  if (!data) notFound();
  const { group, messages } = data;

  return (
    <div className="stack">
      <p>
        <Link href="/groups">← Groupes</Link>
      </p>
      <h1>{group.name ?? "Groupe sans nom"}</h1>
      <p className="muted">
        Créé le {formatDateTime(group.createdAt)}
        {group.createdBy && (
          <>
            {" "}
            par <Link href={`/users/${group.createdBy.id}`}>@{group.createdBy.username}</Link>
          </>
        )}{" "}
        · {group.members.length} membre(s) · {group._count.messages} message(s) · <code>{group.id}</code>
      </p>

      <h2>Membres</h2>
      <table>
        <thead>
          <tr>
            <th>Membre</th>
            <th>Arrivé le</th>
            <th>Dernière lecture</th>
            <th>Statut</th>
          </tr>
        </thead>
        <tbody>
          {group.members.map((member) => (
            <tr key={member.user.id}>
              <td>
                <UserLink user={member.user} />
              </td>
              <td className="nowrap">{formatDateTime(member.joinedAt)}</td>
              <td className="nowrap">{formatDateTime(member.lastReadAt)}</td>
              <td>
                <UserStatusBadge user={member.user} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>Messages</h2>
      {messages.items.length === 0 ? (
        <p className="empty">Aucun message.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>De</th>
              <th>Contenu</th>
              <th>Vues</th>
            </tr>
          </thead>
          <tbody>
            {messages.items.map((m) => (
              <tr key={m.id}>
                <td className="nowrap">{formatDateTime(m.createdAt)}</td>
                <td>
                  <Link href={`/users/${m.sender.id}`}>@{m.sender.username}</Link>
                </td>
                <td>
                  {m.contentType !== "TEXT" && <span className="badge snap">Snap</span>} {describeMessage({ ...m, moderatedAt: null })}
                </td>
                <td>{m.contentType === "TEXT" ? <span className="muted">-</span> : m._count.views}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pagination path={`/groups/${group.id}`} params={{}} page={messages.page} hasNext={messages.hasNext} />
    </div>
  );
}
