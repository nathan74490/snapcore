import { deleteStoryItem } from "@/app/actions";
import { ActionForm } from "@/components/action-form";
import { Pagination } from "@/components/pagination";
import { UserLink } from "@/components/user-link";
import { formatDateTime, parsePage } from "@/lib/format";
import { listActiveStories } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Stories encore visibles dans l'app (24 h, ADR-013). Le média reste privé sur R2 et n'est pas
// affiché ; une story publiée hors mode fantôme apparaît aussi sur la Snap Map (ADR-031).
export default async function StoriesPage({ searchParams }: { searchParams: Promise<{ page?: string }> }) {
  const page = parsePage((await searchParams).page);
  const stories = await listActiveStories(page);

  return (
    <>
      <h1>Stories actives</h1>
      {stories.items.length === 0 ? (
        <p className="empty">Aucune story en ligne.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Auteur</th>
              <th>Type</th>
              <th>Publiée</th>
              <th>Expire</th>
              <th>Snap Map</th>
              <th>Signalements</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {stories.items.map((story) => (
              <tr key={story.id}>
                <td>
                  <UserLink user={story.author} />
                </td>
                <td>{story.contentType === "VIDEO" ? "Vidéo" : "Photo"}</td>
                <td className="nowrap">{formatDateTime(story.createdAt)}</td>
                <td className="nowrap">{formatDateTime(story.expiresAt)}</td>
                <td>
                  {story.latitude !== null && story.longitude !== null ? (
                    <a href={`https://www.openstreetmap.org/?mlat=${story.latitude}&mlon=${story.longitude}#map=14/${story.latitude}/${story.longitude}`} target="_blank" rel="noreferrer">
                      Voir le lieu ↗
                    </a>
                  ) : (
                    <span className="muted">Non</span>
                  )}
                </td>
                <td>{story._count.reports > 0 ? <span className="badge warning">{story._count.reports}</span> : <span className="muted">0</span>}</td>
                <td>
                  <ActionForm
                    action={deleteStoryItem}
                    label="Supprimer"
                    fields={{ storyItemId: story.id, returnTo: "/stories" }}
                    withReason
                    variant="danger"
                    confirm="Supprimer cet élément de story ?"
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pagination path="/stories" params={{}} page={stories.page} hasNext={stories.hasNext} />
    </>
  );
}
