-- Prénom affiché à la place du pseudo ; nullable car les comptes existants n'en ont pas.
ALTER TABLE "User" ADD COLUMN "firstName" TEXT;
