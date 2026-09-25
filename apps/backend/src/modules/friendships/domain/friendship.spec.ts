import { allowsMessaging, planBlock, planFriendRequest, relationFor, sortedPair, type Friendship } from "./friendship";

const base: Friendship = {
  id: "f1",
  requesterId: "a",
  addresseeId: "b",
  status: "PENDING",
  blockedBy: null,
  requestedAt: new Date(),
  respondedAt: null,
};

describe("friendship (domaine)", () => {
  it("trie la paire indépendamment du sens", () => {
    expect(sortedPair("b", "a")).toEqual({ pairLow: "a", pairHigh: "b" });
    expect(sortedPair("a", "b")).toEqual({ pairLow: "a", pairHigh: "b" });
  });

  it("n'autorise la messagerie que pour une paire ACCEPTED", () => {
    expect(allowsMessaging(null)).toBe(false);
    expect(allowsMessaging({ ...base, status: "PENDING" })).toBe(false);
    expect(allowsMessaging({ ...base, status: "DECLINED" })).toBe(false);
    expect(allowsMessaging({ ...base, status: "BLOCKED", blockedBy: "a" })).toBe(false);
    expect(allowsMessaging({ ...base, status: "ACCEPTED" })).toBe(true);
  });

  it.each([
    [null, "a", "create"],
    [{ ...base, status: "PENDING" as const }, "a", "already-pending"],
    [{ ...base, status: "PENDING" as const }, "b", "accept-incoming"],
    [{ ...base, status: "ACCEPTED" as const }, "a", "already-friends"],
    [{ ...base, status: "DECLINED" as const }, "b", "reopen-declined"],
    [{ ...base, status: "BLOCKED" as const, blockedBy: "b" }, "a", "blocked"],
  ])("planFriendRequest(%o, %s) → %s", (existing, requester, expected) => {
    expect(planFriendRequest(existing, requester).kind).toBe(expected);
  });

  it("un blocage sur une paire déjà BLOCKED est un no-op, quel que soit le bloqueur", () => {
    expect(planBlock({ ...base, status: "BLOCKED", blockedBy: "a" }).kind).toBe("noop");
    expect(planBlock({ ...base, status: "ACCEPTED" }).kind).toBe("block-existing");
    expect(planBlock(null).kind).toBe("create");
  });

  it("ne révèle jamais un refus dans la relation affichée", () => {
    expect(relationFor({ ...base, status: "DECLINED" }, "a")).toBe("NONE");
    expect(relationFor({ ...base, status: "PENDING" }, "a")).toBe("OUTGOING_REQUEST");
    expect(relationFor({ ...base, status: "PENDING" }, "b")).toBe("INCOMING_REQUEST");
  });
});
