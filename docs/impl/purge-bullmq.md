# Note d'implémentation : Job BullMQ de purge des médias (ADR-017)

Ce document couvre la mécanique BullMQ concrète qui met en œuvre les décisions d'ADR-017 (identifiants déterministes, jamais de suppression/réajout naïf, le balayage de rattrapage passe toujours par le même job normal). Contrairement à l'ADR, ce document est attendu comme changeant avec les versions de BullMQ effectivement installées, à revérifier avant implémentation et à chaque montée de version majeure, plutôt que de figer des noms d'API dans une décision d'architecture.

## Vérifié sur bullmq 5.81.5 (implémentation initiale)
Chaque point de ce document est couvert par `apps/backend/src/modules/purge/infrastructure/purge-bullmq.int-spec.ts`, qui tourne contre un vrai Redis : à relancer après toute montée de version (`npm run test:integration -w @snapcore/backend`).
- Un `jobId` contenant `:` est refusé (`Custom Id cannot contain :`) : le séparateur `-` est bien nécessaire.
- `changeDelay` prend un délai relatif et lève `JobNotInState` si le job n'est pas `delayed` : l'adaptateur vérifie l'état avant l'appel.
- Un `queue.add` sur l'identifiant d'un job en échec conservé est bien ignoré sans erreur ; `job.retry()` le remet en `waiting`.
- **Garde-fou ajouté à l'implémentation** : le handler relit `scheduledPurgeAt` en base et, s'il est encore dans le futur (job réajouté par le balayage ou exécuté en avance), re-diffère le job à cette date au lieu de purger. La base reste ainsi la seule source de vérité de l'échéance, quel que soit l'état de la file.
- Le balayage horaire est un *job scheduler* (`queue.upsertJobScheduler("purge-sweep", { every: 3600000 })`), idempotent : un redémarrage ne crée pas de second balayage.

## Identifiants de job
`purge-message-<messageId>` / `purge-story-<storyItemId>` / `purge-group-message-<groupMessageId>` (snaps de groupe, ADR-032) (séparateur `-`, pas `:`, car certaines versions récentes de BullMQ utilisent `:` en interne pour construire leurs clés Redis et pourraient refuser un `jobId` personnalisé qui en contient ; à confirmer dans la doc de la version installée avant implémentation), jamais un identifiant aléatoire. Ajouter un job dont l'identifiant existe déjà dans la file est silencieusement ignoré par BullMQ (aucune erreur), y compris si le job existant est déjà terminé ou en échec mais toujours conservé dans Redis, d'où les points ci-dessous.

**Attention à la signature de `queue.add`** : le premier argument est le **nom** du job (une catégorie, ex. `'purge'`), pas son identifiant. L'identifiant déterministe se passe dans les options, via `jobId` :

```
queue.add('purge', data, { jobId: id, delay })
```

Écrire `queue.add(id, data)` (en confondant nom et identifiant) fait que chaque ajout reçoit un identifiant **aléatoire** généré par BullMQ, puisque `jobId` n'a jamais été renseigné : le mécanisme d'identifiants déterministes ne s'applique alors nulle part, sans qu'aucune erreur ne le signale. Cette règle s'applique à **tous** les appels `queue.add` de ce document, y compris la création initiale des jobs de purge (message et story), pas seulement au balayage de rattrapage ci-dessous.

## Reprogrammer un job selon son état
- **Job en attente** (reprogrammation à l'ouverture) : `job.changeDelay(nouveauDélaiEnMs)`. **`changeDelay` prend un délai relatif à maintenant, pas une date absolue** ; calculer `échéance.getTime() - Date.now()` avant l'appel, pas passer directement `scheduledPurgeAt`.
- **Job en cours d'exécution** (reprogrammation depuis l'intérieur du job lui-même, branche abonnement actif) : on ne peut ni le supprimer ni en ajouter un autre sous le même identifiant pendant qu'il tourne. Utiliser `job.moveToDelayed(timestampAbsolu, token)` (celui-ci prend bien un timestamp absolu, à la différence de `changeDelay`) puis lever une `DelayedError` pour signaler à BullMQ que ce traitement se termine ici et reprendra plus tard.

## Rétention des jobs terminés et en échec
- `removeOnComplete: true` : un job de purge réussi est retiré de Redis plutôt que conservé indéfiniment, pour que son identifiant redevienne libre. Sans cela, le balayage de rattrapage (voir plus bas) ne pourrait jamais réajouter un job sous un identifiant occupé par une exécution déjà terminée.
- `removeOnFail` avec un nombre borné (ex. conserver les 50 derniers échecs) : garde un historique de diagnostic sans polluer indéfiniment la file.

## Le balayage de rattrapage doit récupérer le job existant, pas en ajouter un nouveau
Le piège symétrique du point précédent : si un job a échoué et reste conservé dans Redis (`removeOnFail` avec rétention), son identifiant reste occupé. Un ajout aveugle depuis le balayage serait alors ignoré sans effet, précisément dans le cas où le balayage devrait agir (ex. R2 était indisponible au moment de la purge). Pour chaque `Message`/`StoryItem` en retard trouvé par le scan, le balayage doit :
1. `const job = await queue.getJob(id)` ;
2. si `job` existe et est en échec (`await job.isFailed()`), appeler `await job.retry()` plutôt que `queue.add(...)` ;
3. si `job` n'existe pas (perdu malgré l'AOF), l'ajouter normalement avec `queue.add('purge', data, { jobId: id, delay: 0 })` (nom du job en premier argument, identifiant dans `jobId`, voir la remarque ci-dessus) ;
4. **dans tout autre état** (le job existe et est en attente, différé, ou en cours d'exécution) : **ne rien faire**. Le job normal va s'exécuter ou reprendra son cours par lui-même ; un `add` "par sécurité" dans ce cas serait de toute façon ignoré (identifiant déjà occupé), mais l'écrire laisserait croire à tort que le balayage agit alors qu'il ne fait qu'une vérification qui ne trouve rien à faire, ce qui est le comportement attendu la plupart du temps.

## Résumé du principe (celui-là stable, indépendant de la version BullMQ)
Jamais de "supprimer puis réajouter" naïf sur un identifiant déterministe : la bonne opération dépend de l'état du job (en attente, en cours d'exécution, terminé, en échec, absent), et BullMQ expose une API différente pour chacun. Un ajout aveugle (`queue.add`) est correct uniquement dans le cas "absent".
