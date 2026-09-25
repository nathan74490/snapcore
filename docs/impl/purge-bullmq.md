# Note d'implémentation — Job BullMQ de purge des médias (ADR-017)

Ce document couvre la mécanique BullMQ concrète qui met en œuvre les décisions d'ADR-017 (identifiants déterministes, jamais de suppression/réajout naïf, le balayage de rattrapage passe toujours par le même job normal). Contrairement à l'ADR, ce document est attendu comme changeant avec les versions de BullMQ effectivement installées — à revérifier avant implémentation et à chaque montée de version majeure, plutôt que de figer des noms d'API dans une décision d'architecture.

## Identifiants de job
`purge:message:<messageId>` / `purge:story:<storyItemId>`, jamais un identifiant aléatoire. Ajouter un job dont l'identifiant existe déjà dans la file est silencieusement ignoré par BullMQ (aucune erreur), y compris si le job existant est déjà terminé ou en échec mais toujours conservé dans Redis — d'où les points ci-dessous.

## Reprogrammer un job selon son état
- **Job en attente** (reprogrammation à l'ouverture) : `job.changeDelay(nouveauDélaiEnMs)`. **`changeDelay` prend un délai relatif à maintenant, pas une date absolue** — calculer `échéance.getTime() - Date.now()` avant l'appel, pas passer directement `scheduledPurgeAt`.
- **Job en cours d'exécution** (reprogrammation depuis l'intérieur du job lui-même, branche abonnement actif) : on ne peut ni le supprimer ni en ajouter un autre sous le même identifiant pendant qu'il tourne. Utiliser `job.moveToDelayed(timestampAbsolu, token)` (celui-ci prend bien un timestamp absolu, à la différence de `changeDelay`) puis lever une `DelayedError` pour signaler à BullMQ que ce traitement se termine ici et reprendra plus tard.

## Rétention des jobs terminés et en échec
- `removeOnComplete: true` : un job de purge réussi est retiré de Redis plutôt que conservé indéfiniment, pour que son identifiant redevienne libre. Sans cela, le balayage de rattrapage (voir plus bas) ne pourrait jamais réajouter un job sous un identifiant occupé par une exécution déjà terminée.
- `removeOnFail` avec un nombre borné (ex. conserver les 50 derniers échecs) : garde un historique de diagnostic sans polluer indéfiniment la file.

## Le balayage de rattrapage doit récupérer le job existant, pas en ajouter un nouveau
Le piège symétrique du point précédent : si un job a échoué et reste conservé dans Redis (`removeOnFail` avec rétention), son identifiant reste occupé. Un simple `queue.add(id, data)` depuis le balayage serait alors ignoré sans effet — précisément dans le cas où le balayage devrait agir (ex. R2 était indisponible au moment de la purge). Le balayage doit donc :
1. `const job = await queue.getJob(id)` ;
2. si `job` existe et est en échec (`await job.isFailed()`), appeler `await job.retry()` plutôt que `queue.add(...)` ;
3. si `job` n'existe pas (perdu malgré l'AOF), l'ajouter normalement avec `queue.add(id, data, { delay: 0 })`.

## Résumé du principe (celui-là stable, indépendant de la version BullMQ)
Jamais de "supprimer puis réajouter" naïf sur un identifiant déterministe : la bonne opération dépend de l'état du job (en attente, en cours d'exécution, terminé, en échec, absent), et BullMQ expose une API différente pour chacun. Un ajout aveugle (`queue.add`) est correct uniquement dans le cas "absent".
