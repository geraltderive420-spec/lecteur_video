# Lecteur Média Android — « Plex Monoposte » (Offline-First)

Application Android native moderne de lecture et d'organisation d'une bibliothèque locale de films et séries, sans nécessiter de serveur distant (équivalent d'Infuse sur Android).

## 🚀 Fonctionnalités principales

- **Lecture tout-terrain** :
  - Moteur basé sur **AndroidX Media3 (ExoPlayer)** + **FFmpeg** (`nextlib`) pour le décodage de formats et codecs audio avancés (DTS, TrueHD, AC3, EAC3, FLAC).
  - Gestion du HDR10, HLG et Dolby Vision avec repli automatique (tone-mapping).
  - Gestes tactiles immersifs : luminosité à gauche, volume (> 100%) à droite, recherche horizontale précise, double tap, appui long x2.
  - Décalage audio et sous-titres à la volée, zoom libre, Picture-in-Picture (PiP).
- **Médiathèque locale autonome (Offline-First)** :
  - Scan intelligent de dossiers (stockage interne, carte SD, USB OTG).
  - Parseur de noms de fichiers performant (reconnaissance saisons, épisodes, animés, tags de release).
  - Empreinte de fichier (hash partiel début/fin) pour conserver l'état de lecture même après déplacement ou renommage.
  - Identification et enrichissement automatique via l'API **TMDB** (posters, synopsis, casting, sagas).
  - Mode hors-ligne complet après mise en cache.
- **Interface moderne** :
  - 100 % **Jetpack Compose** & **Material 3**.
  - Reprise de lecture intelligente, "Prochains épisodes", filtres et recherche instantanée.
  - Support multi-écrans : Téléphones, tablettes et Android TV (`app-tv`).

## 🛠 Stack technique

- **Langage** : Kotlin 2.0, Coroutines, Flow
- **UI** : Jetpack Compose, Material 3, Compose for TV
- **Architecture** : Clean Architecture modulaire (13 modules), MVVM, UDF
- **Moteur vidéo** : AndroidX Media3 (ExoPlayer), MediaCodec, extension FFmpeg
- **Persistance** : Room (avec FTS), DataStore Preferences
- **Tâches de fond** : WorkManager
- **Réseau** : Retrofit, OkHttp, Kotlinx Serialization
- **Injection de dépendances** : Hilt
- **Chargement d'images** : Coil

## 📁 Architecture des modules

```text
├── app/                  # Application mobile (téléphone & tablette)
├── app-tv/               # Application Android TV / Google TV
├── core/
│   ├── model/            # Modèles métier & entités
│   ├── common/           # Utilitaires, parseur de noms, empreinte fichier
│   ├── database/         # Base de données Room & DAOs
│   ├── network/          # Client API TMDB & sérialisation
│   ├── data/             # Repositories & orchestration
│   ├── player/           # Moteur Media3, codecs, gestes, pistes
│   └── designsystem/     # Thème, composants d'interface, affiches
└── feature/
    ├── home/             # Écran d'accueil & sections
    ├── library/          # Grille/liste, filtres, recherche
    ├── details/          # Fiches films & séries
    ├── player/           # Écran de lecture & HUD
    ├── scanner/          # Gestion des dossiers surveillés & scan
    ├── settings/         # Paramètres de l'application
    └── cast/             # Diffusion & mode compagnon
```

## 📺 Cast et mode compagnon (phase 5)

Deux mécanismes distincts, depuis « Diffuser sur un écran » (menu d'une fiche film, feuille d'actions d'un épisode) :

- **Mode compagnon (principal)** : l'application installée sur l'Android TV (`app-tv`) reçoit l'ordre de lecture, lit le fichier avec le moteur complet (MKV, DTS, TrueHD, HDR...) et le téléphone devient télécommande (lecture, seek, pistes audio/sous-titres, vitesse, volume).
  - Le téléphone sert le fichier en HTTP (serveur local, plages d'octets, jeton aléatoire de 128 bits dans l'URL, service au premier plan, arrêt automatique après 10 min d'inactivité). Les sous-titres externes sont servis de la même façon.
  - La TV s'annonce par NSD (`_lecteurmedia._tcp.`) et affiche un **code à 6 chiffres** à saisir sur le téléphone : seule une personne présente dans la pièce peut la piloter.
  - Protocole : JSON, un message par ligne sur TCP, versionné (`core/common/.../cast/protocol`), version négociée à la connexion.
  - La position de lecture est enregistrée sur le téléphone au fil de la lecture et à la déconnexion : on reprend au même endroit en revenant sur le téléphone.
  - L'application TV doit être **ouverte au premier plan** pour être visible et recevoir (elle disparaît de la liste du téléphone quand elle est fermée).
- **Chromecast standard** : seulement si le fichier est compatible (MP4/WebM, H.264, AAC/AC3/EAC3/MP3/FLAC/Opus/Vorbis ; HEVC/VP9/4K/HDR sur Chromecast Ultra et Google TV). Sinon un message explique chaque raison du refus et renvoie vers le mode compagnon. Limites : pas de sélection de piste ni de sous-titres, pas de sauvegarde de la position côté téléphone.

Code : logique pure testée dans `core:common` (`cast/http`, `cast/protocol`, `cast/compat`), partie Android dans `feature:cast`, récepteur et interface Compose for TV dans `app-tv`.

## ⚙️ Configuration

1. Cloner le dépôt :
   ```bash
   git clone <URL_DU_DEPOT>
   ```
2. Ajouter votre clé API TMDB dans le fichier `local.properties` (non versionné) :
   ```properties
   tmdb.apiKey=VOTRE_CLE_TMDB
   ```
3. Ouvrir le projet dans Android Studio et synchroniser Gradle.
