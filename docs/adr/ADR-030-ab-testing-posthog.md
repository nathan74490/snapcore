# ADR-030 : A/B testing avec les expériences PostHog, premier test sur le bouton du paywall SnapCore+

## Statut
Accepté

## Contexte
SnapCore mesure déjà son usage avec PostHog (ADR-008), côté app et côté backend, sous l'identifiant interne du compte. ADR-008 avait retenu PostHog notamment pour ses feature flags, sans les utiliser. On veut maintenant comparer deux versions d'un même écran sur de vrais parcours, plutôt que de trancher au jugé.

Un A/B test demande quatre choses :
- une **attribution stable** : un utilisateur voit toujours la même variante, sur tous ses appareils ;
- un **événement d'exposition** au moment où la variante devient visible ;
- des **événements de conversion** rattachés à la variante ;
- une **analyse statistique** des résultats.

## Décision
**Outil** : les *Experiments* de PostHog. Chaque expérience est un feature flag à plusieurs variantes ; PostHog répartit les utilisateurs, calcule les résultats et leur significativité. Pas d'outil supplémentaire ni de code d'attribution maison.

**Côté app** : un port de domaine `Experiments.variant(flag)`.
- L'adaptateur `PostHogExperiments` lit le flag chargé par le SDK, au démarrage puis à l'identification du compte. Cette lecture envoie l'événement d'exposition `$feature_flag_called`.
- **On n'appelle `variant` qu'au moment où la variante s'affiche**, et une seule fois par affichage de l'écran : un utilisateur qui ne voit pas l'élément testé n'entre pas dans l'expérience.
- Sans clé PostHog, ou si la variante est inconnue (flags pas encore chargés, expérience arrêtée), l'app affiche toujours le **témoin** (`NoExperiments`), et l'écran ne casse jamais.
- Les événements de conversion envoyés par l'app portent la propriété `$feature/<flag>` avec la variante vue.

**Attribution** : la variante dépend de l'identifiant du compte (ADR-008), donc reste la même sur les deux téléphones d'un utilisateur. Avant connexion, aucune expérience n'est lue.

**Côté backend** : rien pour l'instant. Une expérience qui changerait une règle serveur passera par un port équivalent au-dessus de `posthog-node`, décidé avec elle. Les événements serveur existants servent déjà de conversions secondaires, PostHog les rattachant au même compte.

**Expérience n° 1, `paywall-cta`** : libellé du bouton d'abonnement du paywall SnapCore+ (ADR-025).
- `control` : « S'abonner pour 3,99 € / mois » (inchangé).
- `benefit` : « Revoir mes snaps à volonté », qui met en avant le premier avantage. Le prix reste affiché juste au-dessus, identique dans les deux variantes.
- **Exposition** : à l'affichage du bouton, donc pour les comptes non abonnés uniquement.
- **Mesure principale** : `checkout_started` (lien de paiement ouvert). Mesure secondaire : `snapcoreplus_started` (abonnement payé, envoyé par le backend).
- **Répartition** : 50 / 50. L'expérience se lance depuis le tableau de bord PostHog : Experiments, puis New experiment, clé de flag `paywall-cta`, variantes `control` et `benefit`.

**Expériences suivantes (révision 2)** : une par grande partie de l'app, toutes sur le même modèle.
- **Témoin** : l'interface actuelle, inchangée.
- **Une seule différence** : un texte ou un libellé, pour que le résultat s'explique.
- **Lecture** par `Experiments.expose(flag)` au moment de l'affichage, une seule fois.

| Flag | Écran | Variantes | Exposition | Mesure principale |
|---|---|---|---|---|
| `onboarding-contacts-copy` | Premiers pas, « Trouve tous tes amis » (ADR-029) | `control` : explication du fonctionnement ; `social` : « Tes amis t'attendent », bouton « Retrouver mes amis » | À l'arrivée sur l'étape | `contacts_permission_answered` avec `granted = true` |
| `preview-send-label` | Aperçu d'un snap | `control` : « Envoyer à » ; `short` : « Envoyer » | À l'ouverture de l'aperçu | `snap_preview_confirmed` ; secondaire : `snap_discarded` à la baisse |
| `map-ghost-banner` | Snap Map, bandeau du mode fantôme (ADR-031) | `control` : « Partager ma position » ; `friends` : « Me montrer à mes amis » | Quand le bandeau s'affiche, une fois par ouverture de la carte | `location_sharing_changed` (backend), mode autre que GHOST |
| `profile-plus-card` | Profil, carte SnapCore+ d'un compte gratuit (ADR-025) | `control` : avantages seuls ; `price` : avantages et prix | Affichage de la carte (comptes gratuits) | `paywall_opened` avec `source = profile_card` ; secondaire : `checkout_started` |

Garde-fous :
- `map-ghost-banner` ne change que le libellé. Le choix de l'audience reste dans la même fenêtre de confirmation, et le mode fantôme reste le réglage par défaut.
- Aucune variante ne coche une option à la place de l'utilisateur, par exemple « Ma story » à l'envoi : une publication par erreur coûterait plus que ce que le test rapporterait.
- Une conversion envoyée par le backend ne porte pas de propriété `$feature/<flag>` : PostHog la rattache à la variante d'après l'exposition enregistrée pour la même personne.

Chaque expérience se crée dans PostHog avec la clé de flag ci-dessus, deux variantes à 50 / 50 et sa mesure principale. Tant qu'elle n'existe pas, tout le monde voit le témoin.

## Alternatives
- **Attribution maison** (hachage de l'id du compte modulo 2, côté app ou backend) : aucune dépendance, mais il faudrait réécrire l'exposition, le suivi des conversions et surtout l'analyse statistique, que PostHog fournit déjà. Écarté.
- **Firebase Remote Config + A/B Testing** : gratuit et déjà dans le projet via FCM, mais l'analyse repose sur Google Analytics for Firebase, que le projet n'utilise pas (ADR-008). Les données seraient alors coupées en deux outils. Écarté.
- **Décider la variante côté backend et la renvoyer dans `GET /me`** : une seule source de vérité, mais une route à faire évoluer pour chaque expérience, et l'exposition au moment de l'affichage resterait à envoyer depuis l'app. Écarté pour des tests d'interface ; à reconsidérer pour un test de règle métier.

## Conséquences
### Positives
- Ajouter une expérience coûte un flag dans PostHog, une valeur lue dans le ViewModel concerné et une propriété sur l'événement de conversion.
- Les résultats et leur significativité sont calculés par PostHog, à côté des funnels existants.
- L'app reste utilisable et testable sans PostHog (tests unitaires avec une doublure qui garde la trace des expositions).

### Négatives
- Les flags sont chargés au démarrage : à la toute première ouverture du paywall, s'ils ne sont pas encore arrivés, l'utilisateur voit le témoin, et la lecture enregistre une exposition sans variante, que PostHog ignore. Ce cas reste rare et ne fausse pas la comparaison.
- Les petits volumes du projet ne permettront pas d'atteindre la significativité statistique : l'expérience valide surtout la chaîne complète (attribution, exposition, conversion, analyse). Elle n'apportera pas de conclusion commerciale.
- Une variante affichée dépend d'un service tiers. En cas d'indisponibilité de PostHog, tout le monde revient au témoin.

## Liens
- ADR liés : ADR-008, ADR-025, ADR-029, ADR-031
