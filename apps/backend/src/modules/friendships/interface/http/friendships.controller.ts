import { Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Post, Query } from "@nestjs/common";
import type { ContactMatch, FriendshipListItem, UserSearchResult } from "@snapcore/types";
import { ArrayMaxSize, ArrayMinSize, IsArray, IsString, MaxLength } from "class-validator";
import type { UserAccount } from "../../../identity/domain/user";
import { CurrentUser } from "../../../identity/interface/http/auth.decorators";
import { FriendshipUseCases } from "../../application/friendship-use-cases";

class UsernameDto {
  @IsString()
  @MaxLength(64)
  username: string;
}

// Numéros bruts du carnet d'adresses, tels qu'affichés par le téléphone (ADR-029).
class MatchContactsDto {
  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(2000)
  @IsString({ each: true })
  @MaxLength(32, { each: true })
  phoneNumbers: string[];
}

@Controller()
export class FriendshipsController {
  constructor(private readonly friendships: FriendshipUseCases) {}

  // Recherche par pseudo exact, seul moyen d'ajouter un ami en V1 (ADR-012).
  @Get("users/search")
  search(@CurrentUser() me: UserAccount, @Query() query: UsernameDto): Promise<UserSearchResult> {
    return this.friendships.searchByUsername(me, query.username);
  }

  // Amis à ajouter depuis les contacts du téléphone, pendant l'onboarding (ADR-029).
  @Post("users/contacts/match")
  @HttpCode(200)
  matchContacts(@CurrentUser() me: UserAccount, @Body() body: MatchContactsDto): Promise<ContactMatch[]> {
    return this.friendships.matchContacts(me, body.phoneNumbers);
  }

  @Get("friendships")
  listFriends(@CurrentUser() me: UserAccount): Promise<FriendshipListItem[]> {
    return this.friendships.listFriends(me);
  }

  @Get("friendships/requests/incoming")
  listIncoming(@CurrentUser() me: UserAccount): Promise<FriendshipListItem[]> {
    return this.friendships.listIncomingRequests(me);
  }

  @Get("friendships/requests/outgoing")
  listOutgoing(@CurrentUser() me: UserAccount): Promise<FriendshipListItem[]> {
    return this.friendships.listOutgoingRequests(me);
  }

  @Get("friendships/blocked")
  listBlocked(@CurrentUser() me: UserAccount): Promise<FriendshipListItem[]> {
    return this.friendships.listBlocked(me);
  }

  @Post("friendships/requests")
  sendRequest(@CurrentUser() me: UserAccount, @Body() body: UsernameDto): Promise<FriendshipListItem> {
    return this.friendships.sendRequest(me, body.username);
  }

  @Post("friendships/requests/:friendshipId/accept")
  @HttpCode(200)
  accept(
    @CurrentUser() me: UserAccount,
    @Param("friendshipId", ParseUUIDPipe) friendshipId: string,
  ): Promise<FriendshipListItem> {
    return this.friendships.accept(me, friendshipId);
  }

  @Post("friendships/requests/:friendshipId/decline")
  @HttpCode(204)
  decline(@CurrentUser() me: UserAccount, @Param("friendshipId", ParseUUIDPipe) friendshipId: string): Promise<void> {
    return this.friendships.decline(me, friendshipId);
  }

  @Delete("friendships/:userId")
  @HttpCode(204)
  removeFriend(@CurrentUser() me: UserAccount, @Param("userId", ParseUUIDPipe) userId: string): Promise<void> {
    return this.friendships.removeFriend(me, userId);
  }

  @Post("blocks/:userId")
  @HttpCode(204)
  block(@CurrentUser() me: UserAccount, @Param("userId", ParseUUIDPipe) userId: string): Promise<void> {
    return this.friendships.block(me, userId);
  }

  @Delete("blocks/:userId")
  @HttpCode(204)
  unblock(@CurrentUser() me: UserAccount, @Param("userId", ParseUUIDPipe) userId: string): Promise<void> {
    return this.friendships.unblock(me, userId);
  }
}
