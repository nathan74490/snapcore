import { ConflictError } from "../../../../shared/domain/errors";
import { FixedClock } from "../../../../test/fakes";
import { InMemoryObjectStorage, InMemoryUploadTicketStore, silentLogger } from "../../../../test/in-memory-media";
import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import { RequestUpload } from "../../../media/application/request-upload";
import { UploadPromoter } from "../../../media/application/upload-promoter";
import { PublicMediaUrls } from "../../../media/domain/public-media-urls";
import type { UserAccount } from "../../domain/user";
import { ProfilePhotoUseCases } from "./profile-photo";

describe("ProfilePhotoUseCases", () => {
  const urls = new PublicMediaUrls("https://cdn.snapcore.test/");
  let users: InMemoryUserRepository;
  let storage: InMemoryObjectStorage;
  let requestUpload: RequestUpload;
  let profilePhoto: ProfilePhotoUseCases;
  let alice: UserAccount;

  beforeEach(async () => {
    users = new InMemoryUserRepository();
    storage = new InMemoryObjectStorage();
    const tickets = new InMemoryUploadTicketStore();
    requestUpload = new RequestUpload(storage, tickets, new FixedClock());
    profilePhoto = new ProfilePhotoUseCases(users, new UploadPromoter(storage, tickets, silentLogger), storage, urls, silentLogger);
    alice = await users.seed("alice");
  });

  const uploadPhoto = async () => {
    const { uploadId } = await requestUpload.execute(alice, { purpose: "PROFILE_PHOTO", contentType: "image/jpeg", contentLength: 50_000 });
    storage.simulateUpload(`tmp/${uploadId}`, 50_000, "image/jpeg");
    return uploadId;
  };

  const publicKeyOf = (user: UserAccount) => urls.keyFromUrl(user.profilePhotoUrl!)!;

  it("place la photo dans le bucket public, sous une clé unique, et stocke son URL", async () => {
    const updated = await profilePhoto.change(alice, await uploadPhoto());

    expect(updated.profilePhotoUrl).toMatch(new RegExp(`^https://cdn\\.snapcore\\.test/profiles/${alice.id}/[0-9a-f-]{36}$`));
    expect(storage.has({ area: "public", key: publicKeyOf(updated) })).toBe(true);
  });

  it("remplacer la photo supprime l'ancienne image, déduite de son URL", async () => {
    const first = await profilePhoto.change(alice, await uploadPhoto());
    const second = await profilePhoto.change(first, await uploadPhoto());

    expect(second.profilePhotoUrl).not.toBe(first.profilePhotoUrl);
    expect(storage.has({ area: "public", key: publicKeyOf(first) })).toBe(false);
    expect(storage.has({ area: "public", key: publicKeyOf(second) })).toBe(true);
  });

  it("si la photo a changé entre-temps (requête concurrente), refuse et supprime la nouvelle copie", async () => {
    const stale = alice; // profil lu avant qu'une autre requête ne change la photo
    await profilePhoto.change(alice, await uploadPhoto());
    const publicBefore = [...storage.objects.keys()].filter((k) => k.startsWith("public:"));

    await expect(profilePhoto.change(stale, await uploadPhoto())).rejects.toThrow(ConflictError);

    expect([...storage.objects.keys()].filter((k) => k.startsWith("public:"))).toEqual(publicBefore);
  });

  it("retirer la photo vide l'URL et supprime l'image", async () => {
    const withPhoto = await profilePhoto.change(alice, await uploadPhoto());

    const updated = await profilePhoto.remove(withPhoto);

    expect(updated.profilePhotoUrl).toBeNull();
    expect(storage.has({ area: "public", key: publicKeyOf(withPhoto) })).toBe(false);
  });
});
