-- Groupes de discussion (ADR-032) : chats et snaps à plusieurs, sans administrateur, jusqu'à 32 membres.
-- Tables à part : la messagerie à deux (Message) et ses contraintes (ADR-009, ADR-017, ADR-021) ne changent pas.
-- Suppression d'un User → ses appartenances, ses messages de groupe et ses ouvertures (CASCADE) ; le groupe
-- qu'il a créé reste (createdById → NULL). Suppression d'un Group → membres, messages et ouvertures.

-- CreateTable
CREATE TABLE "Group" (
    "id" TEXT NOT NULL,
    "name" TEXT,
    "createdById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Group_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "GroupMember" (
    "groupId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "joinedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastReadAt" TIMESTAMP(3),

    CONSTRAINT "GroupMember_pkey" PRIMARY KEY ("groupId","userId")
);

-- CreateTable
CREATE TABLE "GroupMessage" (
    "id" TEXT NOT NULL,
    "groupId" TEXT NOT NULL,
    "senderId" TEXT NOT NULL,
    "contentType" "MessageContentType" NOT NULL,
    "content" TEXT,
    "mediaKey" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "scheduledPurgeAt" TIMESTAMP(3),

    CONSTRAINT "GroupMessage_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "GroupMessageView" (
    "messageId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "openedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "GroupMessageView_pkey" PRIMARY KEY ("messageId","userId")
);

-- CreateIndex
CREATE INDEX "Group_createdById_idx" ON "Group"("createdById");

-- CreateIndex
CREATE INDEX "GroupMember_userId_idx" ON "GroupMember"("userId");

-- CreateIndex
CREATE INDEX "GroupMessage_groupId_createdAt_idx" ON "GroupMessage"("groupId", "createdAt");

-- CreateIndex
CREATE INDEX "GroupMessage_senderId_idx" ON "GroupMessage"("senderId");

-- CreateIndex
CREATE INDEX "GroupMessage_scheduledPurgeAt_idx" ON "GroupMessage"("scheduledPurgeAt");

-- CreateIndex
CREATE INDEX "GroupMessageView_userId_idx" ON "GroupMessageView"("userId");

-- AddForeignKey
ALTER TABLE "Group" ADD CONSTRAINT "Group_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "GroupMember" ADD CONSTRAINT "GroupMember_groupId_fkey" FOREIGN KEY ("groupId") REFERENCES "Group"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "GroupMember" ADD CONSTRAINT "GroupMember_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "GroupMessage" ADD CONSTRAINT "GroupMessage_groupId_fkey" FOREIGN KEY ("groupId") REFERENCES "Group"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "GroupMessage" ADD CONSTRAINT "GroupMessage_senderId_fkey" FOREIGN KEY ("senderId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "GroupMessageView" ADD CONSTRAINT "GroupMessageView_messageId_fkey" FOREIGN KEY ("messageId") REFERENCES "GroupMessage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "GroupMessageView" ADD CONSTRAINT "GroupMessageView_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;


-- Nom facultatif : NULL ou 1 à 50 caractères, déjà trimé par l'application (un nom vide retire le nom).
ALTER TABLE "Group" ADD CONSTRAINT "Group_name_length_check" CHECK (
    "name" IS NULL OR (char_length("name") BETWEEN 1 AND 50 AND "name" = btrim("name"))
);

-- Même règle de cohérence que "Message_content_matches_type_check" (migration 20261015000000_message_content_check),
-- sans la rédaction de modération (pas de modération des messages de groupe) : un TEXT porte du texte, jamais
-- de média ni de purge programmée ; un snap porte un média (vidé à la purge, ADR-017), jamais de texte, et
-- toujours une purge programmée. Colonnes entre guillemets doubles : casse des colonnes générées par Prisma.
ALTER TABLE "GroupMessage" ADD CONSTRAINT "GroupMessage_content_matches_type_check" CHECK (
    ("contentType" = 'TEXT'
        AND "mediaKey" IS NULL
        AND "scheduledPurgeAt" IS NULL
        AND "content" IS NOT NULL) OR
    ("contentType" IN ('SNAP_PHOTO', 'SNAP_PHOTO_VIDEO')
        AND "content" IS NULL
        AND "scheduledPurgeAt" IS NOT NULL)
);
