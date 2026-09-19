# Conformité — publication de DuoMix Fader sur Google Play

Squelette à compléter avant toute soumission. Les cases cochées sont déjà en place dans
le dépôt ; les autres restent à faire. Ce document n'est pas un avis juridique.

Responsable : Steve Nodock <stb@outlook.fr>

## 1. Licences open source

### En place

- [x] `LICENSE` (Apache 2.0) et `NOTICE` (copyright + tiers) à la racine.
- [x] En-tête `SPDX-License-Identifier: Apache-2.0` + copyright sur chaque fichier source.
- [x] Écran **« Licences open source »** dans l'app (`ui/LicensesScreen.kt`) : affiche
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

- [ ] **Autorisation d'Oliver Prehn (NewJazz)** : l'app reprend sa classification (7 familles,
      33 gammes, noms systématiques) et les cinq colonnes de son tableau. Aucune licence
      n'est publiée sur newjazz.dk (vérifié le 2026-09-19) : par défaut, tous droits réservés.
      Les noms de gammes et les intervalles relèvent de la théorie musicale commune, mais
      la présentation est la sienne. **Lui écrire avant toute publication** (contact indiqué
      sur son site) pour obtenir son accord écrit sur l'usage, le crédit et le lien Patreon.
      Sans réponse : retirer les noms systématiques et la mise en tableau, garder le crédit.
- [x] Crédit et liens (Patreon, leçon, site) dans le panneau « Harmonie » et dans `NOTICE`.
- [ ] **Capture du son d'une autre app** : le service shell capte le flux de l'app de
      musique via les permissions de l'identité shell (`CAPTURE_MEDIA_OUTPUT`), y compris
      pour des apps qui refusent la capture ordinaire. Le son n'est ni enregistré ni
      transmis (réduit en 12 valeurs de chroma, en mémoire, dans le processus shell), mais
      cela contourne un choix des éditeurs (souvent lié aux droits des contenus) : risque
      de politique Play supplémentaire, à ajouter à la section 4 et à décrire sans détour
      dans la fiche et la politique de confidentialité.
- [ ] *Data safety* : déclarer que l'audio est traité sur l'appareil, de façon éphémère,
      sans collecte ni partage.

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
- [ ] Plan B si refus : distribution hors Play (GitHub Releases, F-Droid — ce dernier
      exige un build reproductible depuis les sources et aucune dépendance propriétaire).

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
