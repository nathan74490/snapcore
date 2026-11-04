import type { GroupSummary, GroupView } from "@snapcore/types";
import { randomUUID } from "node:crypto";
import { noAnalytics, type Analytics } from "../../../shared/domain/analytics";
import type { Clock } from "../../../shared/domain/clock";
import { ConflictError, ForbiddenError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import type { AppLogger } from "../../../shared/domain/logger";
import type { FriendshipRepository } from "../../friendships/application/ports/friendship.repository";
import type { UserAccount } from "../../identity/domain/user";
import type { ObjectStorage } from "../../media/application/ports/object-storage.port";
import {
  MAX_GROUP_MEMBERS,
  MAX_INVITED_FRIENDS,
  MIN_INVITED_FRIENDS,
  parseGroupName,
  type Group,
} from "../domain/group";
import { GROUP_NOT_FOUND, presentGroup, presentGroupMessage, requireMember } from "./group-presenter";
import type { GroupSnapPurge } from "./group-snap-purge";
import type { GroupNotifier, GroupRepository } from "./ports";

// Même réponse que l'utilisateur n'existe pas, soit suspendu, ne soit pas (ou plus) un ami, ou m'ait
// bloqué : on ne révèle pas un blocage (ADR-012).
export const ONLY_FRIENDS = "Tu ne peux ajouter que tes amis à un groupe";
const GROUP_FULL = `Un groupe compte ${MAX_GROUP_MEMBERS} membres au plus`;

// Groupes de discussion (ADR-032) : création, liste, détail, renommage, ajout de membres, départ.
// Pas d'administrateur : chaque membre a les mêmes droits.
export class GroupUseCases {
  constructor(
    private readonly groups: GroupRepository,
    private readonly friendships: FriendshipRepository,
    private readonly storage: ObjectStorage,
    private readonly purge: GroupSnapPurge,
    private readonly notifier: GroupNotifier,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  async create(me: UserAccount, input: { name?: string | null; memberIds: string[] }): Promise<GroupView> {
    const name = parseGroupName(input.name);
    const invited = distinctOthers(input.memberIds, me.id);
    if (invited.length < MIN_INVITED_FRIENDS) {
      throw new ValidationError(`Choisis au moins ${MIN_INVITED_FRIENDS} amis pour créer un groupe`);
    }
    if (invited.length > MAX_INVITED_FRIENDS) throw new ValidationError(GROUP_FULL);
    await this.requireFriends(me, invited);

    const group: Group = { id: randomUUID(), name, createdById: me.id, createdAt: this.clock.now() };
    const memberIds = [me.id, ...invited];
    await this.groups.create(group, memberIds);

    await this.notify(() => this.notifier.updated(memberIds, group.id));
    this.analytics.capture(me.id, "group_created", { members: memberIds.length });
    return this.view(group);
  }

  // Mes groupes, triés par dernier message (à défaut, par date d'arrivée dans le groupe).
  async list(me: UserAccount): Promise<GroupSummary[]> {
    const memberships = await this.groups.listMembershipsOf(me.id);
    if (memberships.length === 0) return [];
    const ids = memberships.map((m) => m.group.id);
    const [members, formerMembers, lastMessages, unopenedSnaps, unread] = await Promise.all([
      this.groups.listActiveMembers(ids),
      this.groups.listFormerSenders(ids),
      this.groups.findLastMessages(me.id, ids),
      this.groups.findOldestUnopenedSnaps(me.id, ids),
      this.groups.findGroupsWithUnread(me.id, ids),
    ]);
    const shown = [...lastMessages.values(), ...unopenedSnaps.values()];
    const openings = await this.groups.listOpenings(
      [...new Set(shown.filter((m) => m.contentType !== "TEXT").map((m) => m.id))],
    );

    return memberships
      .map(({ group, member }) => {
        const groupMembers = members.get(group.id) ?? [];
        const last = lastMessages.get(group.id);
        const unopened = unopenedSnaps.get(group.id);
        const summary: GroupSummary = {
          group: presentGroup(group, groupMembers, formerMembers.get(group.id) ?? []),
          ...(last && { lastMessage: presentGroupMessage(last, member, groupMembers, openings) }),
          ...(unopened && { unopenedSnap: presentGroupMessage(unopened, member, groupMembers, openings) }),
          unread: unread.has(group.id),
        };
        return { summary, sortKey: (last?.createdAt ?? member.joinedAt).getTime() };
      })
      .sort((a, b) => b.sortKey - a.sortKey || a.summary.group.id.localeCompare(b.summary.group.id))
      .map(({ summary }) => summary);
  }

  async get(me: UserAccount, groupId: string): Promise<GroupView> {
    await requireMember(this.groups, groupId, me.id);
    const group = await this.groups.findById(groupId);
    if (!group) throw new NotFoundError(GROUP_NOT_FOUND);
    return this.view(group);
  }

  // N'importe quel membre renomme ; un nom vide retire le nom.
  async rename(me: UserAccount, groupId: string, rawName: string | null): Promise<GroupView> {
    const name = parseGroupName(rawName);
    await requireMember(this.groups, groupId, me.id);
    const group = await this.groups.rename(groupId, name);
    if (!group) throw new NotFoundError(GROUP_NOT_FOUND);

    await this.notifyMembers(groupId);
    this.analytics.capture(me.id, "group_renamed");
    return this.view(group);
  }

  // N'importe quel membre ajoute SES amis ACCEPTED ; ceux qui sont déjà membres sont ignorés.
  // Un nouveau membre ne voit que les messages envoyés depuis son arrivée.
  async addMembers(me: UserAccount, groupId: string, userIds: string[]): Promise<GroupView> {
    await requireMember(this.groups, groupId, me.id);
    const candidates = distinctOthers(userIds, me.id);
    if (candidates.length === 0) throw new ValidationError("Choisis au moins un ami à ajouter");
    // Ceux qui sont déjà membres sont ignorés, qu'ils soient mes amis ou non.
    const current = new Set((await this.activeMembers(groupId)).map((m) => m.userId));
    await this.requireFriends(me, candidates.filter((id) => !current.has(id)));

    const result = await this.groups.addMembers(groupId, me.id, candidates, this.clock.now(), MAX_GROUP_MEMBERS);
    if (result.kind === "missing") throw new NotFoundError(GROUP_NOT_FOUND);
    if (result.kind === "full") throw new ConflictError(GROUP_FULL);

    if (result.userIds.length > 0) {
      await this.notifyMembers(groupId);
      this.analytics.capture(me.id, "group_members_added", { count: result.userIds.length });
    }
    return this.get(me, groupId);
  }

  // Mes messages restent, avec mon nom. Le dernier membre qui part supprime le groupe et ses médias.
  async leave(me: UserAccount, groupId: string): Promise<void> {
    await requireMember(this.groups, groupId, me.id);
    const { removed, remaining } = await this.groups.removeMember(groupId, me.id);
    if (!removed) throw new NotFoundError(GROUP_NOT_FOUND);
    this.analytics.capture(me.id, "group_left");

    if (remaining === 0) {
      await this.deleteGroup(groupId);
      return;
    }
    const members = await this.activeMembers(groupId);
    await this.notify(() => this.notifier.updated(members.map((m) => m.userId), groupId));
    // Les snaps que j'étais seul à ne pas avoir ouverts peuvent maintenant voir leur purge rapprochée.
    await this.bestEffort(`réévaluation des purges du groupe ${groupId}`, async () => {
      for (const snap of await this.groups.listSnapsWithMedia(groupId)) await this.purge.advanceIfAllOpened(snap, members);
    });
  }

  // Médias R2 d'abord : si une suppression échoue, le groupe vide reste en base (invisible de tous) et
  // les jobs de purge de ses snaps supprimeront les médias restants à leur échéance (ADR-017).
  private async deleteGroup(groupId: string): Promise<void> {
    const keys = await this.groups.listMediaKeys(groupId);
    let failures = 0;
    for (const key of keys) {
      try {
        await this.storage.delete({ area: "private", key });
      } catch (error) {
        failures++;
        this.logger.warn(`Média ${key} du groupe ${groupId} non supprimé : ${(error as Error).message}`);
      }
    }
    if (failures > 0) {
      this.logger.warn(`Groupe ${groupId} vide conservé : ${failures} média(s) laissé(s) aux jobs de purge`);
      return;
    }
    await this.groups.deleteIfEmpty(groupId);
  }

  private async view(group: Group): Promise<GroupView> {
    const [members, former] = await Promise.all([
      this.groups.listActiveMembers([group.id]),
      this.groups.listFormerSenders([group.id]),
    ]);
    return presentGroup(group, members.get(group.id) ?? [], former.get(group.id) ?? []);
  }

  private async activeMembers(groupId: string) {
    return (await this.groups.listActiveMembers([groupId])).get(groupId) ?? [];
  }

  // Amis ACCEPTED aux comptes actifs (listFriends filtre déjà les comptes suspendus ou en suppression).
  private async requireFriends(me: UserAccount, userIds: string[]): Promise<void> {
    const friends = new Set((await this.friendships.listFriends(me.id)).map((f) => f.user.id));
    if (userIds.some((id) => !friends.has(id))) throw new ForbiddenError(ONLY_FRIENDS);
  }

  private async notifyMembers(groupId: string): Promise<void> {
    await this.notify(async () => {
      const members = await this.activeMembers(groupId);
      await this.notifier.updated(members.map((m) => m.userId), groupId);
    });
  }

  private notify(action: () => Promise<void>): Promise<void> {
    return this.bestEffort("notification", action);
  }

  private async bestEffort(what: string, action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      this.logger.warn(`Échec (non bloquant) : ${what} — ${(error as Error).message}`);
    }
  }
}

function distinctOthers(ids: string[], myId: string): string[] {
  return [...new Set(ids)].filter((id) => id !== myId);
}
