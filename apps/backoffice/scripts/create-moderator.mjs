// Crée (ou réinitialise) un compte modérateur : seul moyen d'en créer un, pas d'auto-inscription
// (ADR-020). Le mot de passe est demandé au clavier, jamais passé en argument (il resterait dans
// l'historique du terminal).
//
//   npm run create-moderator -w @snapcore/backoffice -- moderateur@alpvengers.com
import { hash } from "bcryptjs";
import { createInterface } from "node:readline/promises";
import { PrismaClient } from "@snapcore/db";

const email = process.argv[2]?.trim().toLowerCase();
if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
  console.error("Usage : npm run create-moderator -w @snapcore/backoffice -- <email>");
  process.exit(1);
}

const password = process.env.MODERATOR_PASSWORD ?? (await ask("Mot de passe (12 caractères minimum) : "));
if (password.length < 12) {
  console.error("Mot de passe trop court : 12 caractères minimum.");
  process.exit(1);
}

const prisma = new PrismaClient();
try {
  const passwordHash = await hash(password, 12);
  const existing = await prisma.moderator.findUnique({ where: { email } });
  await prisma.moderator.upsert({ where: { email }, create: { email, passwordHash }, update: { passwordHash } });
  console.log(existing ? `Mot de passe de ${email} réinitialisé.` : `Compte modérateur ${email} créé.`);
} finally {
  await prisma.$disconnect();
}

async function ask(question) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    return await rl.question(question);
  } finally {
    rl.close();
  }
}
