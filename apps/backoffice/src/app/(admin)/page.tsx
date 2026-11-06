import Link from "next/link";
import { BarChart } from "@/components/bar-chart";
import { ReportTarget } from "@/components/report-target";
import { TARGET_TYPE_LABEL, formatDateTime, truncate } from "@/lib/format";
import { dailyActivity, dashboardStats, listReports, stuckDeletions } from "@/lib/queries";

export const dynamic = "force-dynamic";

const percent = (part: number, whole: number) => (whole ? `${Math.round((part / whole) * 100)} %` : "-");

export default async function DashboardPage() {
  const [stats, activity, stuck, queue] = await Promise.all([dashboardStats(), dailyActivity(14), stuckDeletions(), listReports("PENDING")]);
  const posthog = process.env.POSTHOG_DASHBOARD_URL;

  const groups = [
    {
      title: "Modération",
      tiles: [
        { label: "Signalements en attente", value: stats.pendingReports, href: "/reports", tone: stats.pendingReports ? "warning" : undefined },
        { label: "Comptes suspendus", value: stats.suspended, href: "/users?status=suspended" },
        { label: "Suppressions en cours", value: stats.deleting, href: "/users?status=deleting" },
      ],
    },
    {
      title: "Communauté",
      tiles: [
        { label: "Comptes", value: stats.users, href: "/users" },
        { label: "Inscriptions (7 jours)", value: stats.newUsers },
        { label: "Amitiés", value: stats.friendships },
        { label: "Groupes", value: stats.groups, href: "/groups" },
      ],
    },
    {
      title: "Activité (24 h)",
      tiles: [
        { label: "Messages privés", value: stats.messages, href: "/messages" },
        { label: "dont Snaps", value: stats.snaps, href: "/messages?kind=snap" },
        { label: "Messages de groupe", value: stats.groupMessages },
        { label: "Stories actives", value: stats.activeStories, href: "/stories" },
      ],
    },
    {
      title: "Produit",
      tiles: [
        { label: "Abonnés SnapCore+", value: stats.premium, href: "/subscriptions", hint: percent(stats.premium, stats.users) },
        { label: "Visibles sur la Snap Map", value: stats.sharingLocation, hint: percent(stats.sharingLocation, stats.users) },
      ],
    },
  ];

  return (
    <>
      <header className="pagehead">
        <h1>Tableau de bord</h1>
        {posthog && (
          <a className="button" href={posthog} target="_blank" rel="noreferrer">
            Analytics PostHog ↗
          </a>
        )}
      </header>

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
                    <Link href={`/users/${user.id}`}>@{user.username}</Link>
                  </td>
                  <td>{formatDateTime(user.deletionRequestedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {groups.map((group) => (
        <section key={group.title}>
          <h2>{group.title}</h2>
          <div className="grid">
            {group.tiles.map((tile) => {
              const body = (
                <>
                  <div className="value">{tile.value.toLocaleString("fr-FR")}</div>
                  <div className="label">
                    {tile.label}
                    {"hint" in tile && tile.hint && <span className="muted"> · {tile.hint}</span>}
                  </div>
                </>
              );
              const className = `card stat${"tone" in tile && tile.tone ? ` ${tile.tone}` : ""}`;
              return tile.href ? (
                <Link key={tile.label} href={tile.href} className={`${className} clickable`}>
                  {body}
                </Link>
              ) : (
                <div key={tile.label} className={className}>
                  {body}
                </div>
              );
            })}
          </div>
        </section>
      ))}

      <h2>14 derniers jours</h2>
      <div className="charts">
        <BarChart title="Inscriptions" series={activity.signups} tone="ok" />
        <BarChart title="Messages privés" series={activity.messages} />
        <BarChart title="Messages de groupe" series={activity.groupMessages} tone="violet" />
        <BarChart title="Stories publiées" series={activity.stories} tone="warning" />
      </div>

      <header className="pagehead">
        <h2>File des signalements</h2>
        <Link href="/reports">Tout voir →</Link>
      </header>
      {queue.items.length === 0 ? (
        <p className="empty">Aucun signalement en attente.</p>
      ) : (
        <table>
          <tbody>
            {queue.items.slice(0, 5).map((report) => (
              <tr key={report.id}>
                <td className="nowrap">{formatDateTime(report.createdAt)}</td>
                <td>
                  <span className="badge">{TARGET_TYPE_LABEL[report.targetType]}</span>
                </td>
                <td>
                  <ReportTarget report={report} />
                </td>
                <td>{truncate(report.reason, 50)}</td>
                <td>
                  <Link href={`/reports/${report.id}`}>Traiter</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
