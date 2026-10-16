-- Options de l'avatar dessiné avataaars (ADR-028) ; nullable : null = pas d'avatar.
ALTER TABLE "User" ADD COLUMN "avatar" JSONB;
