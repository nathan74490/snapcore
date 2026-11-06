import Link from "next/link";
import { requireModerator } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { listModerators } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Équipe de modération (ADR-020). Pas de création depuis l'interface : un compte se crée en ligne
// de commande, avec `npm run create-moderator -w @snapcore/backoffice -- <email>`.
export default async function ModeratorsPage() {
  const [me, moderators] = await Promise.all([requireModerator(), listModerators()]);
  return (
    <>
      <h1>Modérateurs</h1>
      <p className="muted">
        Pour ajouter un modérateur ou réinitialiser un mot de passe : <code>npm run create-moderator -w @snapcore/backoffice -- email@exemple.com</code>
      </p>
      <table>
        <thead>
          <tr>
            <th>Email</th>
            <th>Depuis</th>
            <th>Suspensions en cours</th>
            <th>Messages retirés</th>
            <th>Signalements clos</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {moderators.map((m) => (
            <tr key={m.id}>
              <td>
                {m.email} {m.id === me.id && <span className="badge">Vous</span>}
              </td>
              <td className="nowrap">{formatDateTime(m.createdAt)}</td>
              <td>{m._count.usersSuspended}</td>
              <td>{m._count.messagesModerated}</td>
              <td>{m._count.reportsResolved}</td>
              <td>
                <Link href={`/moderators/${m.id}`}>Activité</Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
