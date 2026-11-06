import Link from "next/link";

// Pagination « précédent / suivant » : conserve les autres paramètres de l'URL (filtres, onglet).
export function Pagination({ path, params, page, hasNext }: { path: string; params: Record<string, string | undefined>; page: number; hasNext: boolean }) {
  if (page === 1 && !hasNext) return null;
  const href = (p: number) => {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) if (value) search.set(key, value);
    if (p > 1) search.set("page", String(p));
    const query = search.toString();
    return query ? `${path}?${query}` : path;
  };
  return (
    <div className="pagination">
      {page > 1 ? <Link href={href(page - 1)}>← Précédent</Link> : <span />}
      <span className="muted">Page {page}</span>
      {hasNext ? <Link href={href(page + 1)}>Suivant →</Link> : <span />}
    </div>
  );
}
