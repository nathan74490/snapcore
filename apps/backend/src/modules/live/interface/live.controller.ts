import { Controller, HttpCode, Post } from "@nestjs/common";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser } from "../../identity/interface/http/auth.decorators";
import { IssueParticipantToken } from "../application/issue-participant-token";

// SDK Live commun (ADR-035) : l'app appelle cette route à chaque connexion au serveur Live.
@Controller("live")
export class LiveController {
  constructor(private readonly issueToken: IssueParticipantToken) {}

  // 503 tant que LIVE_SDK_SECRET n'est pas renseigné.
  @Post("participant-token")
  @HttpCode(200)
  participantToken(@CurrentUser() me: UserAccount): { token: string } {
    return { token: this.issueToken.execute(me) };
  }
}
