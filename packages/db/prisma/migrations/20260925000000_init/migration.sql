-- CreateEnum
CREATE TYPE "SubscriptionTier" AS ENUM ('free', 'premium');

-- CreateEnum
CREATE TYPE "MessageContentType" AS ENUM ('TEXT', 'SNAP_PHOTO', 'SNAP_PHOTO_VIDEO');

-- CreateEnum
CREATE TYPE "FriendshipStatus" AS ENUM ('PENDING', 'ACCEPTED', 'BLOCKED', 'DECLINED');

-- CreateEnum
CREATE TYPE "StoryContentType" AS ENUM ('PHOTO', 'VIDEO');

-- CreateTable
CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "username" TEXT NOT NULL,
    "firebaseUid" TEXT NOT NULL,
    "profilePhotoUrl" TEXT,
    "phoneNumber" TEXT,
    "email" TEXT,
    "subscriptionTier" "SubscriptionTier" NOT NULL DEFAULT 'free',
    "premiumUntil" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletionRequestedAt" TIMESTAMP(3),

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Message" (
    "id" TEXT NOT NULL,
    "senderId" TEXT NOT NULL,
    "recipientId" TEXT NOT NULL,
    "contentType" "MessageContentType" NOT NULL,
    "content" TEXT,
    "mediaKey" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "openedAt" TIMESTAMP(3),
    "scheduledPurgeAt" TIMESTAMP(3),
    "retainedNotifiedAt" TIMESTAMP(3),

    CONSTRAINT "Message_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Friendship" (
    "id" TEXT NOT NULL,
    "requesterId" TEXT NOT NULL,
    "addresseeId" TEXT NOT NULL,
    "status" "FriendshipStatus" NOT NULL DEFAULT 'PENDING',
    "blockedBy" TEXT,
    "requestedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "respondedAt" TIMESTAMP(3),
    "pairLow" TEXT NOT NULL,
    "pairHigh" TEXT NOT NULL,

    CONSTRAINT "Friendship_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "StoryItem" (
    "id" TEXT NOT NULL,
    "authorId" TEXT NOT NULL,
    "mediaKey" TEXT NOT NULL,
    "contentType" "StoryContentType" NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "StoryItem_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DeviceToken" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "token" TEXT NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DeviceToken_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_username_key" ON "User"("username");

-- CreateIndex
CREATE UNIQUE INDEX "User_firebaseUid_key" ON "User"("firebaseUid");

-- CreateIndex
CREATE UNIQUE INDEX "User_phoneNumber_key" ON "User"("phoneNumber");

-- CreateIndex
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

-- CreateIndex
CREATE INDEX "Message_senderId_idx" ON "Message"("senderId");

-- CreateIndex
CREATE INDEX "Message_recipientId_idx" ON "Message"("recipientId");

-- CreateIndex
CREATE INDEX "Message_senderId_recipientId_createdAt_idx" ON "Message"("senderId", "recipientId", "createdAt");

-- CreateIndex
CREATE INDEX "Message_scheduledPurgeAt_idx" ON "Message"("scheduledPurgeAt");

-- CreateIndex
CREATE INDEX "Friendship_addresseeId_idx" ON "Friendship"("addresseeId");

-- CreateIndex
CREATE UNIQUE INDEX "Friendship_pairLow_pairHigh_key" ON "Friendship"("pairLow", "pairHigh");

-- CreateIndex
CREATE INDEX "StoryItem_authorId_idx" ON "StoryItem"("authorId");

-- CreateIndex
CREATE INDEX "StoryItem_expiresAt_idx" ON "StoryItem"("expiresAt");

-- CreateIndex
CREATE UNIQUE INDEX "DeviceToken_token_key" ON "DeviceToken"("token");

-- CreateIndex
CREATE INDEX "DeviceToken_userId_idx" ON "DeviceToken"("userId");

-- AddForeignKey
ALTER TABLE "Message" ADD CONSTRAINT "Message_senderId_fkey" FOREIGN KEY ("senderId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Message" ADD CONSTRAINT "Message_recipientId_fkey" FOREIGN KEY ("recipientId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Friendship" ADD CONSTRAINT "Friendship_requesterId_fkey" FOREIGN KEY ("requesterId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Friendship" ADD CONSTRAINT "Friendship_addresseeId_fkey" FOREIGN KEY ("addresseeId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Friendship" ADD CONSTRAINT "Friendship_blockedBy_fkey" FOREIGN KEY ("blockedBy") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "StoryItem" ADD CONSTRAINT "StoryItem_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DeviceToken" ADD CONSTRAINT "DeviceToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;


-- Contraintes CHECK non exprimables dans le DSL Prisma (ADR-012).
-- Interdit l'auto-ajout.
ALTER TABLE "Friendship" ADD CONSTRAINT "Friendship_not_self_check"
    CHECK ("requesterId" <> "addresseeId");

-- Cohérence de la paire triée avec requesterId/addresseeId. Collation "C" : ordre binaire,
-- identique à la comparaison de chaînes faite côté application, quelle que soit la locale du serveur.
ALTER TABLE "Friendship" ADD CONSTRAINT "Friendship_sorted_pair_check"
    CHECK ("pairLow" = LEAST("requesterId" COLLATE "C", "addresseeId" COLLATE "C")
       AND "pairHigh" = GREATEST("requesterId" COLLATE "C", "addresseeId" COLLATE "C"));
