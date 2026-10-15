-- ADR-009 : le contenu d'un message dépend de son type. Un TEXT porte du texte, jamais de média ni de
-- purge programmée (ADR-017) ; un Snap porte un média, jamais de texte, et toujours une purge programmée.
-- Les vidages légitimes restent possibles :
--   - Snap : mediaKey passe à NULL à la purge (ADR-017) ou à la rédaction par modération (ADR-021) ;
--   - TEXT : content ne passe à NULL que par une rédaction de modération, donc avec moderatedAt renseigné.
-- Colonnes entre guillemets doubles : casse des colonnes générées par Prisma (même piège qu'ADR-012).
ALTER TABLE "Message" ADD CONSTRAINT "Message_content_matches_type_check" CHECK (
    ("contentType" = 'TEXT'
        AND "mediaKey" IS NULL
        AND "scheduledPurgeAt" IS NULL
        AND ("content" IS NOT NULL OR "moderatedAt" IS NOT NULL)) OR
    ("contentType" IN ('SNAP_PHOTO', 'SNAP_PHOTO_VIDEO')
        AND "content" IS NULL
        AND "scheduledPurgeAt" IS NOT NULL)
);
