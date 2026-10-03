# MISSION : LECTEUR MÉDIA ANDROID « PLEX MONOPOSTE » (OFFLINE-FIRST)

## 0. RÔLE ET MODE DE TRAVAIL

Tu es un ingénieur Android senior spécialisé en lecture vidéo (Media3, NDK, MediaCodec). Tu construis l'application décrite ci-dessous avec moi, par étapes.

Règles de travail :
- Livre du code complet et compilable. Pas de `TODO`, pas de « à implémenter », pas de pseudo-code. Si une partie est hors du périmètre de l'étape, dis-le explicitement au lieu de laisser un trou.
- Travaille phase par phase (section 8). À la fin de chaque phase, arrête-toi, résume ce qui est livré et ce qui reste, et attends ma validation.
- Avant d'écrire du code pour une phase, annonce en quelques lignes les choix techniques et les risques. Si une exigence est irréaliste ou contradictoire, dis-le et propose une alternative au lieu de l'implémenter à moitié.
- Indique les versions exactes des dépendances que tu utilises et vérifie qu'elles sont compatibles entre elles.
- Chaque composant logique (parser, matching, DAO, règles de reprise) est livré avec ses tests unitaires.

---

## 1. OBJECTIF

Application Android native, fluide et moderne, qui lit, indexe et organise une bibliothèque locale de films et séries, sans aucun serveur (équivalent d'Infuse sur iOS). Elle télécharge automatiquement les métadonnées, mémorise la progression de chaque média et embarque un lecteur tout-terrain avec gestes tactiles façon VLC.

Cibles : téléphones et tablettes Android (priorité), puis Android TV / Google TV dans le même projet.
Sources : stockage interne, carte SD, USB OTG en version 1. Sources réseau (SMB, WebDAV) en version 2, mais l'architecture doit les prévoir dès le départ.

Qualités non négociables : fluidité (aucune saccade dans les listes, démarrage rapide), ergonomie (tout accessible en peu de gestes), robustesse (un fichier illisible ou mal nommé ne casse jamais rien).

---

## 2. STACK TECHNIQUE

- **Langage :** Kotlin, Coroutines, Flow. minSdk 26, targetSdk la plus récente.
- **UI :** Jetpack Compose, Material 3, thème sombre par défaut, couleurs dynamiques, transitions partagées. Compose for TV pour le module télé.
- **Lecture :** AndroidX Media3 (ExoPlayer) + MediaCodec pour le décodage matériel + extension FFmpeg pour l'audio (DTS, DTS-HD, TrueHD, AC3, E-AC3, FLAC). Utiliser une extension précompilée maintenue (type nextlib) plutôt qu'une compilation NDK maison, sauf nécessité.
- **Moteur de secours :** abstraction `PlayerEngine` permettant d'ajouter libmpv plus tard pour les fichiers que Media3 ne lit pas (vieux AVI, codecs rares). Ne pas implémenter libmpv en version 1, seulement l'interface.
- **Persistance :** Room, clés étrangères, index, migrations versionnées, FTS pour la recherche. DataStore pour les préférences.
- **Arrière-plan :** WorkManager.
- **Réseau :** Retrofit ou Ktor (en choisir un et s'y tenir) + Kotlinx.Serialization.
- **Injection :** Hilt.
- **Images :** Coil, cache disque dimensionné, tailles d'images adaptées à l'affichage.
- **Architecture :** MVVM + flux unidirectionnel, modules Gradle (section 7), catalogue de versions.

---

## 3. CONTRAINTES ET PIÈGES À TRAITER EXPLICITEMENT

Ces points font échouer la plupart des projets de ce type. Je veux une réponse concrète pour chacun.

1. **Accès aux fichiers.** Le parcours via `DocumentFile` est très lent sur de gros dossiers. Utiliser des requêtes `DocumentsContract` directes, et croiser avec `MediaStore` quand c'est possible. Persister les permissions d'URI. Expliquer le choix entre SAF seul et `MANAGE_EXTERNAL_STORAGE`, avec les conséquences pour une publication sur le Play Store.
2. **Métadonnées.** IMDb n'a pas d'API accessible. Utiliser TMDB comme source, stocker l'identifiant IMDb fourni par TMDB et proposer un lien vers la fiche IMDb. Afficher l'attribution TMDB obligatoire. La clé API ne doit pas être dans le dépôt (fichier local non versionné). Respecter les limites de débit avec file d'attente et reprise.
3. **Dolby Vision et HDR.** Détecter les capacités réelles de l'appareil (décodeur et écran). Profils 5 et 8 si le matériel le permet, profil 7 lu comme HDR10 via la couche de base, sinon repli HDR10 puis SDR avec tone-mapping. Ne jamais afficher un badge « Dolby Vision » actif si le repli est en cours.
4. **Audio.** Atmos et DTS:X ne sont rendus en objet qu'en passthrough vers un ampli compatible. Sur téléphone, décoder le cœur (5.1/7.1) et mixer en stéréo. Passthrough automatique quand la sortie le supporte, décodage logiciel sinon, avec réglage manuel.
5. **Cast.** Google Cast ne lit ni MKV, ni AVI, ni DTS, ni TrueHD. Deux mécanismes distincts :
   - **Cast standard** vers Chromecast : serveur HTTP local embarqué, proposé seulement si le fichier est compatible, sinon message clair expliquant pourquoi.
   - **Mode compagnon** : la même application installée sur Android TV reçoit l'ordre de lecture depuis le téléphone (découverte sur le réseau local), lit le fichier avec le moteur complet, et le téléphone sert de télécommande. C'est le mode principal.
6. **Licences.** FFmpeg (LGPL/GPL selon la configuration) et libmpv ont des implications si l'application est distribuée. Indiquer la configuration retenue et ce qu'elle impose.
7. **Sous-titres.** ASS/SSA avec styles demande un rendu dédié (libass), PGS et VOBSUB sont des images. Préciser ce qui est rendu fidèlement et ce qui est simplifié.

---

## 4. SPÉCIFICATIONS FONCTIONNELLES

### Module A : Sources, scan et identification

1. **Dossiers surveillés.** L'utilisateur ajoute des dossiers et associe à chacun une catégorie : Films, Séries, Animés, Documentaires, Vidéos personnelles (sans recherche de métadonnées), Générique. Ajout, suppression, pause, re-scan forcé par dossier.
2. **Scan (WorkManager).**
   - Parcours récursif : `.mkv`, `.mp4`, `.m4v`, `.avi`, `.mov`, `.ts`, `.m2ts`, `.webm`, `.wmv`, `.flv`, `.mpg`.
   - Scan incrémental : ne retraiter que les fichiers nouveaux, modifiés ou supprimés.
   - Empreinte par fichier (taille + hash partiel du début et de la fin) pour suivre un fichier déplacé ou renommé sans perdre sa progression.
   - Détection automatique des nouveaux fichiers : scan au lancement, scan périodique, bouton manuel. Gestion des supports amovibles débranchés (média marqué « indisponible », jamais supprimé de la base).
   - Extraction des informations techniques réelles de chaque fichier : résolution, codec vidéo, HDR/DV, pistes audio (langue, codec, canaux), pistes de sous-titres, durée, chapitres.
   - Progression visible (notification et écran des réglages), annulable.
3. **Analyse des noms de fichiers.**
   - Extraire titre nettoyé, année, saison/épisode, et étiquettes techniques (`1080p`, `2160p`, `HDR`, `DV`, `x265`, `REMUX`, groupe de release).
   - Formats à couvrir : `S01E02`, `s01e02e03` (double épisode), `1x02`, `Saison 1 Episode 2`, `EP02`, `E02`, numérotation absolue des animés (`[Groupe] Titre - 012`), épisodes spéciaux (`S00E01`), dates (`2024.03.15`), séparateurs points, tirets et underscores, crochets et parenthèses.
   - Utiliser aussi les noms des dossiers parents comme indice (`Série/Saison 2/05.mkv`).
   - Regrouper les épisodes d'une même série quels que soient leurs dossiers physiques.
   - Lire les fichiers `.nfo` et les images locales (`poster.jpg`, `fanart.jpg`) s'ils existent, avec priorité sur le résultat en ligne.
4. **Identification TMDB.**
   - Recherche film ou série selon la catégorie du dossier, avec score de confiance (similarité du titre, année, durée).
   - Données : titre original et localisé, synopsis, note, date de sortie, durée, genres, classification d'âge, acteurs et rôles, réalisateur, affiche, image de fond, logo, bande-annonce, identifiant IMDb, collection (saga), et pour les séries : saisons, épisodes, vignettes, statut.
   - Langue des métadonnées configurable (français par défaut, repli anglais).
   - Trois états : identifié, à vérifier (confiance faible), non identifié. Une vue dédiée liste les médias à vérifier.
   - **Corriger l'association** : recherche manuelle, choix parmi les résultats, saisie directe d'un identifiant TMDB, et verrouillage pour que le prochain scan ne l'écrase pas.
   - Tout fonctionne hors-ligne une fois les métadonnées et images en cache.

### Module B : Base de données Room

Entités minimales (à compléter si nécessaire, justifier tout ajout) :

- `LibraryFolder` (id, uri, displayPath, category, enabled, lastScannedAt)
- `Movie` (id, tmdbId, imdbId, title, originalTitle, sortTitle, year, releaseDate, overview, runtime, rating, certification, posterPath, backdropPath, logoPath, trailerKey, collectionId, addedAt, matchState, matchLocked)
- `Series` (id, tmdbId, imdbId, title, originalTitle, sortTitle, firstAirDate, status, overview, rating, certification, posterPath, backdropPath, logoPath, addedAt, matchState, matchLocked)
- `Season` (id, seriesId, seasonNumber, name, overview, posterPath, airDate)
- `Episode` (id, seriesId, seasonId, seasonNumber, episodeNumber, absoluteNumber, title, overview, stillPath, airDate, runtime)
- `MediaFile` (id, folderId, uri, displayPath, fileName, size, fingerprint, lastModified, durationMs, container, videoCodec, width, height, hdrType, movieId, episodeId, addedAt, isAvailable)
- `AudioTrackInfo` et `SubtitleTrackInfo` (mediaFileId, index, language, codec, channels, title, isDefault, isForced)
- `WatchState` (mediaFileId, positionMs, durationMs, isCompleted, playCount, lastWatchedAt, selectedAudioTrack, selectedSubtitleTrack, audioDelayMs, subtitleDelayMs)
- `Person` + `CastMember` (lien film ou série, rôle, ordre, type acteur/réalisateur)
- `Genre` + tables de jointure
- `Collection` (sagas TMDB) et `UserList` + `UserListItem` (favoris, listes personnelles)
- `SeriesPreference` (seriesId, langue audio préférée, langue de sous-titres préférée)

Exigences : plusieurs `MediaFile` peuvent pointer vers le même film ou épisode (versions 1080p et 4K, avec choix de la version à la lecture). Index sur toutes les colonnes de tri et de filtre. Table FTS pour la recherche. Requêtes d'accueil exposées en `Flow`. Listes paginées (Paging 3). Sauvegarde et restauration de la base (export/import d'un fichier).

### Module C : Lecteur vidéo

1. **Formats.**
   - Vidéo : H.264, HEVC, VP9, AV1, MPEG-4/Xvid, MPEG-2, VC-1 selon le matériel, avec repli logiciel quand c'est faisable.
   - HDR : HDR10, HDR10+, HLG, Dolby Vision (voir section 3).
   - Audio : AAC, MP3, AC3, E-AC3, E-AC3 JOC (Atmos), TrueHD, DTS, DTS-HD MA, DTS:X (cœur), FLAC, Opus, Vorbis, PCM.
   - Conteneurs : MKV, MP4, AVI, MOV, TS, M2TS, WebM.
2. **Gestes.**
   - Glissement vertical à gauche : luminosité (attribut `screenBrightness` de la fenêtre, sans modifier le réglage système).
   - Glissement vertical à droite : volume du flux média, avec amplification au-delà de 100 % en option.
   - Glissement horizontal : recherche avec affichage du temps cible et de l'écart, vignette d'aperçu si disponible.
   - Double tap gauche/droite : saut configurable (10 s par défaut), cumulable. Double tap au centre : pause.
   - Appui long : lecture accélérée x2 tant que le doigt est posé.
   - Pincement : zoom libre. Les modes d'affichage sont un bouton séparé.
   - Zones mortes sur les bords pour ne pas entrer en conflit avec les gestes système. Verrouillage de l'écran tactile.
3. **Modes d'affichage.** Ajuster, Remplir (rogner), Étirer, Original, 16:9, 4:3, 21:9. Mémorisé par média. Rotation automatique ou verrouillée.
4. **Pistes.**
   - Audio : sélecteur avec langue, codec, canaux, titre. Changement sans coupure longue.
   - Sous-titres intégrés : SRT, ASS/SSA, PGS, VOBSUB. Externes : `.srt`, `.ass`, `.vtt`, `.sub/.idx` détectés dans le même dossier ou chargés manuellement.
   - Recherche et téléchargement de sous-titres via OpenSubtitles.
   - Apparence : taille, couleur, contour, fond, position verticale.
   - Délai audio et délai sous-titres réglables par pas de 50 ms.
   - Langues préférées globales, surchargées par série (`SeriesPreference`), sous-titres forcés gérés automatiquement.
5. **Lecture.**
   - Vitesse 0,25x à 4x avec correction de hauteur.
   - Chapitres : liste et navigation.
   - Épisode suivant : compte à rebours en fin d'épisode, bouton « Passer le générique » si des chapitres le permettent.
   - Lecture par dossier : enchaîner les fichiers d'un dossier dans l'ordre, avec répétition et aléatoire.
   - Minuteur de sommeil (durée ou fin de l'épisode).
   - Mode nuit (compression de dynamique), égaliseur simple.
   - Picture-in-Picture avec commandes, lecture en arrière-plan (audio seul) en option.
   - MediaSession complète : notification, écran de verrouillage, casque Bluetooth, touches média.
   - Panneau d'informations techniques (codec, débit, images perdues, décodeur utilisé).
   - Réglages : décodeur matériel ou logiciel, tunneling, ajustement de la fréquence d'affichage (TV).
6. **Reprise.**
   - Position sauvegardée périodiquement (toutes les 5 s environ), à la pause et à la sortie, jamais à chaque image.
   - « Vu » au-delà de 90 % ou dans les dernières minutes. En dessous de 2 %, considérer comme non commencé.
   - À l'ouverture : « Reprendre à HH:MM:SS » ou « Recommencer ». Marquage manuel vu / non vu, par épisode, saison ou série.
7. **Cast.** Les deux mécanismes de la section 3 : Cast standard avec vérification de compatibilité, et mode compagnon Android TV avec télécommande complète (lecture, recherche, pistes, volume) et synchronisation de la progression au retour.

### Module D : Interface

1. **Accueil.** Reprendre la lecture, Prochains épisodes (épisode suivant des séries en cours), Récemment ajoutés, Films, Séries, Non vus, Favoris. Rangées configurables et réordonnables.
2. **Bibliothèque.**
   - Vues grille d'affiches et liste, taille des vignettes réglable.
   - Tri : titre, date d'ajout, date de sortie, note, durée, dernière lecture, taille du fichier. Ordre croissant ou décroissant, mémorisé par section.
   - Filtres combinables : genre, année ou décennie, vu / non vu / en cours, résolution, HDR, classification d'âge, dossier source, langue audio.
   - Navigation par genre, acteur, réalisateur, collection, année.
   - Recherche globale instantanée (titres, acteurs, noms de fichiers).
3. **Fiche détail.** Image de fond, affiche, logo, année, durée, note, classification, genres, badges techniques issus du fichier réel, synopsis, distribution (tap sur un acteur = ses autres titres dans la bibliothèque), bande-annonce, lien IMDb, choix de la version si plusieurs fichiers, chemin du fichier. Séries : onglets par saison, épisodes avec vignette, progression et statut. Actions : lire, reprendre, marquer vu, favori, ajouter à une liste, corriger l'association, rafraîchir les métadonnées.
4. **Dossiers.** Explorateur brut des dossiers surveillés, lecture directe de n'importe quel fichier, même non identifié.
5. **Réglages.** Dossiers et catégories, scan, langue des métadonnées, lecteur (gestes, sauts, décodeur, sous-titres, audio), apparence, contrôle parental (code PIN + plafond de classification d'âge), sauvegarde et restauration, cache des images, à propos et attributions.
6. **États à soigner.** Premier lancement guidé (permissions, ajout du premier dossier), bibliothèque vide, scan en cours, hors-ligne, fichier manquant, erreur de lecture avec message compréhensible et proposition de solution.
7. **Adaptation.** Téléphone portrait et paysage, tablette et pliables (mises en page adaptatives), Android TV (navigation à la télécommande, focus visible).

---

## 5. EXIGENCES DE PERFORMANCE

- Démarrage à froid jusqu'à l'accueil affiché : moins de 1,5 s sur un appareil milieu de gamme, bibliothèque de 2 000 médias.
- Défilement des grilles sans image perdue : chargement paginé, images à la bonne taille, clés stables, pas de recomposition inutile.
- Ouverture d'un fichier local jusqu'à la première image : moins de 1 s.
- Scan initial de 1 000 fichiers : jamais de blocage de l'interface, résultats visibles au fil de l'eau.
- Aucune opération disque ou réseau sur le thread principal. Baseline Profiles générés.

---

## 6. PRÉVU POUR PLUS TARD (architecture à anticiper, ne pas coder)

- Sources réseau SMB, WebDAV, DLNA via une abstraction `MediaSource` commune avec le stockage local.
- Moteur libmpv derrière `PlayerEngine`.
- Synchronisation Trakt (historique et progression).
- Profils utilisateurs multiples.
- Musique.

---

## 7. MODULES GRADLE

`app`, `app-tv`, `core:model`, `core:database`, `core:network`, `core:data` (repositories), `core:player`, `core:designsystem`, `core:common`, `feature:home`, `feature:library`, `feature:details`, `feature:player`, `feature:scanner`, `feature:settings`, `feature:cast`.

Les modules `feature` ne dépendent jamais les uns des autres.

---

## 8. PHASES DE LIVRAISON

**Phase 1 : fondations.** Structure Gradle, catalogue de versions, thème, navigation, modèles, schéma Room complet avec DAO et tests, analyseur de noms de fichiers avec au moins 60 cas de test couvrant tous les formats de la section 4.A.3 (dont cas piégeux : titres contenant une année ou un nombre, comme « 2001 », « 1917 », « Blade Runner 2049 »).

**Phase 2 : lecteur.** `core:player` et `feature:player` : Media3 + extension FFmpeg, gestes, modes d'affichage, pistes, sous-titres, délais, vitesse, chapitres, PiP, MediaSession, reprise. Lecture d'un fichier choisi manuellement.

**Phase 3 : bibliothèque.** Dossiers surveillés, scan incrémental, extraction technique, identification TMDB, correction manuelle, cache hors-ligne.

**Phase 4 : interface complète.** Accueil, bibliothèque avec tris et filtres, recherche, fiches détail, explorateur de dossiers, réglages, épisode suivant, lecture par dossier.

**Phase 5 : cast et TV.** Dans cet ordre, chaque étape livrable et testable seule :
1. Serveur HTTP local du téléphone (plages d'octets, jeton, service au premier plan, arrêt automatique), avec tests.
2. Protocole de télécommande et découverte NSD (`feature:cast`) : messages (ouvrir, lecture/pause, seek, pistes, volume, état, progression), versionnés, avec tests.
3. Module Android TV (`app-tv`) : récepteur du mode compagnon, lecture de l'URL reçue avec le moteur complet, navigation à la télécommande et focus visible, accueil et bibliothèque en Compose for TV.
4. Télécommande côté téléphone : choix de l'appareil, écran de télécommande, reprise de la progression au retour.
5. Cast standard Chromecast avec test de compatibilité du fichier et message expliquant le refus.
Test réel exigé sur un téléphone et une TV du même Wi-Fi (débit d'un film 4K, coupure et reprise du Wi-Fi, téléphone en veille).

**Phase 6 : finitions.** OpenSubtitles, listes et favoris, contrôle parental, sauvegarde, Baseline Profiles, passe de performance et d'accessibilité.

Commence par la phase 1. Avant de coder, pose-moi les questions dont les réponses changeraient l'architecture.
