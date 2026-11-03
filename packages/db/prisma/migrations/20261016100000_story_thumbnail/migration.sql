-- Vignette privée d'un élément de story (ADR-026) ; nullable car les éléments existants n'en ont pas.
ALTER TABLE "StoryItem" ADD COLUMN "thumbnailKey" TEXT;
