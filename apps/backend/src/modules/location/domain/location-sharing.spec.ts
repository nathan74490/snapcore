import type { LocationSharingMode } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";
import type { Friendship } from "../../friendships/domain/friendship";
import {
  canSeeLocation,
  MAX_SHARING_FRIEND_IDS,
  parseOptionalCoordinates,
  parseSharingFriendIds,
  storyCoordinates,
  type LocationOwner,
} from "./location-sharing";

const friendship = (status: Friendship["status"], blockedBy: string | null = null): Friendship => ({
  id: "f1",
  requesterId: "owner",
  addresseeId: "viewer",
  status,
  blockedBy,
  requestedAt: new Date(),
  respondedAt: null,
});

const owner = (locationSharing: LocationSharingMode, overrides: Partial<LocationOwner> = {}): LocationOwner => ({
  deletionRequestedAt: null,
  moderationStatus: "ACTIVE",
  locationSharing,
  viewerListed: false,
  ...overrides,
});

describe("Règle de visibilité de la Snap Map (ADR-031)", () => {
  const friends = friendship("ACCEPTED");

  it("ALL_FRIENDS : visible de tout ami ACCEPTED, listé ou non", () => {
    expect(canSeeLocation(friends, owner("ALL_FRIENDS"))).toBe(true);
    expect(canSeeLocation(friends, owner("ALL_FRIENDS", { viewerListed: true }))).toBe(true);
  });

  it("GHOST : invisible de tous, même des amis listés", () => {
    expect(canSeeLocation(friends, owner("GHOST"))).toBe(false);
    expect(canSeeLocation(friends, owner("GHOST", { viewerListed: true }))).toBe(false);
  });

  it("FRIENDS_EXCEPT : visible sauf des amis listés", () => {
    expect(canSeeLocation(friends, owner("FRIENDS_EXCEPT"))).toBe(true);
    expect(canSeeLocation(friends, owner("FRIENDS_EXCEPT", { viewerListed: true }))).toBe(false);
  });

  it("ONLY_FRIENDS : visible uniquement des amis listés", () => {
    expect(canSeeLocation(friends, owner("ONLY_FRIENDS"))).toBe(false);
    expect(canSeeLocation(friends, owner("ONLY_FRIENDS", { viewerListed: true }))).toBe(true);
  });

  it.each([
    ["sans relation", null],
    ["demande en attente", friendship("PENDING")],
    ["demande refusée", friendship("DECLINED")],
    ["bloqué par le propriétaire", friendship("BLOCKED", "owner")],
    ["bloqué par le viewer", friendship("BLOCKED", "viewer")],
  ])("exige une amitié ACCEPTED (%s)", (_, relation) => {
    expect(canSeeLocation(relation, owner("ALL_FRIENDS"))).toBe(false);
    expect(canSeeLocation(relation, owner("ONLY_FRIENDS", { viewerListed: true }))).toBe(false);
  });

  it("propriétaire inactif : suppression demandée ou suspendu (ADR-019, ADR-021)", () => {
    expect(canSeeLocation(friends, owner("ALL_FRIENDS", { deletionRequestedAt: new Date() }))).toBe(false);
    expect(canSeeLocation(friends, owner("ALL_FRIENDS", { moderationStatus: "SUSPENDED" }))).toBe(false);
  });
});

describe("Paramètres de la Snap Map", () => {
  it("liste d'amis dédupliquée, refusée au-delà de 2000 éléments distincts", () => {
    expect(parseSharingFriendIds(["b", "a", "b", "a"])).toEqual(["b", "a"]);
    const max = Array.from({ length: MAX_SHARING_FRIEND_IDS }, (_, i) => `id-${i}`);
    expect(parseSharingFriendIds([...max, ...max])).toHaveLength(MAX_SHARING_FRIEND_IDS);
    expect(() => parseSharingFriendIds([...max, "un-de-trop"])).toThrow(ValidationError);
  });

  it("coordonnées d'une publication : les deux ou aucune, dans les bornes", () => {
    expect(parseOptionalCoordinates({})).toBeNull();
    expect(parseOptionalCoordinates({ latitude: null, longitude: null })).toBeNull();
    expect(() => parseOptionalCoordinates({ latitude: 45, longitude: null })).toThrow(ValidationError);
    expect(parseOptionalCoordinates({ latitude: -90, longitude: 180 })).toEqual({ latitude: -90, longitude: 180 });
    expect(() => parseOptionalCoordinates({ latitude: 45 })).toThrow(ValidationError);
    expect(() => parseOptionalCoordinates({ longitude: 6 })).toThrow(ValidationError);
    expect(() => parseOptionalCoordinates({ latitude: 90.5, longitude: 6 })).toThrow(ValidationError);
    expect(() => parseOptionalCoordinates({ latitude: 45, longitude: -180.1 })).toThrow(ValidationError);
    expect(() => parseOptionalCoordinates({ latitude: Number.NaN, longitude: 6 })).toThrow(ValidationError);
  });

  it("position d'une story arrondie à 3 décimales, ignorée en mode fantôme", () => {
    const precise = { latitude: 45.899247, longitude: 6.129384 };
    expect(storyCoordinates(precise, "ALL_FRIENDS")).toEqual({ latitude: 45.899, longitude: 6.129 });
    expect(storyCoordinates({ latitude: -33.86885, longitude: -0.0004 }, "ONLY_FRIENDS")).toEqual({ latitude: -33.869, longitude: 0 });
    expect(storyCoordinates(precise, "GHOST")).toBeNull();
    expect(storyCoordinates(null, "ALL_FRIENDS")).toBeNull();
  });
});
