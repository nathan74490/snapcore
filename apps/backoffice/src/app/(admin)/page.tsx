import Link from "next/link";
import { dashboardStats } from "@/lib/queries";

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  const stats = await dashboardStats();
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
