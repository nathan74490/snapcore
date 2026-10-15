// Réexporte le client Prisma généré depuis prisma/schema.prisma (ADR-004).
// En JS + .d.ts plutôt qu'en TS pour être consommable tel quel par backend et backoffice,
// sans étape de build dédiée pour ce paquet.
module.exports = require("@prisma/client");
