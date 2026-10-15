export function UserStatusBadge({ user }: { user: { moderationStatus: string; deletionRequestedAt: Date | null } }) {
  if (user.deletionRequestedAt) return <span className="badge">Suppression en cours</span>;
  if (user.moderationStatus === "SUSPENDED") return <span className="badge danger">Suspendu</span>;
  return <span className="badge ok">Actif</span>;
}
