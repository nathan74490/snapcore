import { avatarQuery } from "@/lib/format";

interface Props {
  user: { username: string; firstName?: string | null; avatar?: unknown; profilePhotoUrl?: string | null };
  size?: number;
}

// Avatar dessiné (ADR-028), rendu par le backend via /avatar ; sinon la photo de profil (CDN
// public, ADR-014) ; sinon l'initiale.
export function UserAvatar({ user, size = 32 }: Props) {
  // Rendu en double densité, au moins 64 px (minimum du backend).
  const query = avatarQuery(user.avatar, Math.max(64, size * 2));
  const src = query ? `/avatar?${query}` : user.profilePhotoUrl;
  const style = { width: size, height: size };
  if (src) {
    // eslint-disable-next-line @next/next/no-img-element
    return <img className="avatar" src={src} alt="" width={size} height={size} style={style} loading="lazy" />;
  }
  const initial = (user.firstName || user.username).slice(0, 1).toUpperCase();
  return (
    <span className="avatar initial" style={{ ...style, fontSize: size * 0.45 }} aria-hidden>
      {initial}
    </span>
  );
}
