// Correspondance clé R2 ↔ URL publique des photos de profil (ADR-016). L'URL stockée encode
// la clé : c'est ce qui permet de retrouver l'ancienne photo à supprimer, sans champ dédié.
export class PublicMediaUrls {
  private readonly prefix: string;

  constructor(baseUrl: string) {
    this.prefix = `${baseUrl.replace(/\/+$/, "")}/`;
  }

  urlFor(key: string): string {
    return this.prefix + key;
  }

  /** null si l'URL ne vient pas de notre bucket public (rien à supprimer). */
  keyFromUrl(url: string): string | null {
    return url.startsWith(this.prefix) ? url.slice(this.prefix.length) : null;
  }
}

export function tmpKeyFor(uploadId: string): string {
  return `tmp/${uploadId}`;
}
