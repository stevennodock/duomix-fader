# Estimer la gamme, la tonalité et la grille d'accords d'un morceau en cours d'écoute

**Note de recherche — DuoMix Fader, fonction « Harmonie »**

- Steve Nodock <stb@outlook.fr> — conception, direction musicale, essais d'écoute
- Claude (Anthropic, modèle Fable 5.1) — algorithmes, implémentation, tests

Version 0.1 — 19 septembre 2026. Document de travail : les constantes citées sont celles
du code à cette date et sont appelées à bouger avec les essais.

> **Crédit.** La classification visée — 7 familles, 33 « Scales of Harmonies », noms
> systématiques, accords associés — est l'œuvre d'**Oliver Prehn** (NewJazz) :
> leçon <https://youtu.be/Vq2xt2D3e3E>, soutien <https://www.patreon.com/newjazz>.
> Cette note ne décrit que notre méthode de *détection* ; elle ne reproduit pas son tableau.

## 1. Objet

Donner à un musicien qui accompagne un morceau, pendant qu'il l'écoute : la **famille** de
la gamme employée, la **gamme** elle-même (son mode), la **tonalité**, et la **grille
d'accords** en chiffrage romain. L'outil vise une estimation *moyenne et stable* du
morceau, pas une transcription : il doit ignorer les variations rapides et ne signaler
qu'une modulation qui dure.

Ce n'est pas un problème nouveau — l'estimation de tonalité par profils de chroma remonte
à Krumhansl et Schmuckler, et la reconnaissance d'accords par gabarits à Fujishima —, mais
trois contraintes le particularisent ici : 33 gammes candidates au lieu des 24 tonalités
majeures/mineures ; un flux audio continu, sans connaissance du début ni de la fin du
morceau ; et un calcul embarqué, sans réseau ni modèle appris.

## 2. Principe général

Trois questions, de la plus sûre à la moins sûre, chacune avec son échelle de temps :

| Question | Indice | Échelle |
| --- | --- | --- |
| Quelles **notes** ? → la famille | chroma moyenné | ~30 s |
| Quels **accords**, en quelle **boucle** ? → la grille | chroma de trames courtes, replié sur le cycle | le cycle détecté |
| Quelle **tonique** ? → le mode, la tonalité | accord de tonique attendu + ligne de basse | ~30 s |

La séparation est le cœur de la méthode : les modes d'une même famille partagent
exactement les mêmes notes (ré dorien = do majeur = la mineur naturel). Le chroma seul ne
peut donc pas les départager ; il faut un indice de *hiérarchie* entre ces notes.

## 3. Acquisition

Le son de l'application de musique — et d'elle seule, filtrée par son uid — est capté
depuis un processus tournant sous l'identité shell (via Shizuku), par une politique audio
en boucle (`LOOP_BACK_RENDER`) : l'application reste audible. Android plafonne cette
capture à **16 kHz mono**, ce qui suffit : l'analyse n'exploite que 40 Hz – 2,1 kHz.

**Confidentialité.** Les échantillons ne quittent jamais ce processus, ne sont ni
enregistrés ni transmis. Seuls en sortent, une fois par seconde, des vecteurs de 12
nombres. Aucune reconstruction du signal n'est possible à partir d'eux.

## 4. Du signal aux classes de hauteur

Deux transformées de Fourier sur le même tampon circulaire :

- **fenêtre longue** (16 384 points, 1,02 s, pas de 256 ms, résolution 0,98 Hz) pour les
  notes et la basse — il faut cette finesse pour séparer deux demi-tons vers 41 Hz ;
- **fenêtre courte** (4 096 points, 256 ms, pas de 128 ms) pour les accords, lus au-dessus
  de 98 Hz.

Pour chaque trame : fenêtre de Hann, module, puis

1. **suppression des harmoniques 3, 5 et 6** de chaque pic (parts retirées : 30 %, 18 %,
   12 % du niveau de la fondamentale). Ce sont les seuls premiers harmoniques à tomber sur
   une *autre* note (quinte, tierce majeure) ; les 2e et 4e renforcent la même ;
2. **sélection des pics** (maxima locaux) à moins de **30 dB** du plus fort. Sans ce seuil,
   les éclaboussures des attaques créent des milliers de micro-pics ;
3. **compression en racine carrée** du module, puis cumul par classe de hauteur
   (référence la = 440 Hz) et **normalisation par trame** : un passage fort ne pèse pas plus
   qu'un passage doux.

*Ce qui n'a pas marché.* Une compression logarithmique ramenait les harmoniques presque
au niveau des fondamentales : les gammes des familles 3, 4 et 6 étaient prises pour la
famille 1. Sans le seuil de 30 dB, les notes hors gamme atteignaient ~45 % du niveau des
notes de la gamme ; avec lui, elles tombent à zéro sur signal synthétique.

## 5. La famille : quelles notes

Le chroma est intégré par une moyenne exponentielle (constante de temps 30 s). On en
retire le plancher commun aux 12 classes, puis on le **compresse encore en racine
carrée** : une gamme se reconnaît à la *présence* de ses notes, pas à leur poids — sinon
une tonique martelée à la basse écrase la norme et rend indiscernables deux gammes qui ne
diffèrent que d'une note.

Chaque *ensemble de notes* distinct (les 33 gammes × 12 toniques se réduisent à bien moins
d'ensembles, puisque les modes d'une famille le partagent) est noté par la similarité
cosinus entre ce chroma et son gabarit binaire — normalisation qui traite équitablement
les gammes de 6, 7 et 8 notes —, moins un **a priori de famille** (0 ; 0,02 ; 0,02 ; 0,03 ;
0,045 pour les familles 5 à 7) : à score voisin, la gamme la plus courante l'emporte.
C'est un rasoir d'Occam assumé, et un biais à garder en tête.

## 6. La grille : accords, cycle, repli

### 6.1 Reconnaissance d'accords

Chaque trame courte est comparée aux 48 triades (majeure, mineure, diminuée, augmentée ×
12), par similarité cosinus, avec un léger handicap pour les triades rares (0,06 ; 0,08) et
pour les accords étrangers à la gamme retenue (0,08). En deçà de 0,55, pas d'accord.

**Insensibilité au voicing.** Une classe de hauteur ignore l'octave : do-mi-sol, mi-sol-do
et sol-do-mi ont le même chroma. Un accord est donc reconnu quel que soit son
renversement, sans qu'il soit besoin de détecter ce dernier.

### 6.2 Détection du cycle

La musique tonale populaire répète sa grille. On cherche la période de cette répétition
par **autocorrélation** de la suite des chromas (64 s d'historique) : chaque trame est
lissée (640 ms), centrée, normée, puis comparée à celle qui la précède de *d* trames, pour
*d* de 2 à 32 s. La similarité moyenne S(d) présente un pic à la longueur de la boucle.

Un cycle est retenu si : S(d*) ≥ 0,45 ; S(d*) dépasse la moyenne des S d'au moins 0,10
(sinon tout se ressemble : accord unique, bourdon) ; et la boucle a été entendue au moins
deux fois. Parmi les maxima locaux à moins de 8 % du meilleur, on prend le **plus court** :
une boucle de 8 s se répète aussi à 16 s, et c'est 8 s qu'on veut.

Ce cycle **définit la fenêtre d'écoute** des accords.

### 6.3 Repli sur le cycle

Toutes les répétitions de la boucle sont superposées et moyennées, phase par phase, en
comptant depuis la trame la plus récente. Ce qui revient à chaque tour — les accords — se
renforce ; ce qui change d'un tour à l'autre — mélodie, voix, percussions — s'estompe.
C'est un débruitage par moyennage synchrone, sans aucun modèle.

Les accords sont nommés sur la boucle repliée, les suites de moins de 600 ms sont rendues
à leur voisine, et la boucle est présentée en commençant par l'accord de tonique.

### 6.4 Heuristique de repli, quand aucun cycle n'est établi

Morceau trop jeune, musique non répétitive, accord unique : les critères de 6.2 échouent.
Alors :

- la fenêtre d'analyse des accords est fixée à **20 secondes glissantes** ;
- les accords y sont nommés *tels quels*, sans repli, donc sans débruitage ;
- on affiche les **8 derniers** accords comme une suite, et non comme une boucle ;
- l'interface le dit (« pas de boucle trouvée — 20 dernières secondes »).

Vingt secondes : assez pour couvrir la plupart des grilles de 4 ou 8 mesures aux tempos
courants, assez peu pour suivre le morceau. C'est une valeur d'usage, pas un optimum
démontré — premier candidat à une étude systématique (voir § 9).

## 7. La tonique : quel mode

Pour chaque note candidate *r* de l'ensemble retenu, une saillance :

```
saillance(r) = 0,45 × part du temps sur l'accord de tonique attendu en r
             + 0,30 × part du temps où la ligne de basse tient r
             + 0,10 × part du temps où elle tient la quinte de r
             + 0,15 × présence de r dans le chroma
```

**L'accord de tonique attendu** vient de la gamme elle-même : c'est la triade qu'elle
construit sur son premier degré (majeure pour l'ionien, le lydien, le mixolydien ;
mineure pour le dorien, le phrygien, l'éolien ; diminuée pour le locrien…), ce qui
correspond à la colonne « Chords » du tableau d'Oliver Prehn. Un morceau en ré dorien
passe du temps sur ré *mineur* ; un morceau en sol mixolydien, sur sol *majeur*.

**La ligne de basse** est extraite par une règle simple : dans chaque trame longue, le pic
le plus fort entre 40 et 260 Hz, pourvu qu'il atteigne 10 % du pic le plus fort de la
trame ; s'il existe au tiers de sa fréquence un pic d'au moins 20 % de son niveau, c'est
que le premier n'était qu'un 3e harmonique, et on retient le plus grave. Une erreur
d'octave est sans conséquence.

Les deux indices sont complémentaires et aucun ne suffit :

- les **renversements** trompent la basse (do majeur sur mi) mais pas l'accord ;
- les **voicings sans fondamentale** du jazz trompent l'accord (un do majeur 9 joué
  mi-sol-si-ré *est* un mi mineur 7) mais pas la basse.

## 8. Stabilité temporelle

| Événement | Condition | Durée exigée |
| --- | --- | --- |
| Première estimation | 8 s de musique entendue | — |
| Correction d'une estimation prise trop tôt | la moyenne longue désigne d'autres notes (avance ≥ 0,01), la moyenne rapide ne la contredit pas | 12 s |
| Modulation (autres notes) | la moyenne **rapide** (6 s) désigne un autre ensemble, avance ≥ 0,02 | 12 s |
| Changement de mode (mêmes notes) | nouvelle tonique ≥ 20 % plus saillante, *la même* tout du long | 30 s |
| Nouveau morceau | silence | 2,5 s |

Deux moyennes, deux rôles. La moyenne longue mélange l'ancienne et la nouvelle gamme
pendant une modulation et ferait apparaître une gamme intermédiaire *fictive* : elle n'a
donc pas voix au chapitre tant que la moyenne rapide signale une modulation ; à
l'adoption, elle est réinitialisée à partir de la rapide. Une gamme détrônée en moins de
25 s est tenue pour une erreur d'estimation : elle est remplacée dans la liste des
séquences, pas ajoutée.

## 8 bis. Frontières entre morceaux et historique

L'historique garde une section par morceau : gamme, séquences, grille d'accords. Encore
faut-il savoir où un morceau finit et comment il s'appelle.

**Méthode retenue : lire ce que l'app de musique annonce elle-même.** Toute app de lecture
publie titre, artiste et album dans sa session multimédia — c'est ce qui alimente l'écran
verrouillé. Le processus shell la lit (permission `MEDIA_CONTENT_CONTROL`), pour la seule
app du canal musique, toutes les deux secondes. Un changement de titre clôt la section en
cours et remet le détecteur à zéro : frontière exacte, sans rien écouter ni deviner, sans
réseau. Sans titre annoncé (certaines apps, certains contenus), on retombe sur le silence
de 2,5 s, et la section s'intitule « morceau inconnu ».

Avec un titre connu, un silence n'est plus une frontière mais une pause *dans* le morceau :
sa section reste ouverte.

**Pistes écartées, et pourquoi.**

- *Reconnaissance par empreinte acoustique* (Chromaprint + base AcoustID, équivalent libre
  de Shazam) : fonctionne sans métadonnées, mais oblige à envoyer une empreinte de ce que
  l'on écoute à un serveur tiers, donc à demander la permission réseau que l'app n'a pas,
  et à dépendre d'une base qui couvre mal les musiques de niche. À réserver, en option
  explicite, aux sources muettes sur leur contenu.
- *Modèle embarqué* (NPU, modèle spécialisé par LoRA) : identifier un morceau parmi des
  dizaines de millions relève de la recherche dans une base d'empreintes, pas de la
  classification — aucun modèle embarqué ne contient ce catalogue. Un modèle pourrait en
  revanche lire l'écran de l'app émettrice, mais la session multimédia donne la même
  information, exacte et gratuite.
- Un modèle embarqué aurait plus de sens ailleurs : séparer basse, accords et voix avant
  l'analyse (voir § 9).

## 9. Validation, limites, travaux à venir

**Ce qui est vérifié.** 23 tests automatisés sur signal synthétisé (notes à 4 harmoniques,
16 kHz) : fidélité du catalogue aux règles de construction des 33 gammes ; détection
famille / mode / tonique pour des gammes des familles 1, 2, 3, 4 et 6 ; insensibilité à
une excursion de 4 s ; modulation durable ; nouveau morceau après silence ; **tonique
retrouvée avec des accords tous renversés**, basse sur la tierce ; cycle de 8 s et
progression I – IV – V ; repli sans cycle. Sur appareil : capture, charge (~3,5 % d'un
cœur), stabilité de la famille sur musique réelle.

**Ce qui ne l'est pas.** Aucune évaluation sur un corpus annoté : nous ne connaissons pas
le taux de réussite sur musique réelle. Les constantes ont été réglées sur signal
synthétique et sur quelques écoutes.

**Limites connues.**

- Le **mode est intrinsèquement moins sûr que la famille**, et l'ambiguïté est parfois
  musicale : une boucle I – V – vi – IV passe autant de temps sur quatre accords dont
  chacun est l'accord de tonique d'un mode de la même famille.
- Accords plus brefs que ~0,6 s ; accords de quatre sons et plus réduits à leur triade ;
  musique non tempérée ou accordée loin de 440 Hz ; percussions très présentes.
- Musique sans grille répétée : on reste sur l'heuristique du § 6.4.
- L'a priori de famille retarde la reconnaissance des gammes rares.
- Si le canal musique est baissé à zéro au fader, l'analyse n'entend plus rien.

**Pistes.** Séparation de sources par un modèle embarqué (basse / harmonie / voix), qui
attaquerait à la racine les deux faiblesses actuelles — ligne de basse et accords noyés
dans le mixage ; évaluer sur un corpus annoté (tonalité et accords) ; estimer le tempo pour
exprimer le cycle en mesures et aligner son début sur un premier temps ; accords de
septième, pour exploiter toute la colonne « Chords » ; profils de hiérarchie tonale
propres à chacune des 33 gammes ; estimation de l'accordage ; étude de sensibilité de la
fenêtre de repli.

## 10. Reproductibilité

Code : `app/src/main/java/com/dirtwing/duomixfader/harmony/` (`ChromaAnalyzer`,
`ProgressionTracker`, `HarmonyDetector`, `ScaleCatalog`), sans dépendance Android.
Tests : `gradlew :app:testDebugUnitTest`. Licence Apache 2.0 pour le code et pour cette
note ; le matériel d'Oliver Prehn n'est pas couvert par cette licence (voir `NOTICE`).
