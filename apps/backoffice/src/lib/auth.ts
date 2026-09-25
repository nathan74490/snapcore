import "server-only";
import { compare, hashSync } from "bcryptjs";
import type { NextAuthOptions } from "next-auth";
import { getServerSession } from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";
import { redirect } from "next/navigation";
import { prisma } from "./prisma";

// Comptes modérateurs internes, distincts des comptes Firebase de l'app (ADR-020). Pas
// d'auto-inscription : les comptes sont créés par scripts/create-moderator.mjs.
export const authOptions: NextAuthOptions = {
  // Credentials impose une session JWT dans NextAuth 4 : le cookie est httpOnly et chiffré (JWE),
  // illisible par le JavaScript du navigateur, et ne contient que l'identifiant du modérateur.
  session: { strategy: "jwt", maxAge: 8 * 60 * 60 },
  pages: { signIn: "/login" },
  providers: [
    CredentialsProvider({
      name: "Identifiants modérateur",
      credentials: {
        email: { label: "Email", type: "email" },
        password: { label: "Mot de passe", type: "password" },
      },
      async authorize(credentials) {
        const email = credentials?.email?.trim().toLowerCase();
        const password = credentials?.password;
        if (!email || !password) return null;
        const moderator = await prisma.moderator.findUnique({ where: { email } });
        // Comparaison bcrypt même si le compte n'existe pas : même temps de réponse dans les deux cas.
        const ok = await compare(password, moderator?.passwordHash ?? DUMMY_HASH);
        return moderator && ok ? { id: moderator.id, email: moderator.email } : null;
      },
    }),
  ],
  callbacks: {
    jwt({ token, user }) {
      if (user) token.sub = user.id;
      return token;
    },
    session({ session, token }) {
      if (session.user && token.sub) (session.user as { id?: string }).id = token.sub;
      return session;
    },
  },
};

// Hash bcrypt réel d'une valeur jetable, calculé une fois au démarrage : utilisé quand l'email est
// inconnu, pour que la réponse prenne le même temps qu'avec un vrai compte (voir authorize).
const DUMMY_HASH = hashSync(crypto.randomUUID(), 12);

export interface ModeratorSession {
  id: string;
  email: string;
}

/**
 * Session du modérateur connecté, ou redirection vers /login. À appeler dans CHAQUE page ET
 * chaque action serveur : une action serveur est un point d'entrée HTTP à part entière.
 */
export async function requireModerator(): Promise<ModeratorSession> {
  const session = await getServerSession(authOptions);
  const id = (session?.user as { id?: string } | undefined)?.id;
  if (!id || !session?.user?.email) redirect("/login");
  // Un compte supprimé depuis l'ouverture de la session ne doit plus pouvoir agir.
  const moderator = await prisma.moderator.findUnique({ where: { id }, select: { id: true, email: true } });
  if (!moderator) redirect("/login");
  return moderator;
}
