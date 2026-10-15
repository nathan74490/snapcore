import { Controller, Delete, HttpCode } from "@nestjs/common";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser } from "../../identity/interface/http/auth.decorators";
import { AccountDeletionUseCases } from "../application/account-deletion-use-cases";

@Controller("me")
export class AccountDeletionController {
  constructor(private readonly deletion: AccountDeletionUseCases) {}

  // 202 : le compte est bloqué immédiatement, la suppression se termine en arrière-plan.
  // L'app doit ensuite appeler FirebaseAuth.signOut() (ADR-015).
  @Delete()
  @HttpCode(202)
  requestDeletion(@CurrentUser() me: UserAccount): Promise<void> {
    return this.deletion.request(me);
  }
}
