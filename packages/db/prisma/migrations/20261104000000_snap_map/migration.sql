-- Snap Map (ADR-031) : mode de partage de position (fantôme par défaut, confidentialité d'abord),
-- liste d'amis exclus / autorisés, et position arrondie d'un élément de story. La dernière position
-- d'un utilisateur n'est jamais en base : elle vit dans Redis avec un TTL de 8 h (ADR-003).

-- CreateEnum
CREATE TYPE "LocationSharingMode" AS ENUM ('GHOST', 'ALL_FRIENDS', 'FRIENDS_EXCEPT', 'ONLY_FRIENDS');

-- AlterTable
ALTER TABLE "StoryItem" ADD COLUMN     "latitude" DOUBLE PRECISION,
ADD COLUMN     "longitude" DOUBLE PRECISION;

-- AlterTable
ALTER TABLE "User" ADD COLUMN     "locationSharing" "LocationSharingMode" NOT NULL DEFAULT 'GHOST';

-- CreateTable
CREATE TABLE "LocationShareRule" (
    "ownerId" TEXT NOT NULL,
    "friendId" TEXT NOT NULL,

    CONSTRAINT "LocationShareRule_pkey" PRIMARY KEY ("ownerId","friendId")
);

-- CreateIndex
CREATE INDEX "LocationShareRule_friendId_idx" ON "LocationShareRule"("friendId");

-- AddForeignKey
ALTER TABLE "LocationShareRule" ADD CONSTRAINT "LocationShareRule_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LocationShareRule" ADD CONSTRAINT "LocationShareRule_friendId_fkey" FOREIGN KEY ("friendId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
