# Animations Android — références vidéo du 16 octobre 2026

Périmètre : gestes et transitions des fonctions existantes, selon les trois vidéos Snapchat fournies. Les données, la messagerie, les stories et Camera Kit restent pilotés par leurs services existants.

| Référence | Mouvement observé | Intégration |
| --- | --- | --- |
| `091957` | Chat et Stories passent toujours au-dessus de la caméra, à l'aller comme au retour | Caméra fixée dans sa couche graphique et toujours placée dessous. Les panneaux Chat et Stories restent au-dessus et suivent le doigt pour recouvrir ou découvrir la caméra. |
| `084144` | Glissement horizontal caméra ↔ chat / stories ; barre du bas fixe | `HomeScreen` utilise `HorizontalPager`, pages adjacentes conservées, sélection synchronisée après stabilisation. Appui sur un onglet : 280 ms, `FastOutSlowInEasing`. |
| `084207` | Conversation qui entre depuis la droite, clavier après l'ouverture, retour vers la liste | Transitions de navigation de 280 ms, décalage de la liste de 25 %. Focus du champ lorsque la destination passe à `RESUMED`. Clavier et barre de saisie suivent les insets IME Android. |
| `084207` | Retour interactif d'une conversation | `SwipeBackPane` suit le doigt et révèle une capture locale de l'accueil avec parallaxe. Retour si déplacement > 28 % de la largeur, ou geste rapide > 700 dp/s après 24 dp. Sinon rappel par ressort. Confirmation : 180 ms. |
| `084235` | Tirage vers le bas, fantôme qui fait un clin d’œil et tire la langue, puis envol avec jet arc-en-ciel | `SnapRefresh` conserve le seuil natif de 80 dp. Personnage vectoriel avec mains, visage et corps ondulé. Au relâchement validé : bouche ouverte, six bandes colorées et envol de 360 ms ; remontée de la liste en 280 ms après 80 ms. Un tirage court revient sans envol. Actualisation réelle des services. |

La caméra possède un cycle de vie borné à la visibilité de sa page : elle est arrêtée hors écran, puis relancée à l'entrée. Les gestes horizontaux du pager sont suspendus pendant le mode Lens pour laisser le carrousel fonctionner. Le retour interactif est suspendu pendant l'affichage d'un snap.

La capture de fond du chat reste uniquement en mémoire, puis est libérée à la fermeture de la conversation. Elle n'est ni enregistrée ni transmise par l'application.

## Vérification sur appareil

Galaxy A54 (SM-A546B), Android 16, résolution 1080 × 2340, réglage de police utilisateur conservé à 1,1.

- Navigation par balayage dans les deux sens et par appui sur les onglets.
- Actualisations successives des stories et du chat : bande jaune, déplacement continu, fantôme et retour complet en place.
- Conversation : ouverture, clavier, déplacement court annulé, retour complet, réouverture, fermeture du clavier et retour par la flèche.
- Caméra : aperçu, changement de caméra, capture locale, abandon de l'aperçu ; aucun média envoyé.
- Les 15 contrôles de parcours ont réussi. Enregistrement ADB relu avec extraction des images intermédiaires.
- `:app:testDebugUnitTest` : 61 tests, aucun échec.
- `:app:lintDebug` : réussi ; avertissements préexistants hors périmètre conservés.

Depuis `apps/mobile`, avec le JBR d'Android Studio dans `JAVA_HOME` :

```powershell
.\gradlew.bat :app:installDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

## Caméra fixe sous Chat et Stories

La référence Screen_Recording_20261016_091957_Snapchat.mp4 précise l’ordre permanent des couches, indépendant du sens du geste : caméra en dessous, Chat et Stories au-dessus. Seuls les panneaux latéraux se déplacent.

## Fantôme personnalisé et envol

Révision du 16 octobre : remplacement du pictogramme par un personnage vectoriel expressif, dessiné à partir de la vidéo 084235. Le clin d’œil et la langue sont visibles pendant le tirage ; au relâchement, les deux yeux s’ouvrent et un jet arc-en-ciel sort de la bouche. La trajectoire accompagne la remontée de la liste pour éviter que celle-ci masque le personnage avant son envol.

Compilation et installation réussies sur le Galaxy A54. Vérification par enregistrement ADB et extraction des images intermédiaires : deux actualisations du Chat, deux des Stories, tirage court annulé, disparition complète du personnage et de la bande jaune. Aucun message AndroidRuntime d’erreur pendant le parcours. Les tests unitaires et le lint mentionnés plus haut concernent la révision précédente ; cette retouche visuelle a été vérifiée sur appareil.
