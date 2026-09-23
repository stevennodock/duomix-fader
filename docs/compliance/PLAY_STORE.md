# Conformité — publication de DuoMix Fader sur Google Play

Squelette à compléter avant toute soumission. Les cases cochées sont déjà en place dans
le dépôt ; les autres restent à faire. Ce document n'est pas un avis juridique.

Responsable : Steve Nodock <stb@outlook.fr>

## 1. Licences open source

### En place

- [x] `LICENSE` (Apache 2.0) et `NOTICE` (copyright + tiers) à la racine.
- [x] En-tête `SPDX-License-Identifier: Apache-2.0` + copyright sur chaque fichier source.
- [x] Écran **« À propos »** dans l'app (`ui/AboutScreen.kt`, modèle Markor) : build copiable, justification de Shizuku, droits, contributeurs, puis
      `NOTICE` puis `LICENSE`, copiés dans `assets/licenses/` par la tâche Gradle
      `copyLicenseAssets`. Les fichiers de la racine sont la seule source de vérité.
- [x] Audit du 2026-09-18 : 95 modules dans `releaseRuntimeClasspath`, tous Apache 2.0
      sauf `dev.rikka.shizuku:*` (MIT). Aucune licence copyleft (GPL/LGPL/AGPL/MPL).

### Obligations par licence

| Licence | Obligation dans l'app distribuée | Couvert par |
| --- | --- | --- |
| Apache 2.0 | Fournir le texte de la licence ; reproduire les fichiers `NOTICE` des dépendances s'ils existent ; signaler les fichiers modifiés | `LICENSE`, `NOTICE`, écran Licences |
| MIT (Shizuku API) | Reproduire le copyright et le texte de permission | `NOTICE` (texte MIT complet) |

### À refaire avant CHAQUE release

- [ ] `gradlew :app:dependencies --configuration releaseRuntimeClasspath` et comparer
      à la liste de `NOTICE` ; toute nouvelle dépendance doit y être ajoutée avec sa licence.
- [ ] Vérifier qu'aucune dépendance copyleft n'est apparue (bloquant pour Apache 2.0 + Play).
- [ ] Vérifier si une dépendance Apache 2.0 embarque son propre fichier `NOTICE`
      (dans l'AAR/JAR, `META-INF/NOTICE*`) : son contenu doit alors être recopié.
- [ ] Ouvrir l'écran Licences sur un build **release** (minifié) et vérifier l'affichage.

## 2. Shizuku : ce qui NE doit PAS être publié

- [ ] Le Shizuku réduit (`stb-duomix/Shizuku`, branche `duomix-minimal`) reste **privé**.
      Le README amont interdit aux dérivés le nom « Shizuku », l'identifiant
      `moe.shizuku.privileged.api` et les permissions `moe.shizuku.manager.permission.*`.
- [x] DuoMix fonctionne avec le **Shizuku officiel** : testé le 2026-09-18 sur Pixel 11
      Pro XL / Android 17 avec la release GitHub v13.6.0 (signée `CN=Rikka`, `classes.dex`
      identique au bit près à un build depuis les sources). Lecture simultanée, sliders
      et crossfader validés. **Décision : Shizuku officiel = dépendance retenue.**
      À re-tester à chaque nouvelle version majeure de Shizuku ou d'Android.
- [ ] Ne pas utiliser le logo ni les captures de Shizuku dans la fiche Play.

## 2 bis. Analyse harmonique : droits et confidentialité

- [x] **Autorisation d'Oliver Prehn (NewJazz)** — accord écrit reçu en septembre 2026 (conserver
      le message : c'est la pièce à produire si Google ou un tiers conteste). Historique : l'app reprend sa classification (7 familles,
      33 gammes, noms systématiques) et les cinq colonnes de son tableau. Aucune licence
      n'est publiée sur newjazz.dk (vérifié le 2026-09-19) : par défaut, tous droits réservés.
      Les noms de gammes et les intervalles relèvent de la théorie musicale commune, mais
      la présentation est la sienne. **Lui écrire avant toute publication** (contact indiqué
      sur son site) pour obtenir son accord écrit sur l'usage, le crédit et le lien Patreon.
      Sans réponse : retirer les noms systématiques et la mise en tableau, garder le crédit.
- [x] **Fiche PDF embarquée** (couverte par l'accord ci-dessus ; les liens de soutien restent en tête) (`assets/docs/scales-of-harmonies-*.pdf`) : elle reprend les
      données de son tableau (textes reformulés, crédit et liens de soutien en tête). C'est
      la reproduction la plus directe de son travail dans l'app : **à ne pas publier sans
      son accord écrit**. Pour un usage personnel sur son propre appareil, pas de difficulté.
      Repli sans accord : retirer les PDF des assets et le lien (`SheetLink`).
- [x] Crédit et liens (Patreon, leçon, site) dans le panneau « Harmonie » et dans `NOTICE`.
- [ ] **Capture du son d'une autre app** : le service shell capte le flux de l'app de
      musique via les permissions de l'identité shell (`CAPTURE_MEDIA_OUTPUT`), y compris
      pour des apps qui refusent la capture ordinaire. Le son n'est ni enregistré ni
      transmis (réduit en 12 valeurs de chroma, en mémoire, dans le processus shell), mais
      cela contourne un choix des éditeurs (souvent lié aux droits des contenus) : risque
      de politique Play supplémentaire, à ajouter à la section 4 et à décrire sans détour
      dans la fiche et la politique de confidentialité.
- [ ] **Titre du morceau et historique** : le service shell lit le titre et l'artiste que
      l'app de musique publie dans sa session multimédia (`MEDIA_CONTENT_CONTROL`), et l'app
      garde un historique local (fichier privé `harmony_history.json` : noms de morceaux,
      gammes, accords — effaçable depuis l'écran Historique). Rien n'est transmis. À décrire
      dans la politique de confidentialité : c'est un historique d'écoute.
- [ ] *Data safety* : déclarer que l'audio est traité sur l'appareil, de façon éphémère,
      sans collecte ni partage.
- [ ] **Micro — permission `RECORD_AUDIO`** (depuis 0.7.0) : permission sensible pour Google
      Play. Elle n'est demandée qu'au moment où l'utilisateur choisit la source « Micro » (ou
      « Son de l'app » sur Android 12, voir ci-dessous), jamais au lancement. Le son est réduit
      en chroma et en image d'oscilloscope, en mémoire, puis oublié ; rien n'est enregistré ni
      transmis (l'app n'a pas la permission `INTERNET`). À faire : la déclarer dans la fiche
      (*Data safety* : audio traité sur l'appareil, non collecté), la justifier dans la politique
      de confidentialité, et prévoir la vidéo de démonstration que Google peut exiger pour une
      permission sensible. Types de service de premier plan ajoutés : `microphone` (porté
      seulement pendant l'écoute) — à justifier dans la déclaration *Foreground service*.
- [ ] **Capture de lecture — `MediaProjection`** (depuis 0.8.0, appareils où le shell ne peut
      pas capter, c'est-à-dire Android 12) : capture officielle d'Android, limitée à l'uid de
      l'app de musique ; l'écran n'est jamais capté. Contrairement à la capture par le shell,
      elle respecte le choix des éditeurs : elle n'aboutit que si l'app de musique l'autorise
      (vérifié le 2026-09-20 sur OnePlus 7 Pro : YouTube Music, YouTube, Telegram, X, VLC,
      Chrome). Le shell pose `appops PROJECT_MEDIA allow` pour NOTRE seul paquet, ce qui évite
      la fenêtre d'accord à chaque session : à dire sans détour dans la fiche et la politique
      de confidentialité. Type de service `mediaProjection` et permission
      `FOREGROUND_SERVICE_MEDIA_PROJECTION` à justifier de même.

## 2 ter. Compatibilité : ce que l'app fait selon l'appareil (depuis 0.6.0)

L'app lit ce que l'identité shell a le droit de faire (`IMixerService.capabilities`), désactive
ce qui est impossible et l'affiche (en, fr, es). À reprendre dans la fiche Play, pour ne pas
promettre à tous ce que seuls certains appareils permettent :

| | Android récent (testé : Pixel 11 Pro XL, Android 17) | Android 12 (testé : OnePlus 7 Pro, émulateur) |
|---|---|---|
| Lecture simultanée | oui | oui |
| Volume par app, fondu continu | oui | non — **mode bascule** (`appops PLAY_AUDIO`) |
| Analyse du son de l'app | capture par le shell | capture de lecture d'Android |
| Analyse par le micro | oui | oui |
| Notification | carte de lecteur multimédia (fader, boutons, pavés, progression) | notification ordinaire dessinée par l'app : tonalité et 8 pavés en rangée, progression, bascule à trois boutons (la carte de lecteur d'Android 12 ne montre que 2 pavés et réduit l'illustration à une vignette) |

- [ ] **OnePlus, OPPO, realme** : adb (donc Shizuku) y est bridé tant que l'option développeur
      « Désactiver la surveillance des autorisations » n'est pas activée. C'est un prérequis à
      annoncer dans la fiche ; l'app affiche l'indice sur ces marques. L'option lève une
      protection du constructeur : le dire, et laisser l'utilisateur décider.
- [ ] **Mode bascule** : un réglage appops survit à un redémarrage. Garde-fous en place (le
      service shell rétablit le son à son arrêt et à la mort du client ; l'app note les paquets
      coupés et les rétablit à la connexion suivante). À re-tester avant chaque release.
- [ ] Versions Android 13 à 16 et autres marques : **non testées**.

## 3. Marques et fiche Play

- [ ] « YouTube » et « YouTube Music » sont des marques de Google : usage descriptif
      uniquement (« compatible avec… »), jamais dans le nom de l'app, l'icône ou d'une
      manière suggérant une affiliation. Pas de logo YouTube dans l'icône ni les captures.
- [ ] Mention « projet indépendant, non affilié à Google/YouTube » dans la description
      (déjà dans `NOTICE` et le README).
- [x] Icône propre à l'app : icône adaptative vectorielle originale (anneau à dégradé
      circulaire + crossfader), sans logo ni élément graphique de tiers. Sources :
      `docs/icon/` (SVG + PNG 512 px pour la fiche Play), `res/drawable/ic_launcher_*.xml`.

## 4. Risques de politique Play — À TRANCHER AVANT D'INVESTIR

Ces points ne relèvent pas des licences mais peuvent entraîner un refus ou un retrait :

- [ ] **Interférence avec d'autres apps** (politique *Device and Network Abuse*) :
      DuoMix modifie le comportement de YouTube / YouTube Music (`appops`
      `TAKE_AUDIO_FOCUS`, volume par lecteur) sans leur coopération. C'est le cœur de
      l'app : lire la politique en vigueur et évaluer le risque honnêtement.
- [ ] **Conditions d'utilisation de YouTube** : vérifier que la lecture simultanée ne
      contrevient pas aux CGU (lecture en arrière-plan, contournement de fonctionnalités).
- [ ] **API non publiques** : l'app utilise HiddenApiBypass et la réflexion sur
      `AudioPlaybackConfiguration`. Play le tolère en pratique mais ne garantit rien ;
      prévoir une dégradation propre si une API disparaît.
- [ ] **Service de premier plan `mediaPlayback`** : Play exige une déclaration (et une vidéo
      de démonstration) pour chaque type de service de premier plan. DuoMix ne joue aucun
      son : sa MediaSession n'est qu'une surface de contrôle (barre de progression =
      crossfader). Usage détourné à justifier ; repli possible sur le type `specialUse`.
- [ ] **Stratégie de soumission** (2026-09-23) : voir section 4 bis.
- [ ] Plan B si refus : distribution hors Play (GitHub Releases, F-Droid — ce dernier
      exige un build reproductible depuis les sources et aucune dépendance propriétaire).

## 4 bis. Stratégie pour un avis favorable de Google Play (2026-09-23)

Le risque n'est pas Shizuku en soi (Shizuku est sur Play, et des apps qui en dépendent y sont
publiées) mais l'**opacité** : un relecteur qui ne comprend pas pourquoi une app demande des
privilèges shell refuse. Tout ce qui suit vise à ce qu'il comprenne en deux minutes.

1. **Compte développeur** — compte Google dédié à la publication, au nom réel (Google vérifie
   l'identité : le pseudonyme « Steve Nodock » n'est que le nom d'affichage du développeur, le
   nom légal reste privé). Prévoir la vérification d'identité (pièce, parfois D-U-N-S pour une
   organisation : rester en compte individuel), le paiement unique, et les **20 testeurs pendant
   14 jours** exigés des nouveaux comptes individuels avant l'accès à la production — prévoir
   un test fermé dès maintenant.
2. **Fiche transparente** — dire dès la première ligne : « Nécessite l'app gratuite Shizuku,
   démarrée par le débogage sans fil. » Expliquer pourquoi (Android ne permet pas ceci sans
   privilèges) et ce que le service fait EXACTEMENT (la liste de l'écran À propos), captures à
   l'appui. Ne rien promettre d'Android 12 que l'app n'y fait pas (mode bascule).
3. **Déclarations à l'envoi** — *Foreground service* : `mediaPlayback` (la carte du fader),
   `microphone` (seulement pendant l'écoute par le micro), `mediaProjection` (Android 12,
   son de l'app de musique seulement) : une vidéo courte par type, montrant le geste de
   l'utilisateur qui déclenche chacun. *Data safety* : audio traité sur l'appareil, éphémère,
   aucune collecte, aucun réseau. *Permissions sensibles* : RECORD_AUDIO justifiée par la
   source « Micro », jamais demandée au lancement.
4. **Politique de confidentialité** publiée à une URL stable (page GitHub du projet) : même
   contenu que la section Privacy du guide, en anglais.
5. **Ne pas s'exposer inutilement** — l'app n'a pas la permission INTERNET (à conserver :
   c'est l'argument le plus fort face à un relecteur), ne cite aucune marque dans son icône ni
   son nom, précise qu'elle n'est affiliée ni à Google, ni à YouTube, ni à Shizuku, ni à
   Oliver Prehn. La capture privilégiée du son d'autres apps (Android 13+) doit être décrite
   sans détour ; la capture de lecture (Android 12) respecte le choix des éditeurs.
6. **Ordre de marche** — (a) dépôt GitHub public avec politique de confidentialité et guide ;
   (b) build release signé, testé sur les deux appareils ; (c) test fermé 14 jours ; (d) envoi
   en production avec les déclarations et vidéos ; (e) si refus : lire le motif exact, répondre
   par l'appel avec la description technique, et distribuer en parallèle par GitHub Releases
   (F-Droid ensuite : build reproductible à préparer).

## 5. Autres prérequis Play (hors licences, pour mémoire)

- [ ] Compte développeur vérifié ; pour un compte personnel récent : test fermé
      (12 testeurs pendant 14 jours) avant la production.
- [ ] Format **AAB** (`gradlew :app:bundleRelease`), Play App Signing, clé d'upload
      dédiée (jamais la clé de debug), `signing.properties` hors dépôt.
- [ ] `targetSdk` conforme à l'exigence Play en vigueur à la date de soumission.
- [ ] **Politique de confidentialité** (URL publique obligatoire) et formulaire
      *Data safety* : DuoMix ne collecte ni ne transmet aucune donnée et ne demande pas
      la permission `INTERNET` — à déclarer tel quel, et à re-vérifier à chaque release.
- [ ] Classification du contenu, public cible, déclaration « pas de publicité ».
- [x] R8 + réduction des ressources activés en release ; `MixerUserService` et l'AIDL
      conservés (`proguard-rules.pro`). AAB 0.3.0 testé le 2026-09-19 sur Pixel 11 Pro XL
      via `bundletool` (APK générés depuis l'AAB, comme le fait Play) : service shell lié,
      fader de la notification fonctionnel, lecture simultanée OK.
- [ ] Conserver le `mapping.txt` de chaque release (désobfuscation des traces de crash) :
      à téléverser dans la Play Console avec l'AAB.

## Journal des audits

| Date | Version | Auditeur | Résultat |
| --- | --- | --- | --- |
| 2026-09-18 | 0.1.0 | Steve Nodock | 95 modules, Apache 2.0 + MIT, conforme |
| 2026-09-19 | 0.3.0 | Steve Nodock | AAB release (R8) testé sur appareil ; dépendances inchangées depuis 0.1.0 |
