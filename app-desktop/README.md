# Lecteur Média pour Windows (`app-desktop`)

Version Windows de l'application, avec les fonctions de la version téléphone. Le PC peut aussi **recevoir depuis le téléphone**, comme la TV (mode compagnon).

## Installer

- **Installateur** : `app-desktop/build/compose/binaries/main/exe/LecteurMedia-1.0.3.exe`. L'installation se fait pour l'utilisateur courant (dans `%LOCALAPPDATA%`), sans droits administrateur, et crée un raccourci dans le menu Démarrer.
- **Version portable** : le dossier `app-desktop/build/compose/binaries/main/app/LecteurMedia/` contient `LecteurMedia.exe`. On peut le copier n'importe où et le lancer directement.

Tout est embarqué : le Java runtime et le moteur vidéo (libVLC). **Aucune installation de VLC ni de Java n'est nécessaire.**

Au premier lancement, Windows demande d'autoriser Lecteur Média sur le réseau : acceptez au moins pour les **réseaux privés**. Sans cette autorisation, le téléphone ne trouvera pas le PC.

## Fonctions

- **Bibliothèque** : dossiers surveillés par catégorie (films, séries, animés, documentaires, vidéos perso, mixte), analyse au démarrage ou périodique, et empreinte de fichier qui conserve la progression quand un fichier est déplacé ou renommé. Un disque débranché passe « hors ligne » sans être oublié. Les `.nfo`, `poster.jpg` et `fanart.jpg` sont lus.
- **Identification TMDB** : affiches, résumés, distribution, sagas, saisons et épisodes. Un écran « À vérifier » permet de confirmer les correspondances douteuses, et on peut corriger l'identification à la main.
- **Accueil** :
  - les rangées Reprendre, Épisodes suivants, Récemment ajoutés, Films, Séries, Non vus et Favoris ;
  - l'ordre et la visibilité des rangées se règlent dans les paramètres.
- **Films / Séries** :
  - affichage en grille ou en liste, trois tailles d'affiches ;
  - 7 tris ;
  - filtres par visionnage, genre, décennie, résolution, HDR, langue audio, classification et dossier.
- **Fiches** :
  - badges (4K, HDR, Dolby Vision, codecs, audio) ;
  - versions multiples d'un même film ;
  - distribution (un clic ouvre la filmographie présente dans la bibliothèque) ;
  - saga, bande-annonce, liens IMDb/TMDB et informations techniques ;
  - pour les séries : saisons, épisodes, marquer une saison comme vue.
- **Recherche** sans accents ni majuscules dans les titres, les personnes et les épisodes. **Listes** personnelles et Favoris. **Explorateur** de fichiers : il lit n'importe quelle vidéo et enchaîne avec le reste du dossier.
- **Lecteur** (libVLC, décodage GPU Direct3D 11, HDR) :
  - tous les formats : MKV, MP4, AVI, HEVC, AV1, DTS, TrueHD, Atmos…
  - reprise de lecture (à demander, automatique ou jamais) ;
  - pistes audio et sous-titres choisis selon les langues préférées, mémorisés par fichier et par série ;
  - sous-titres externes (`.srt`, `.ass`…) détectés automatiquement ou chargés à la main ;
  - décalage audio et sous-titres, vitesse, modes d'affichage (adapter, remplir, étirer, 16:9…) et zoom ;
  - chapitres, file de lecture avec aléatoire et répétition, épisode suivant avec compte à rebours ;
  - minuterie de veille, mode nuit, volume jusqu'à 200 %, passthrough audio, capture d'image ;
  - statistiques de lecture, mini-lecteur toujours au premier plan, plein écran.
- **Diffusion** :
  - **Recevoir depuis le téléphone** : le PC s'annonce sur le réseau comme la TV et affiche un code à 6 chiffres. Le téléphone pilote ensuite la lecture (pistes, seek, volume…) et garde la progression.
  - **Diffuser vers un écran** : le PC envoie un film de sa bibliothèque à la TV (ou à un autre PC) et sert de télécommande.

### Raccourcis du lecteur

| Touche | Action |
|---|---|
| Espace / K | Lecture / pause |
| ← / → | Reculer / avancer (pas réglable ; Maj : 3 s, Ctrl : 1 min) |
| ↑ / ↓, molette | Volume |
| F, F11, double-clic | Plein écran |
| M | Muet |
| A / S | Piste audio / sous-titres suivants |
| G / H | Décalage des sous-titres −/+ 100 ms |
| [ / ] , Retour arrière | Vitesse −/+, vitesse normale |
| N / P | Fichier suivant / précédent |
| Page préc. / suiv. | Chapitre |
| 0 à 9 | Aller à 0 %, 10 %… 90 % |
| Z | Mode d'affichage suivant |
| Échap | Quitter le plein écran, puis la lecture |

Dans la bibliothèque : Ctrl+F lance une recherche, Ctrl+O ouvre un fichier, Alt+← ou le bouton « précédent » de la souris revient en arrière. On peut aussi glisser-déposer une vidéo ou un dossier sur la fenêtre.

## Construire

Prérequis : JDK 17 et VLC 3 (64 bits) installé sur la machine de build. Ses fichiers sont copiés dans l'application ; à défaut, indiquez son dossier avec la variable `VLC_DIR`.

```bash
gradlew.bat :app-desktop:packageExe          # installateur .exe
gradlew.bat :app-desktop:createDistributable # version portable (dossier)
gradlew.bat :app-desktop:run                 # lancer depuis les sources
```

La clé TMDB est lue comme pour Android, depuis `tmdb.apiKey` dans `local.properties` ou depuis `TMDB_API_KEY`. On peut aussi la saisir dans les Paramètres de l'application.

### Auto-test

`gradlew.bat :app-desktop:selfTest -PselfTestDir=<dossier>` attend un dossier contenant `lib/Films` et `lib/Series`. L'auto-test :

1. analyse et identifie la bibliothèque ;
2. rend chaque écran hors écran dans `<dossier>/out` ;
3. lit un film 1080p et un 4K HEVC dans la vraie fenêtre (pistes, seek, plein écran, mini-lecteur) ;
4. enchaîne les épisodes d'une série ;
5. joue le rôle du téléphone sur le protocole compagnon (appairage, flux HTTP, commandes) ;
6. vérifie l'annonce mDNS.

Le rapport est écrit dans `<dossier>/out/report.txt`.

## Architecture

- **Code partagé avec Android, sans copie** :
  - les modules `core:model` et `core:common` : parseur de noms, empreinte, score d'identification, protocole compagnon, serveur HTTP de flux ;
  - le client TMDB de `core:network` ;
  - la logique sans dépendance Android de `core:player` : reprise, choix des pistes, file de lecture, minuterie, modes d'affichage. Ces sources sont compilées directement dans le module (`srcDir`).
- **Spécifique au bureau** :
  - SQLite (`sqlite-jdbc`) ;
  - Compose Multiplatform Desktop pour l'interface ;
  - vlcj/libVLC pour la lecture ;
  - jmDNS pour la découverte réseau, compatible avec `NsdManager` sur Android.
- **Rendu vidéo natif** : libVLC dessine directement dans la fenêtre, sans copie d'images par Java. Les contrôles sont une fenêtre Compose transparente posée au-dessus de la vidéo, visible seulement quand ils s'affichent.
- **Données** : `%LOCALAPPDATA%\LecteurMedia\data` contient la base, le cache d'images et `settings.json`.
