import Link from "next/link";
import { Pagination } from "@/components/pagination";
import { formatDateTime, parsePage } from "@/lib/format";
import { listGroups } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Groupes de discussion (ADR-032) : sans administrateur, le créateur n'est qu'une trace.
export default async function GroupsPage({ searchParams }: { searchParams: Promise<{ q?: string; page?: string }> }) {
  const params = await searchParams;
  const q = params.q ?? "";
  const page = parsePage(params.page);
  const groups = await listGroups(q, page);

  return (
    <>
      <h1>Groupes</h1>
      <form className="toolbar" action="/groups">
        <input name="q" defaultValue={q} placeholder="Nom du groupe, pseudo d'un membre ou identifiant" />
        <button className="primary" type="submit">
          Rechercher
        </button>
      </form>
      {groups.items.length === 0 ? (
        <p className="empty">{q ? "Aucun groupe ne correspond." : "Aucun groupe."}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Groupe</th>
              <th>Membres</th>
              <th>Messages</th>
              <th>Dernier message</th>
              <th>Créé</th>
            </tr>
          </thead>
          <tbody>
            {groups.items.map((group) => {
              const names = group.members.map((m) => m.user.firstName ?? `@${m.user.username}`).join(", ");
              return (
                <tr key={group.id}>
                  <td>
                    <Link href={`/groups/${group.id}`}>{group.name ?? <em>Sans nom</em>}</Link>
                    <div className="muted small">
                      {names}
                      {group._count.members > group.members.length && "…"}
                    </div>
                  </td>
                  <td>{group._count.members}</td>
                  <td>{group._count.messages}</td>
                  <td className="nowrap">{formatDateTime(group.messages[0]?.createdAt)}</td>
                  <td className="nowrap">
                    {formatDateTime(group.createdAt)}
                    {group.createdBy && (
                      <div className="small">
                        par <Link href={`/users/${group.createdBy.id}`}>@{group.createdBy.username}</Link>
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <Pagination path="/groups" params={{ q: q || undefined }} page={groups.page} hasNext={groups.hasNext} />
    </>
  );
}
