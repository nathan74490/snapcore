import { Body, Controller, Get, HttpCode, Put } from "@nestjs/common";
import type { FriendLocation, LocationSharingMode, LocationSharingSettings } from "@snapcore/types";
import { IsArray, IsIn, IsNumber, IsString, Max, Min } from "class-validator";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser } from "../../identity/interface/http/auth.decorators";
import { LocationUseCases } from "../application/location-use-cases";
import { LOCATION_SHARING_MODES } from "../domain/location-sharing";

class UpdateLocationDto {
  @IsNumber()
  @Min(-90)
  @Max(90)
  latitude: number;

  @IsNumber()
  @Min(-180)
  @Max(180)
  longitude: number;
}

// friendIds : dédupliqués, 2000 au plus, filtrés en silence aux amis actuels par le cas d'usage.
class UpdateLocationSharingDto {
  @IsIn(LOCATION_SHARING_MODES)
  mode: LocationSharingMode;

  @IsArray()
  @IsString({ each: true })
  friendIds: string[];
}

// Snap Map (ADR-031). Pas de temps réel : l'app recharge la carte périodiquement.
@Controller()
export class LocationController {
  constructor(private readonly location: LocationUseCases) {}

  // Toujours 204 ; en mode fantôme, rien n'est stocké.
  @Put("me/location")
  @HttpCode(204)
  updateLocation(@CurrentUser() me: UserAccount, @Body() body: UpdateLocationDto): Promise<void> {
    return this.location.updateLocation(me, body);
  }

  @Get("me/location-sharing")
  settings(@CurrentUser() me: UserAccount): Promise<LocationSharingSettings> {
    return this.location.settings(me);
  }

  @Put("me/location-sharing")
  updateSettings(@CurrentUser() me: UserAccount, @Body() body: UpdateLocationSharingDto): Promise<LocationSharingSettings> {
    return this.location.updateSettings(me, { mode: body.mode, friendIds: body.friendIds });
  }

  @Get("map/friends")
  friends(@CurrentUser() me: UserAccount): Promise<FriendLocation[]> {
    return this.location.friendsOnMap(me);
  }
}
