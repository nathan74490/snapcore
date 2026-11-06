import type { ReactNode } from "react";
import { Nav } from "@/components/nav";
import { SignOutButton } from "@/components/sign-out-button";
import { requireModerator } from "@/lib/auth";
import { prisma } from "@/lib/prisma";

// Toutes les pages de ce groupe exigent une session modérateur (ADR-020). Les actions serveur
// la revérifient elles-mêmes (voir actions.ts).
export default async function AdminLayout({ children }: { children: ReactNode }) {
  const moderator = await requireModerator();
  const pending = await prisma.report.count({ where: { status: "PENDING" } });

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">SnapCore · Admin</div>
        <Nav
          sections={[
            { title: "Vue d'ensemble", items: [{ href: "/", label: "Tableau de bord" }] },
            {
              title: "Modération",
              items: [
                { href: "/reports", label: "Signalements", badge: pending },
                { href: "/messages", label: "Messages" },
                { href: "/stories", label: "Stories" },
                { href: "/audit", label: "Journal" },
              ],
            },
            {
              title: "Communauté",
              items: [
                { href: "/users", label: "Utilisateurs" },
                { href: "/groups", label: "Groupes" },
                { href: "/subscriptions", label: "SnapCore+" },
              ],
            },
            { title: "Équipe", items: [{ href: "/moderators", label: "Modérateurs" }] },
          ]}
        />
        <div className="who">
          <span title={moderator.email}>{moderator.email}</span>
          <SignOutButton />
        </div>
      </aside>
      <main>{children}</main>
    </div>
  );
}
