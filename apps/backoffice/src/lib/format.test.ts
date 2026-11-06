import assert from "node:assert/strict";
import { test } from "node:test";
import { avatarQuery, dailySeries, describeMessage, formatDateOnly, parsePage, subscriptionState, truncate } from "./format.ts";

test("un message rédigé n'affiche jamais son ancien contenu", () => {
  assert.equal(
    describeMessage({ contentType: "TEXT", content: null, mediaKey: null, moderatedAt: new Date() }),
    "Retiré par la modération",
  );
});

test("un Snap montre sa nature, pas son média, et signale s'il a expiré", () => {
  assert.equal(describeMessage({ contentType: "SNAP_PHOTO", content: null, mediaKey: "messages/x", moderatedAt: null }), "Snap photo");
  assert.equal(describeMessage({ contentType: "SNAP_PHOTO_VIDEO", content: null, mediaKey: null, moderatedAt: null }), "Snap vidéo (expiré)");
});

test("une date de naissance (colonne DATE, minuit UTC) s'affiche sans décalage de jour", () => {
  assert.equal(formatDateOnly(new Date("2004-05-12T00:00:00Z")), "12/05/2004");
  assert.equal(formatDateOnly(null), null);
});

test("un Chat texte montre son texte", () => {
  assert.equal(describeMessage({ contentType: "TEXT", content: "salut", mediaKey: null, moderatedAt: null }), "salut");
});

test("tronque au-delà de la longueur maximale", () => {
  assert.equal(truncate("abcdef", 4), "abc…");
  assert.equal(truncate("abc", 4), "abc");
});

test("le numéro de page refuse tout ce qui n'est pas un entier positif", () => {
  assert.equal(parsePage(undefined), 1);
  assert.equal(parsePage("3"), 3);
  assert.equal(parsePage("0"), 1);
  assert.equal(parsePage("-2"), 1);
  assert.equal(parsePage("1.5"), 1);
  assert.equal(parsePage("abc"), 1);
});

test("l'état d'un abonnement SnapCore+ dépend de sa date de fin", () => {
  const now = new Date("2026-11-06T12:00:00Z");
  const day = 24 * 60 * 60 * 1000;
  assert.equal(subscriptionState({ subscriptionTier: "free", premiumUntil: null }, now), "free");
  assert.equal(subscriptionState({ subscriptionTier: "premium", premiumUntil: new Date(now.getTime() + 30 * day) }, now), "active");
  assert.equal(subscriptionState({ subscriptionTier: "premium", premiumUntil: new Date(now.getTime() + 2 * day) }, now), "expiring");
  assert.equal(subscriptionState({ subscriptionTier: "premium", premiumUntil: new Date(now.getTime() - day) }, now), "expired");
});

test("la série quotidienne remplit les jours sans activité et finit aujourd'hui (heure de Paris)", () => {
  // 23 h 30 UTC le 5 novembre = 0 h 30 le 6 novembre à Paris.
  const series = dailySeries([{ day: "2026-11-06", count: 4 }, { day: "2026-11-04", count: 1 }], 3, new Date("2026-11-05T23:30:00Z"));
  assert.deepEqual(
    series.map((d) => [d.key, d.count]),
    [["2026-11-04", 1], ["2026-11-05", 0], ["2026-11-06", 4]],
  );
  assert.equal(series[2].label, "06/11");
});

test("un avatar incomplet ne produit pas d'URL de rendu", () => {
  const avatar = {
    top: "shortFlat", hairColor: "2c1b18", skinColor: "edb98a", eyes: "default", eyebrows: "default", mouth: "smile",
    facialHair: "none", accessories: "none", clothing: "hoodie", clothesColor: "262e33", backgroundColor: "65c9ff",
  };
  assert.equal(avatarQuery(avatar, 64), "top=shortFlat&hairColor=2c1b18&skinColor=edb98a&eyes=default&eyebrows=default&mouth=smile&facialHair=none&accessories=none&clothing=hoodie&clothesColor=262e33&backgroundColor=65c9ff&size=64");
  assert.equal(avatarQuery({ ...avatar, mouth: undefined }), null);
  assert.equal(avatarQuery(null), null);
});
