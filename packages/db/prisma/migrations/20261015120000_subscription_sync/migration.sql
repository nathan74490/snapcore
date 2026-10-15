-- ADR-025 : date du dernier événement RevenueCat appliqué au compte, pour ignorer un webhook plus ancien.
ALTER TABLE "User" ADD COLUMN "subscriptionEventAt" TIMESTAMP(3);
