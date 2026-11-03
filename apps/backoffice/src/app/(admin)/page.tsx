import Link from "next/link";
import { formatDateTime } from "@/lib/format";
import { dashboardStats, stuckDeletions } from "@/lib/queries";

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  const [stats, stuck] = await Promise.all([dashboardStats(), stuckDeletions()]);
  const tiles = [
    { label: "Signalements en attente", value: stats.pendingReports, href: "/reports" },
    { label: "Comptes", value: stats.users },
    { label: "Comptes suspendus", value: stats.suspended },
    { label: "Inscriptions (7 jours)", value: stats.newUsers },
    { label: "Messages (24 h)", value: stats.messages },
    { label: "Stories actives", value: stats.activeStories },
  ];
  return (
    <>
      <h1>Tableau de bord</h1>
      {stuck.length > 0 && (
        // Alerte d'ADR-019 : le job réessaie toujours, mais un compte bloqué ne doit pas passer inaperçu.
        <div className="card alert">
          <h2>Suppressions de compte bloquées depuis plus de 24 h ({stuck.length})</h2>
          <p className="muted">
            Le job de suppression réessaie sans fin. La cause est dans les logs du backend : « ALERTE : Suppression de
            compte &lt;id&gt; toujours en échec après 24h ».
          </p>
          <table>
            <thead>
              <tr>
                <th>Compte</th>
                <th>Suppression demandée le</th>
              </tr>
            </thead>
            <tbody>
              {stuck.map((user) => (
                <tr key={user.id}>
                  <td>
                    <Link href={`/users/${user.id}`}>{user.username}</Link>
                  </td>
                  <td>{formatDateTime(user.deletionRequestedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="grid">
        {tiles.map((tile) => (
          <div key={tile.label} className="card stat">
            <div className="value">{tile.href ? <Link href={tile.href}>{tile.value}</Link> : tile.value}</div>
            <div className="label">{tile.label}</div>
          </div>
        ))}
      </div>
    </>
  );
}
