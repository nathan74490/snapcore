import assert from "node:assert/strict";
import { test } from "node:test";
import { describeMessage, formatDateOnly, truncate } from "./format.ts";

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
