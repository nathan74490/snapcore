import Link from "next/link";
import { UserStatusBadge } from "@/components/user-status-badge";
import { formatDateTime } from "@/lib/format";
import { searchUsers } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Recherche libre, sans signalement préalable (ADR-021) : pseudo, email ou identifiant.
export default async function UsersPage({ searchParams }: { searchParams: Promise<{ q?: string }> }) {
  const { q = "" } = await searchParams;
  const users = await searchUsers(q);

  return (
    <>
      <h1>Utilisateurs</h1>
      <form className="inline" action="/users">
        <input name="q" defaultValue={q} placeholder="Pseudo, email ou identifiant" autoFocus size={40} />
        <button className="primary" type="submit">
          Rechercher
        </button>
      </form>
      <div style={{ marginTop: "1rem" }}>
        {q && users.length === 0 && <p className="muted">Aucun compte ne correspond.</p>}
        {users.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Pseudo</th>
                <th>Email</th>
                <th>Inscription</th>
                <th>Statut</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id}>
                  <td>
                    <Link href={`/users/${user.id}`}>@{user.username}</Link>
                  </td>
                  <td>{user.email ?? "—"}</td>
                  <td>{formatDateTime(user.createdAt)}</td>
                  <td>
                    <UserStatusBadge user={user} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
