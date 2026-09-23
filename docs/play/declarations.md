# Google Play — déclarations à remplir dans la console

Réponses préparées pour chaque formulaire de la Play Console, version 0.9.0. À relire au moment
de l'envoi : les formulaires changent de libellé, pas de sens. Stratégie d'ensemble :
`docs/compliance/PLAY_STORE.md` § 4 bis.

## 1. Compte et fiche

| Champ | Réponse |
| --- | --- |
| Type de compte | Personnel (particulier) |
| Statut DSA (UE) | **Non-professionnel** — app gratuite, sans monétisation, hobby. Aucune adresse ni téléphone publiés. |
| Nom de développeur (public) | Steve Nodock |
| Email de contact (public) | stb@outlook.fr |
| Site | https://github.com/stevennodock/duomix-fader |
| Politique de confidentialité | https://github.com/stevennodock/duomix-fader/blob/main/docs/PRIVACY.md |
| Catégorie | Application · Musique et audio |
| Tags | Lecteur de musique, Outils musicaux, Mixage |
| Prix | Gratuite (irréversible : ne jamais passer payante) |
| Publicité | Non |
| Achats intégrés | Non |
| Pays | Tous |

## 2. Questionnaire de contenu (Content rating, IARC)

- Catégorie : **Utilitaire, productivité, communication ou autre**.
- Violence, sexualité, langage, substances, jeux d'argent : **non** partout.
- L'app permet-elle d'interagir ou d'échanger avec d'autres utilisateurs ? **Non.**
- Partage la position ? **Non.** Achats numériques ? **Non.**
- Résultat attendu : PEGI 3 / Everyone.

## 3. Audience cible

- Tranche d'âge : **18 ans et plus** uniquement (simplifie tout : pas de règles « familles »).
- L'app n'attire pas involontairement les enfants (pas de personnages, pas de jeu) : **non**.

## 4. Sécurité des données (*Data safety*)

| Question | Réponse | Justification |
| --- | --- | --- |
| L'app collecte-t-elle ou partage-t-elle des données utilisateur ? | **Non** | Aucune permission réseau ; rien ne quitte l'appareil. |
| Données chiffrées en transit ? | Sans objet | Il n'y a pas de transit. |
| L'utilisateur peut-il demander la suppression ? | Sans objet (rien de collecté) — préciser que l'historique local s'efface dans l'app et à la désinstallation. |
| Audio | Traité **sur l'appareil, de façon éphémère**, jamais collecté ni partagé | Réduit en 12 valeurs par instant, en mémoire, puis oublié. |
| Fichiers, contacts, position, identifiants | Non | — |

Remarque : le formulaire ne considère pas comme « collecte » un traitement local éphémère. Répondre
« non » est exact ; l'écran À propos et la politique de confidentialité l'expliquent.

## 5. Permissions et déclarations spécifiques

### RECORD_AUDIO (permission sensible)
- Usage : **analyse harmonique par le micro**, à la demande explicite de l'utilisateur (bouton
  « Micro » dans le bloc Harmonie), et **capture de lecture sur Android 12** (bouton « Son de l'app »).
  Jamais demandée au lancement. Un second appui arrête le micro.
- Ce qui est fait du son : réduction en énergies de classes de hauteur et image d'oscilloscope, en
  mémoire, rien n'est enregistré ni transmis.
- Vidéo à fournir : l'utilisateur ouvre le bloc Harmonie, touche « Micro », accepte la permission,
  l'oscilloscope s'anime, la gamme s'affiche ; second appui, l'indicateur micro d'Android s'éteint.

### Services de premier plan (*Foreground service types*)

| Type | Quand | Justification à saisir | Vidéo |
| --- | --- | --- | --- |
| `mediaPlayback` | Tant que le fader est affiché | La MediaSession est la surface de commande du crossfader : sa barre de progression EST le fader, ses boutons le déplacent. L'app ne joue aucun son elle-même ; elle pilote le volume de deux apps de lecture, ce qui doit survivre à la fermeture de l'écran. | Notification déployée, glisser la barre, le volume des deux apps suit. |
| `microphone` | Seulement pendant l'écoute par le micro | Le micro est lu pour l'analyse harmonique que l'utilisateur a choisie ; sans ce type, Android coupe le micro dès que l'app quitte l'écran. Retiré dès l'arrêt du micro. | Comme ci-dessus, puis mettre l'app en arrière-plan : l'analyse continue. |
| `mediaProjection` | Android 12 seulement, pendant « Son de l'app » | Sur Android 12 le service privilégié ne peut pas capter le son ; l'app utilise la capture de lecture d'Android, limitée à l'uid de l'app de musique. L'écran n'est jamais capté. | Sur un Android 12 : toucher « Son de l'app », accepter, la gamme s'affiche. |

### Accès à l'app pour la relecture (*App access*)
Cocher « Tout ou partie des fonctionnalités est soumise à des restrictions » et fournir ces
instructions (en anglais) :

> DuoMix Fader requires the free Shizuku app (Google Play: moe.shizuku.privileged.api), started
> through Developer options → Wireless debugging, as documented by Shizuku. Without it the app
> opens normally, shows three red status lights and an explanation, and no feature is active;
> it does not crash. With Shizuku running: open DuoMix Fader, tap "Request Shizuku permission",
> accept. Then start playback in YouTube Music and YouTube: both keep playing, and the sliders
> and the notification's progress bar set their volumes. The Harmony block shows the scale of
> the music after about ten seconds. No account or login is needed. A full user guide with
> screenshots is at https://github.com/stevennodock/duomix-fader/tree/main/docs/user-guide and
> the exact list of privileged operations is in the app's About screen.

### Autres questions du formulaire
- Application de news ? Non. COVID ? Non. Prêt financier ? Non. Application gouvernementale ? Non.
- Utilise-t-elle des API non publiques ? Le formulaire ne le demande pas ; l'écran À propos et le
  code public les décrivent. Ne rien cacher si un relecteur pose la question.
- Package visibility (`<queries>`) : justifiée — l'app doit savoir quelles apps du catalogue fermé
  sont installées pour les proposer. Aucune énumération de toutes les apps.

## 6. Test fermé obligatoire (nouveaux comptes personnels)

- 20 testeurs inscrits, 14 jours consécutifs, avant de pouvoir demander l'accès à la production.
- Piste : *Closed testing*, liste d'emails ou groupe Google. Fournir le lien d'inscription et la
  procédure Shizuku (guide, section 2).
- Pendant les 14 jours : ne pas changer le `versionCode` sans nécessité ; corriger seulement.
- À la fin : formulaire « Apply for production » — Google demande ce que les testeurs ont dit et
  ce qui a été corrigé. Garder une note des retours.

## 7. Éléments graphiques (voir `build-assets.ps1`)

| Élément | Format | Fichier |
| --- | --- | --- |
| Icône | 512 × 512 PNG, 32 bits | `docs/icon/duomix-fader-icon-512.png` |
| Bannière (*feature graphic*) | 1024 × 500 PNG/JPEG | `docs/play/out/feature-graphic.png` |
| Captures téléphone | 2 à 8, 9:16, 1080 × 1920 | `docs/play/out/screenshot-*.png` |

Les captures viennent des images du guide (notre app seule au premier plan, barre d'état rognée,
nom du casque masqué) : aucune donnée personnelle.
