-- Nom de famille (facultatif) et date de naissance (obligatoire pour les nouvelles inscriptions),
-- tous deux privés ; nullable car les comptes existants n'en ont pas (ADR-029).
ALTER TABLE "User" ADD COLUMN "lastName" TEXT,
ADD COLUMN "birthday" DATE;
