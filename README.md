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
├── app-desktop/          # Application Windows (Compose Desktop + libVLC)
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

## 🖥 Version Windows (`app-desktop`)

Exécutable Windows avec les fonctions de la version téléphone. Le PC peut aussi **recevoir depuis le téléphone** comme la TV : il s'annonce sur le réseau, affiche un code à 6 chiffres, puis lit le flux du téléphone, qui sert de télécommande.

- Interface en Compose Multiplatform Desktop.
- Lecture par libVLC embarqué : décodage GPU Direct3D 11, HDR, tous codecs.
- Base de données SQLite.
- Les modules `core:model`, `core:common`, le client TMDB et la logique de lecture sont partagés avec Android.

```bash
gradlew.bat :app-desktop:packageExe
```

Le détail (installation, raccourcis, auto-test, architecture) est dans [app-desktop/README.md](app-desktop/README.md).

## 🏠 Bibliothèque partagée, façon Plex (PC → téléphones et TV)

Le PC Windows sert sa bibliothèque aux téléphones et aux TV de la maison : ils la parcourent (affiches, résumés, saisons), lisent n'importe quel film ou épisode directement depuis le PC, et partagent **un seul état de visionnage**, celui du PC. Un film commencé sur la TV reprend au même endroit sur le téléphone ou sur le PC.

- **Sur le PC** : « Partage » dans la barre latérale → activer, puis « Associer un appareil » affiche un code à 6 chiffres.
- **Sur le téléphone** : onglet « Partagés » → le PC apparaît tout seul (découverte réseau `_lecteurlib._tcp`) → saisir le code. On peut aussi saisir l'adresse du PC à la main.
- **Sur la TV** : onglet « Partagés », même principe ; la TV lit avec son propre moteur (MKV, DTS, HDR…).
- Le fichier original est envoyé tel quel (HTTP avec plages d'octets) : aucun transcodage, le lecteur de l'appareil décode. Les sous-titres externes (`.srt`, `.ass`…) suivent. Une série s'enchaîne d'épisode en épisode sur le téléphone.

**Sécurité**

- Désactivé par défaut. Seuls les appareils du **réseau local** reçoivent une réponse (adresses privées, lien local, IPv6 ULA) : rien n'est exposé sur Internet.
- **Appairage** uniquement pendant que la fenêtre « Associer un appareil » est ouverte sur le PC : code à usage unique, valable 5 minutes, fermé après 5 essais faux. Une adresse qui accumule les refus est mise à l'écart quelques minutes.
- Après l'appairage, l'appareil reçoit une clé de 256 bits qui **ne circule plus jamais** : chaque requête est signée (HMAC-SHA256 de la méthode, du chemin, de l'heure et du corps), refusée au-delà de 5 minutes d'écart, et une écriture ne peut pas être rejouée.
- Les liens de lecture (flux, sous-titres, affiches) portent une signature propre à **un seul fichier** : impossible d'en déduire un autre. Les affiches sont désignées par une clé opaque, jamais par un chemin du disque.
- « Retirer » un appareil sur le PC coupe immédiatement son accès, liens déjà ouverts compris.
- Les appareils ne peuvent que lire la bibliothèque et l'état de visionnage : ni modifier ni supprimer.
- Limite connue : le trafic n'est pas chiffré (HTTP sur le réseau local, comme le mode compagnon) ; quelqu'un sur le même Wi-Fi pourrait voir quel fichier est regardé, sans pouvoir s'associer ni ouvrir d'autres fichiers.

Code : protocole, serveur, client et sécurité dans `core:common` (`library`, testés de bout en bout), serveur du PC dans `app-desktop` (`share`), client Android dans `core:data` (`shared`) et `feature:shared`, écran TV dans `app-tv`.

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
