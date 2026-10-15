import Link from "next/link";
import type { ReactNode } from "react";
import { SignOutButton } from "@/components/sign-out-button";
import { requireModerator } from "@/lib/auth";

// Toutes les pages de ce groupe exigent une session modérateur (ADR-020). Les actions serveur
// la revérifient elles-mêmes (voir actions.ts).
export default async function AdminLayout({ children }: { children: ReactNode }) {
  const moderator = await requireModerator();
  return (
    <>
      <header className="topbar">
        <span className="brand">SnapCore · Modération</span>
        <nav>
          <Link href="/">Tableau de bord</Link>
          <Link href="/reports">Signalements</Link>
          <Link href="/users">Utilisateurs</Link>
        </nav>
        <span className="who">
          {moderator.email}
          <SignOutButton />
        </span>
      </header>
      <main>{children}</main>
    </>
  );
}
