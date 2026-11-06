import Link from "next/link";
import { UserAvatar } from "./user-avatar";

// Lien vers la fiche d'un compte, avec son avatar et son prénom quand il en a un.
export function UserLink({ user, avatar = true }: { user: { id: string; username: string; firstName?: string | null; avatar?: unknown; profilePhotoUrl?: string | null }; avatar?: boolean }) {
  return (
    <Link href={`/users/${user.id}`} className="userlink">
      {avatar && <UserAvatar user={user} size={24} />}
      <span>
        @{user.username}
        {user.firstName && <span className="muted"> · {user.firstName}</span>}
      </span>
    </Link>
  );
}
