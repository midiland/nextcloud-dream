# CLAUDE.md — Nextcloud Dream (Android TV)

Économiseur d'écran Android TV (`DreamService`) qui affiche en diaporama les photos d'un dossier Nextcloud, via WebDAV. Usage personnel, installé en sideload sur une Mi Box. Code et commentaires **en français**.

**Langues de l'interface :** anglais par défaut (`res/values/strings.xml`), français dans `res/values-fr/strings.xml`. Toute nouvelle chaîne va dans les **deux** fichiers (lint `MissingTranslation`). Les pluriels utilisent `<plurals>` ; en français, il faut `one`, `many` et `other`. Les messages d'erreur affichés passent par `MainActivity.describeError()`, jamais par `exception.message`. Les logs restent en français.

## Commandes

Le `java` du PATH est un JDK 25, que Gradle 8.14 / AGP 8.13 refusent : **toujours forcer
le JDK 17**. Le SDK Android est dans `~/Android/Sdk` (et non à l'emplacement macOS).
Sur le Mac : SDK dans `~/Library/Android/sdk`, JDK 21 d'Android Studio
(`/Applications/Android Studio.app/Contents/jbr/Contents/Home`), AVD `Android_TV_1080p_API31`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
./gradlew assembleDebug        # APK debug ~11 Mo, logs Timber actifs, run-as possible
./gradlew assembleRelease      # APK release ~1,8 Mo (R8), signé avec la clé debug, logs INFO+ (tag NextcloudDream)
./gradlew testDebugUnitTest    # 84 tests, ~1 min à froid
```

- Debug et release ont la même signature (clé debug). On passe de l'un à l'autre avec `adb install -r`, et les données sont conservées.
- `adb` se trouve dans `~/Android/Sdk/platform-tools/adb`. Les shells non interactifs ne l'ont pas dans leur PATH : utiliser le chemin complet.

**Test de bout en bout sur émulateur** (faux Nextcloud, configuration saisie
automatiquement, économiseur lancé) : `tools/fake-nextcloud/run_emulator_test.sh`. Mode
d'emploi, résultats attendus et erreurs à éviter : **`docs/TESTING_GUIDE.md`**, à lire
avant tout test sur émulateur.

Pour lancer l'économiseur sur un appareil :

```bash
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_components fr.midiland.nextclouddream/.dream.NextcloudDreamService
adb shell am start -n com.android.systemui/.Somnambulator   # démarrage immédiat
adb shell dumpsys dreams | grep mCurrentDreamName           # vérifier que c'est notre service
```

Pour consulter les logs (version debug uniquement), les screenshots et le cache :

```bash
adb logcat --pid=$(adb shell pidof fr.midiland.nextclouddream)
adb exec-out screencap -p > shot.png
adb shell "run-as fr.midiland.nextclouddream ls -la files/photos"    # mettre la commande run-as entre guillemets
```

## Architecture — mode hybride

Package : `fr.midiland.nextclouddream` (dans `app/src/main/java/fr/midiland/nextclouddream/`).

Fonctionnement d'ensemble :
- **La synchro** (`SyncWorker` toutes les 6 h, ou l'écran de config) ne télécharge que l'**index** : la liste WebDAV et les métadonnées EXIF. Elle remplit ensuite le cache de secours jusqu'à 20 photos.
- **L'économiseur** télécharge chaque image **à la volée** (aperçu réduit par Nextcloud) avec **1 photo d'avance**.
- **Le cache de secours** (LRU, 50 Mo par défaut) garde les dernières photos et prend le relais hors connexion.
- **Les vidéos seules** (`.mp4`/`.mov` rattachés à aucune photo) sont lues **en streaming**, muettes, jamais téléchargées ni mises en cache (voir la section « Vidéos seules »).

Les objets partagés passent par **`AppContainer`** (`context.container`) : un seul `SettingsManager` et un seul `PhotoRepository` par processus. Ne jamais instancier ces classes ailleurs. Les classes de `storage/` et de `photos/` reçoivent des `File` et des dépendances (pas de `Context`), ce qui les rend testables sur la JVM.

| Fichier | Rôle |
|---|---|
| `NextcloudDreamApp.kt` | Porte `container`. Timber : `DebugTree` en debug, `ReleaseTree` (INFO et plus, tag `NextcloudDream`) en release. `ImageLoaderFactory` Coil sans cache mémoire ni disque. Planifie `SyncWorker` |
| `AppContainer.kt` | `settings`, `repository` (lazy) ; extension `Context.container` |
| `MainActivity.kt` | Écran de configuration, navigable au D-pad (viewBinding). Sert aussi de `settingsActivity` du dream. **Les flèches haut/bas sont rendues au focus** par `enableDpadNavigation()` : un `EditText` les consomme sinon (déplacement du curseur) et le focus reste bloqué sur un champ. Le premier champ prend le focus au démarrage ; `windowSoftInputMode="stateAlwaysHidden"` empêche le clavier de s'ouvrir tout seul |
| `dream/NextcloudDreamService.kt` | Cycle de vie, UI (messages, horloge, overlay) et boucle d'affichage avec 1 photo d'avance. Boucle protégée par try/catch (pause de 10 s). `when (slide)` : `PhotoSlide` → photo puis `delay(intervalMs)` ; `VideoSlide` → `playVideo` sans `delay` (la vidéo a occupé l'écran), échec → `slides.remove`. Photo illisible → `repository.evict` + `slides.remove` |
| `dream/SlideSource.kt` | Choix de la photo suivante, indépendant d'Android (`PhotoSource` et horloge injectées). `Slide` est scellée : `PhotoSlide` (fichier, clip) ou `VideoSlide` (flux). Playlist de **clés**. `FetchResult` : Ready ; Stream → `VideoSlide` ; Unavailable → photo suivante (10 au plus) ; Offline → `offlineUntil` 5 min, puis cache. Hors ligne, **jusqu'à 2 cycles** sont parcourus pour trouver une photo en cache (1 cycle ne suffit pas : bug trouvé par les tests). Index relu au plus une fois par minute s'il manque des métadonnées |
| `photos/PhotoSource.kt` | Interface implémentée par `PhotoRepository`, pour tester `SlideSource` avec une fausse source. `fetchMotion` n'y est **pas** : la fausse source des tests reste inchangée |
| `photos/MotionRef.kt` | Vidéo d'une photo animée : `Sidecar` (iPhone, fichier à côté), `Trailer` (Pixel, collée au JPEG), `None`. Dans l'index, **null = pas encore examinée**, `None` = examinée sans vidéo |
| `remote/MotionPhoto.kt` | Analyse du XMP d'un JPEG : items du `Container:Directory` **dans l'ordre** → position et longueur de la vidéo. Gère aussi l'ancien `GCamera:MicroVideoOffset`. Indépendant d'Android, testé sur la JVM |
| `ui/MotionLayer.kt` | `TextureView` + `MediaPlayer` muet. `playOnce` : clip de photo animée (fichier local), calé sur la photo. Vidéos seules : `openStream` (prepareAsync, 20 s max), `startVideo` (1re image, 10 s max), `awaitVideoEnd(capMs, minMs)` (fin, plafond, blocage 15 s, boucle si plus courte qu'une photo), `releaseVideo` (sur IO, attendu), `grabFrame`. Aucune dépendance ajoutée (ExoPlayer doublerait l'APK) |
| `photos/VideoStream.kt` | URL + en-têtes d'une vidéo à lire. **Pas une data class** : `toString()` n'expose jamais l'en-tête Authorization |
| `photos/MediaCount.kt` | Résultat de `syncNow()` : photos et vidéos seules (messages de l'écran de configuration) |
| `remote/Mp4Metadata.kt` | Date (`mvhd`, ou `com.apple.quicktime.creationdate`) et position (`udta/©xyz`, ou `com.apple.quicktime.location.ISO6709`) d'une vidéo, par lectures Range. Trouve le `moov` en fin de fichier en sautant de boîte en boîte. Indépendant d'Android, testé sur des boîtes fabriquées |
| `dream/PhotoPlaylist.kt` | Générique `PhotoPlaylist<T>`. Mélange par cycles. Les photos vues récemment (au moins 1) passent en fin du cycle suivant |
| `ui/SlideshowView.kt` | Deux `Slide` (fond flou + photo), fondu de 1,5 s avec `withLayer()`. Règle de cadrage dans `image/Framing`. `playVideo` : ouvre le flux pendant que l'élément courant reste affiché, puis fondu vers la vidéo (portrait : fond flou tiré de sa 1re image). `holdingVideoFrame` : la dernière image d'une vidéo reste figée jusqu'à l'élément suivant, qui la fond en sortie |
| `ui/BlurredBackground.kt` | Flou maison sur 48 px (`RenderEffect` n'existe qu'à partir de l'API 31) |
| `photos/PhotoRepository.kt` | `syncNow(): Result<MediaCount>` : (0) les vidéos seules entrent dans l'index (`isVideo`, `motion = None`) mais jamais dans le préchargement, (1) index sauvé tout de suite, (2) préchargement du cache de secours (borné par `maxCacheMb` / 1 Mo), (3) EXIF, index sauvé toutes les 30 s. `fetchForDisplay()` renvoie un `FetchResult` (vidéo : HEAD seulement, puis `Stream`) ; `evict()`. `getIndex()` est le **seul** point qui retire les vidéos quand `playVideos` est décoché. Aperçu serveur, repli sur l'original seulement si `HttpStatusException.isFileSpecific` |
| `photos/IndexedPhoto.kt`, `PhotoMetadata.kt`, `FetchResult.kt` | Modèles. `PhotoMetadata` : JSON et `formattedDate()` |
| `remote/NextcloudWebDavClient.kt` | PROPFIND (`oc:fileid`, `nc:metadata-files-live-photo`), `downloadPreview`, `readHead` (Range), `downloadRange`, `readRange`, `probe` (HEAD), `streamHeaders()`, `download`. `listPhotos` collecte aussi les `.mov`/`.mp4` ; `pairMedia` (companion, testé) les rattache à leur photo ou en fait des vidéos seules. Basic Auth en UTF-8. `HttpStatusException(code)` avec `isFileSpecific` et `isPermanent` |
| `remote/MultistatusParser.kt` | Lecture de la réponse PROPFIND (`XmlPullParser` injecté, testé avec kxml2) |
| `remote/ExifReader.kt` | Date et GPS EXIF (début du JPEG, ou fichier complet) |
| `remote/PlaceResolver.kt` | `Geocoder` Android, avec cache par zone d'environ 1 km |
| `storage/PhotoCacheManager.kt` | `directory/<key><suffixe>`, avec `key = sha256(url\|etag)[:32]`. Le suffixe est un paramètre : `.jpg` pour les photos, `.mp4` pour les clips (deux instances, deux dossiers). Fichiers temporaires **uniques** (`createTempFile`), fsync puis rename. LRU via `touch`/`trim`. `retainOnly` épargne les `.part` de moins d'une heure |
| `storage/PhotoIndexStore.kt` | `AtomicFile` sur `photo_index.json` |
| `image/Framing.kt`, `image/ImageResizer.kt` | Règle de cadrage partagée ; réduction sur l'appareil avec contournement Android 9 « invalid scale » |
| `settings/SettingsManager.kt` | `SharedPreferences` classiques (`nextcloud_dream_prefs`). `playLivePhotos` (activé par défaut) commande l'animation. `playVideos` (activé) et `maxVideoSeconds` (60 s, de 10 à 600) les vidéos seules. Seul le mot de passe est chiffré (`app_password_encrypted`), via `KeystoreCipher`, puis gardé déchiffré en mémoire |
| `settings/KeystoreCipher.kt` | AES-256-GCM, clé `nextcloud_dream_app_password` dans l'Android Keystore. Format : Base64(IV de 12 octets + texte chiffré). `decrypt` renvoie null si la clé est perdue : seul le mot de passe est alors à ressaisir |
| `update/ReleaseParser.kt` | Lecture de l'API GitHub `/releases/latest` : tag → `versionCode` (**même formule que `parseAppVersion` dans `build.gradle.kts`**, les deux doivent rester d'accord) et URL de l'APK. Indépendant d'Android, testé sur la JVM. Refuse un tag hors format, une release sans APK, et une URL non HTTPS |
| `update/ReleaseChecker.kt` | Requête GitHub et téléchargement de l'APK (OkHttp). Bloquant, à appeler depuis `Dispatchers.IO` |
| `update/ApkInstaller.kt` | Remise de l'APK au `PackageInstaller` du système, autorisation « sources inconnues » (`canRequestPackageInstalls`), seuil d'espace disque. `InstallResultReceiver` ouvre l'écran de confirmation que le système renvoie (`STATUS_PENDING_USER_ACTION`) |
| `worker/SyncWorker.kt` | WorkManager, contrainte réseau. `failure()` sur une erreur permanente, sinon `retry()` (3 au plus) |

**Tests** (`app/src/test`, `./gradlew testDebugUnitTest`, 84 tests, aussi exécutés en CI) : `PhotoPlaylist`, `SlideSource` (en ligne, indisponible, hors ligne avec horloge simulée, cache sans index, métadonnées, vidéos jamais proposées hors ligne), `PhotoCacheManager` (dont une **valeur de référence de `keyFor`**, qui ne doit jamais changer), `PhotoIndexStore` (dont les vidéos), `PhotoMetadata`, `MultistatusParser`, `MediaPairing` (rattachement des vidéos), `Mp4Metadata`, `MotionPhoto`, `ReleaseParser`, `VideoStream`. `testOptions.unitTests.isReturnDefaultValues = true`. org.json et kxml2 sont ajoutés en `testImplementation`.

**Couverture** : `./gradlew jacocoTestReport` (tâche déclarée dans `app/build.gradle.kts`, dépend de `testDebugUnitTest`). Rapports dans `app/build/reports/jacoco/jacocoTestReport/` : HTML pour la lecture, XML à côté. Mesure à la demande, la CI ne s'en sert pas. `enableUnitTestCoverage = true` sur le build type `debug` ; le code généré (R, BuildConfig, viewBinding) est exclu. État au 2026-10-07 : **22,6 % des lignes** (instructions 22,8 %). Par paquet : `storage` 88 %, `dream` 36 %, `remote` 22 %, `photos` 21 %, et 0 % pour `ui`, `settings`, `worker`, `image` et `NextcloudDreamApp`, qui dépendent d'Android et ne sont pas atteignables depuis la JVM.

### Mise à jour depuis l'application

`MainActivity.checkForUpdate()` interroge les releases GitHub à l'ouverture de l'écran de configuration. Le bouton n'apparaît **que** si une version plus récente existe ; une requête en échec ne dit rien, l'écran devant rester utilisable hors ligne. Points à connaître :

- **La signature doit être identique** à celle de la version installée, sinon Android refuse la mise à jour. Tant que la CI signe avec la clé debug du runner (secrets `SIGNING_*` absents), le bouton échoue systématiquement.
- `REQUEST_INSTALL_PACKAGES` dans le manifeste, **plus** l'autorisation par application (« sources inconnues ») que l'utilisateur accorde une fois. Sans elle, on l'envoie sur `ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
- Pas d'installation silencieuse possible : il faudrait être device owner ou application système. L'écran de confirmation du système est incontournable.
- API GitHub non authentifiée : 60 requêtes/heure par IP. Suffisant pour une vérification à l'ouverture de l'écran, pas pour une vérification périodique.
- **Vérifié sur l'émulateur Android TV 9** le 2026-10-08, API GitHub simulée en HTTPS (autorité de confiance installée dans le magasin système, `api.github.com` redirigé par `/system/etc/hosts` + `adb reverse`) : parcours complet 1.0.0 → 1.0.1 avec deux APK signés de la même clé, cas « à jour », et refus propre avec deux clés différentes. Jamais essayé sur la Mi Box.
- Le verdict de l'installation arrive pendant que l'écran système est au premier plan : le récepteur de `MainActivity` est donc inscrit de `onCreate` à `onDestroy`, pas de `onStart` à `onStop` — sinon l'écran reste sur « installation en cours » après un échec (bug trouvé au test).

### Raccourci vers les réglages de l'économiseur

`MainActivity.offerScreensaverSettings()` propose un bouton vers l'écran où l'utilisateur choisit son économiseur — l'étape que l'application ne peut pas faire seule, `screensaver_components` étant dans `Settings.Secure` (permission `WRITE_SECURE_SETTINGS`, système et adb uniquement).

- **Android TV n'a pas l'équivalent du raccourci des notifications.** Le manifeste de TvSettings (AOSP) déclare 25 actions `android.settings.*`, dont `ACTION_NOTIFICATION_LISTENER_SETTINGS`, mais **pas `ACTION_DREAM_SETTINGS`**. Sa `DaydreamActivity` n'a aucun intent-filter, donc elle est inatteignable depuis une application tierce.
- D'où l'enchaînement : `ACTION_DREAM_SETTINGS` d'abord (téléphones, peut-être les variantes Google TV), repli sur `ACTION_SETTINGS` (déclaré → `MainSettings`), bouton masqué si rien ne résout.
- Le bloc `<queries>` du manifeste est **indispensable** : sans lui, la visibilité des paquets (API 30+) ferait renvoyer null à `resolveActivity` et le bouton resterait masqué.
- Autre piste si besoin un jour : `com.google.android.pano.action.SLEEP` (→ `DaydreamVoiceAction`) démarre l'économiseur sélectionné sur-le-champ. C'est la seule action qui fonctionne sur la Mi Box pour le lancer.
- **Non vérifié sur appareil** : le code compile, mais le comportement réel du bouton sur une TV n'a pas été observé.

### Photos animées (Live Photo iPhone, Motion Photo Pixel)

Quand la photo affichée en possède une, un court clip muet se joue une fois, puis on
revient à l'image fixe. Réglage `playLivePhotos`, activé par défaut.

**Tout ce qui suit a été relevé sur le vrai serveur de l'utilisateur** (sondes jetables,
hors dépôt) avant d'écrire une ligne de code :

- **iPhone** : la propriété WebDAV `nc:metadata-files-live-photo` est remplie et donne le
  **fileid** du fichier jumeau — le `.jpg` pointe le `.mov` et réciproquement. Aucun
  appairage par nom à deviner ; le repli par nom de base ne sert qu'aux serveurs
  antérieurs à Nextcloud 29, et se limite aux `.mov` de moins de 20 Mo.
- **Pixel** : le MP4 est collé à la fin du JPEG et décrit par le `Container:Directory` du
  XMP. **Offset depuis la fin = somme des `Length`+`Padding` de l'item vidéo et de tous
  les suivants.** Vérifié au octet près sur un fichier réel de 6 281 220 octets : items
  `Primary` (0), `GainMap` (37 395), `MotionPhoto` (3 612 543) ; le Range calculé renvoie
  bien `ftyp`/`isom`.
- **La détection ne coûte aucune requête** : les `.mov` sont déjà dans la réponse PROPFIND
  (ils étaient simplement jetés par le filtre d'extension), et le XMP du Pixel est dans
  les 256 Ko que `readHead` télécharge déjà pour l'EXIF.
- Le serveur **accepte les Range en fin de fichier**, ce qui permet de récupérer la vidéo
  sans télécharger la photo.

**Trois pièges, chacun couvert par un test :**

1. `GCamera` ou `Container:Directory` présents **ne signifient pas** photo animée : les
   photos **Ultra HDR** récentes ont un Container qui ne décrit qu'une carte de gain. Sur
   les deux fichiers Pixel testés, un seul était animé. Il faut l'item `video/mp4`.
2. Chercher le premier `Length` près de `video/mp4` renvoie la longueur de la **carte de
   gain**. Il faut parcourir les items dans l'ordre.
3. Le JPEG contient un second paquet XMP (le XMP **étendu**), découpé en segments de
   64 Ko, donc tronqué dans ce qu'on lit : il doit être ignoré sans bruit.

**Lecture.** `MediaPlayer` sur une `TextureView`, volume à zéro, **sans demander le focus
audio** (ne pas couper la musique de l'utilisateur). Pas de rotation à appliquer soi-même,
bien que ce soit souvent conseillé : les dimensions annoncées par `MediaPlayer` sont déjà
celles de l'affichage (un `.mov` codé 1280×720 avec rotation 90° est annoncé 720×1280) et
l'image arrive déjà redressée — tourner une seconde fois donnait une vidéo couchée et
étirée en plein écran (constaté à l'écran sur Android 9, corrigé). Pour un Motion Photo, la lecture
commence à `MotionPhotoPresentationTimestampUs`, c'est-à-dire **sur l'image fixe
elle-même** : le passage photo → vidéo est invisible. Retour à la photo par un fondu de
300 ms.

### Invariants à respecter

- **Écriture atomique.** Le cache et l'index n'exposent jamais de fichier incomplet. Les fichiers temporaires `*.part` sont renommés à la fin. `listPhotos()` ne liste que les `*.jpg`.
- **La clé du cache est stable.** `keyFor(url, etag)` donne le même résultat qu'avant le mode hybride, donc les anciens caches sont réutilisés. Si la photo est modifiée sur Nextcloud, l'ETag change, donc la clé aussi, et l'ancienne version est purgée.
- **Purge seulement après un listing réussi.** `retainOnly` n'est appelé qu'après un PROPFIND réussi. Un serveur injoignable ne doit jamais vider le cache ni l'index.
- **Stockage.** Aucun téléchargement si `StorageManager.getAllocatableBytes` est sous 30 Mo. Android garde une réserve d'environ 5 % : sur la Mi Box, il faut environ 290 Mo libres dans `df` pour pouvoir écrire.
- **Mémoire.** Au plus deux bitmaps vivent en même temps dans `SlideshowView`, plus une image préchargée sur disque (pas en mémoire). Ne pas activer le cache mémoire de Coil.
- **Coroutines.** Ne pas avaler `CancellationException` (voir `syncNow` et `fetchForDisplay`).
- **Les clips sont un bonus, jamais une dépendance.** `fetchMotion` ne lève jamais et rend
  null dès que quelque chose manque (réglage décoché, stockage saturé, serveur injoignable,
  format inattendu) : la photo s'affiche alors sans animation, et rien d'autre ne change.
  Vérifié : réglage décoché → 0 clip, 0 téléchargement ; serveur coupé → le diaporama
  continue sur le cache avec un simple avertissement.
- **Les clips ne vont pas dans le cache de secours.** Dossier `files/motion` à part, 20 Mo,
  son propre LRU : à 3,5 Mo (Pixel) ou 6 Mo (iPhone) pièce, ils videraient de ses photos le
  cache de 50 Mo qui fait tenir le diaporama hors connexion. Un `.mov` de plus de 20 Mo
  n'est pas rattaché par son nom : trop gros pour être un clip, il est joué comme vidéo seule.
- **Les vidéos seules ne touchent jamais le disque** : ni cache, ni préchargement, ni
  téléchargement. Seuls quelques Ko de boîtes MP4 sont lus en mémoire à la synchro.
- **Le mot de passe ne sort que dans l'en-tête Authorization** : jamais dans une URL
  (MediaPlayer journalise les URL), jamais dans un log (`VideoStream.toString()`).
  Vérifié : 0 occurrence de « authorization » ou du mot de passe dans logcat.
- **Délais : `withTimeoutOrNull`, jamais `withTimeout`** dans le diaporama. Le
  dépassement de `withTimeout` est une `TimeoutCancellationException`, donc une
  `CancellationException` : relancée comme une vraie annulation, elle **arrêtait tout le
  diaporama** (écran noir). Bug trouvé au test, serveur coupé pendant une vidéo.
- **Le lecteur d'une vidéo est libéré (sur IO, attendu) avant que le suivant ne se
  connecte à la même Surface**, et `prepare()` n'est jamais bloquant (`prepareAsync`).
- **Un clip ne déborde jamais sur la photo suivante** : son job est annulé avant chaque
  nouvelle photo.

### Mesures (18 photos de test)

- Aperçus serveur : 200 Ko à 2,2 Mo, environ 1 Mo en moyenne. Le cache de secours de 50 Mo tient donc environ 50 photos.
- Lecture EXIF par Range sur 18 photos : environ 6 s. 15 sur 18 ont un GPS, et les lieux sont correctement résolus.

### Vidéos seules

Lues en streaming par `MediaPlayer.setDataSource(context, uri, headers)`, muettes, sans
focus audio. Décisions prises avec l'utilisateur : streaming (la Mi Box n'a pas la place),
lecture en entier avec un plafond réglable (60 s), date et lieu comme pour les photos.

- **Rattachement** (`pairMedia`) : une vidéo est « seule » si aucune photo ne la désigne
  (fileid ou nom de base) **et** si son propre `livePhotoFileId` ne désigne pas une photo
  du listing (il suffit qu'un côté de la paire le dise).
- **Affichage** : HEAD au moment de choisir l'élément (`probe` → `Stream`, `Unavailable`
  ou `Offline`, comme une photo), ouverture du flux pendant que l'élément courant reste à
  l'écran, puis fondu. Paysage recadré, portrait en entier sur le flou de sa 1re image.
- **Durée** : jusqu'à la fin ; coupée en fondu au plafond ; **une vidéo plus courte que la
  durée d'une photo tourne en boucle** (`isLooping`) jusqu'à l'atteindre, puis s'arrête à
  la fin du tour en cours (jamais au milieu). Le plafond reste prioritaire.
- **Échecs** : flux trop long à ouvrir (20 s) ou à démarrer (10 s), position immobile
  15 s, erreur MediaPlayer → vidéo écartée de ce cycle, l'élément courant reste affiché.
- **Hors connexion** : jamais en cache, donc sautées ; `cachedSlide` ignore même un
  fichier mis en cache sous la clé d'une vidéo par une version antérieure.

**Vérifié le 2026-10-12** sur l'émulateur Android TV API 31 (Mac), contre un **faux
serveur Nextcloud** local (PROPFIND, GET/HEAD avec Range, aperçus, Basic auth, journal de
chaque requête), joint par `adb reverse tcp:8080`. Ce serveur est maintenant versionné dans
`tools/fake-nextcloud/` (voir « Test de bout en bout sur émulateur ») :

- Synchro : 4 photos (dont 1 Live Photo correctement rattachée) et 7 vidéos seules ;
  date et lieu lus pour le `.mp4` à clés iPhone (Paris) et le `.mov` à `©xyz` (Kyoto),
  `moov` en fin de fichier compris, sans jamais télécharger une vidéo.
- **Toutes les requêtes du lecteur vidéo (`stagefright`) portent l'authentification** ;
  pour un `moov` en fin de fichier, il fait lui-même une requête Range vers la fin.
- H.264, HEVC `.mov`, portrait (rotation), boucle d'une vidéo de 3 s (3 tours pour 8 s),
  plafond (25 s et 3 min coupées à 20 s), fichier tronqué écarté, deux vidéos d'affilée.
- Serveur coupé pendant une vidéo → bascule sur le cache ; serveur qui ne répond jamais
  au lecteur → abandon en 20 s, le diaporama continue ; touche pendant une ouverture
  bloquée → économiseur fermé en 0,6 s, pas d'ANR. Réglage décoché → 0 vidéo, 0 requête.
- APK release : +26 Ko.

`app/src/debug/` autorise le HTTP en clair vers `localhost` / `127.0.0.1`, **build debug
seulement** (pour ce faux serveur). Le clavier virtuel de l'émulateur capte les touches
pendant la saisie par adb : le désactiver le temps du test (`adb shell ime disable …`).

**Non vérifié** : un vrai serveur Nextcloud (redirections éventuelles, en-têtes conservés
par `MediaHTTPConnection` sur Android 9), de vraies vidéos d'iPhone (HEVC/Dolby Vision) et
la Mi Box (débit Wi-Fi, décodage matériel).

## CI / releases

- `.github/workflows/release.yml` se déclenche sur les tags `v[0-9]*` / `V[0-9]*`, puis vérifie que le tag vaut bien `[vV]xx.xxx.xxx` (sinon le build échoue). Il exécute `./gradlew assembleRelease -PappVersion=$GITHUB_REF_NAME`, puis publie une GitHub Release avec l'APK sous le nom **constant** `nextcloud-dream.apk` (sans version : c'est ce qui rend `releases/latest/download/nextcloud-dream.apk` utilisable comme lien permanent, utilisé par le bouton du README). Pas de notion de release candidate : toutes les releases sont finales.
- **Version :** `app/build.gradle.kts` → `parseAppVersion()`. Format `[vV]xx.xxx.xxx` (majeur sur 1 à 2 chiffres, mineur et patch sur 1 à 3), `versionCode = MMmmmppp` (99.999.999 au plus, donc bien sous la limite d'un `Int`), et `0.0.0-dev` / `1` sans `-PappVersion`. Vérifié : `v1.2.3` → `1002003`, `V1.10.00` → `1010000`, `V12.345.678` → `12345678`, `V1.2` → erreur, `V1.2.3-rc.4` → erreur, `V1.1234.0` → erreur.
- **Signature :** si `SIGNING_KEYSTORE_PATH` est défini (avec `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`), le signingConfig `release` est utilisé, sinon la clé debug — ce repli ne vaut que pour les builds locaux. En CI, les quatre valeurs viennent des **secrets** GitHub (onglet *Secrets*, pas *Variables* : `secrets.X` ne lit jamais une repository variable) ; `SIGNING_KEYSTORE_BASE64` est décodé dans `$RUNNER_TEMP`, hors du workspace, et le magasin est ouvert par `keytool` pour échouer tout de suite si le base64 est tronqué ou l'alias absent. **Le workflow échoue si un secret manque** : une release signée par la clé debug du runner serait impossible à mettre à jour (clé différente à chaque build). Les messages d'erreur ne contiennent aucune valeur, les logs d'un dépôt public étant lisibles par tous.
- ⚠️ La Mi Box a actuellement une version signée avec la **clé debug locale**. Le premier APK de CI signé avec une autre clé imposera un `adb uninstall`, donc la configuration sera à ressaisir.
- ⚠️ Le package a été renommé de `com.nextclouddream` en `fr.midiland.nextclouddream` le 2026-10-06. Pour Android c'est une **application différente** : sur l'émulateur et sur la Mi Box il faut faire `adb uninstall com.nextclouddream` (l'ancienne reste installée et visible sinon), ressaisir la configuration, et repointer `screensaver_components` sur le nouveau composant. Le cache et l'index repartent de zéro. C'est ce renommage qui a rendu `LegacySettingsMigration` définitivement inutile — elle lisait les préférences dans le répertoire du nouveau package, toujours vide — d'où sa suppression le 2026-10-07, avec la dépendance `security-crypto` (APK : 1,96 Mo → 1,72 Mo).
- Le dépôt est `git@github.com:midiland/nextcloud-dream.git`, destiné à devenir public.

## Icône et bannière

Les deux sont **obligatoires** sur Android TV et déclarées dans le manifeste (`android:icon`, `android:banner`). Générées depuis `nextcloud-dream.png` (564×566, fond transparent) aux tailles des guides Android TV, dans `res/mipmap-*` :

| Densité | Icône (1:1) | Bannière (16:9) |
|---|---|---|
| mdpi | 80×80 | 160×90 |
| hdpi | 120×120 | 240×135 |
| xhdpi | 160×160 | 320×180 |
| xxhdpi | 240×240 | 480×270 |
| xxxhdpi | 320×320 | 640×360 |

- **Format WebP** (q95 pour l'icône avec alpha, q90 pour la bannière) : 91 Ko pour les dix fichiers, contre 574 Ko en PNG. Pris en charge depuis bien avant minSdk 28.
- **La bannière doit contenir le nom de l'application** — c'est elle que le launcher Android TV affiche, pas l'icône. Elle est composée du logo net sur un fond repris du logo, flouté et assombri (même procédé que `BlurredBackground`), avec une marge de sécurité : le logo occupe 86 % de la hauteur, rien ne touche les bords.
- `docs/banner.webp` (1280×400) est le bandeau du README, composé de la même façon. Hors de `res/`, il ne pèse donc pas sur l'APK.
- Pas d'icône adaptative : le logo fourni est un carré arrondi plein cadre dont le texte descend jusqu'à 85 % de la hauteur, donc en dehors de la zone sûre de 72/108 dp. Un masque circulaire couperait « DREAM ». Il faudrait un logo en calques (sujet seul + fond) pour en faire une.

## Versions (alignées sur les outils installés sur cette machine)

- AGP 8.13.0, Kotlin 2.0.21, Gradle 8.14.3 (déjà en cache dans `~/.gradle`), compileSdk/targetSdk 36, minSdk 28.
- Coil **2.7** (API `coil.*`, pas Coil 3), OkHttp 4.12, WorkManager 2.10, Timber 5.
- `proguard-rules.pro` ne contient plus aucune règle : les `-dontwarn` ne servaient qu'à Tink (`security-crypto`), retiré.

## Environnements de test

### Émulateur : `tv28`

- Image `system-images;android-28;android-tv;x86`, soit **Android TV 9, la même version que la Mi Box** — c'est ce qui rend les essais représentatifs. La machine est un Linux x86_64, donc l'image x86 démarre (sur un Mac Apple Silicon il faudrait une image arm64, donc API 30 ou plus).
- **Clavier :** `hw.keyboard = yes` dans `~/.android/avd/tv28.avd/config.ini`, sinon le clavier de la machine n'est pas transmis. Clavier AZERTY : la saisie peut être mal mappée, utiliser `adb shell input text "..."`.
- Une configuration Nextcloud de test y est saisie, sur un dossier de 3 photos.
- Pour tester sans serveur, copier des JPEG dans le cache : `adb push` vers `/data/local/tmp`, puis `run-as ... cp` dans `files/photos/`. Il faut la version debug.

### Mi Box : `192.168.1.XX:5555`

- Modèle MIBOX4 (Mi Box S), Android 9, ABI **armeabi-v7a**. L'IP vient du DHCP et peut changer.
- **Stockage quasi plein** (~260 Mo libres sur 4,9 Go). Une installation en échec avec `Requested internal only, but not enough space` vient du seuil de réserve d'Android (~5 %), pas de la taille de l'APK. Installer la **release** (1,9 Mo).
- Des applications ont été désinstallées pour libérer de la place.
- L'économiseur précédent était `com.google.android.backdrop/com.google.android.backdrop.Backdrop`. Il est maintenant remplacé par le nôtre.
- **Démarrer l'économiseur sur la box :** `Somnambulator` ne déclenche rien, et `service call dreams 1` est refusé (permission `WRITE_DREAM_STATE`). Ce qui **fonctionne** passe par l'action « veille » des réglages TV, qui elle a la permission :
  `adb shell am start -a com.google.android.pano.action.SLEEP -n com.android.tv.settings/.device.display.daydream.DaydreamVoiceAction`
- Sur la box, le compte Nextcloud utilisé est distinct de celui de l'émulateur. Ce compte voit bien le dossier : 18 photos trouvées. **Synchro bloquée par `hasRoomForDownload`** (confirmé dans les logs : « Stockage de l'appareil presque plein »). Avec 260 Mo libres, l'espace allouable hors réserve système est presque nul.
- Une installation (`install -r`) arrête l'économiseur s'il est en cours d'exécution, c'est le comportement normal.
- La version release y est installée. La configuration Nextcloud doit être saisie sur la box ; conseiller un cache de 50 Mo.

## Pièges rencontrés

- En zsh, la variable `path` est liée à `PATH` : ne jamais l'utiliser comme variable de boucle dans un script.
- `run-as` ne fonctionne que sur un APK debuggable. Sa commande doit être entre guillemets : `adb shell "run-as pkg sh -c '...'"`.
- `dumpsys diskstats` donne des tailles d'apps **mises en cache par le système** (parfois anciennes). Pour l'espace réel, utiliser `df /data`.
- `TextClock` suit le format 12/24 h de l'appareil. L'émulateur est en en-US, donc il affiche « 6:43 » et « Sunday 4 October ».

## Métadonnées photo (date / lieu) — résultats des tests du 2026-10-04

- Le serveur Nextcloud de l'utilisateur renvoie **404** pour toutes les propriétés `nc:metadata-photos-*` (original_date_time, gps, place, size, exif) et `nc:file-metadata-*`. Il n'y a donc pas de métadonnées côté serveur : il faut lire l'EXIF des originaux.
- `nc:creation_time` vaut 0. `getlastmodified` est identique pour toutes les photos (14/01/2022), donc inutilisable comme date de prise de vue.
- Photos de test issues d'un appareil photo compact : `DateTimeOriginal` est présent, **pas de GPS**.
- `ImageResizer` réencode en JPEG et **efface l'EXIF**. Les métadonnées doivent être lues sur l'original **avant** la réduction (dans `PhotoCacheManager.store`).
- La dépendance `androidx.exifinterface:exifinterface:1.4.1` est déjà ajoutée.
- Géocodeur Android : `com.google.android.gms/.location.geocode.GeocodeService` est présent sur la Mi Box et sur l'émulateur. **Testé sur l'émulateur : il fonctionne** (noms de villes correctement résolus). Il n'a pas encore été testé sur la Mi Box.
- Les photos prises au smartphone ont un GPS ; celles de l'appareil photo n'ont que la date.
- **Note :** les fichiers `<hash>.json` à côté des photos (pré-hybride) n'existent plus. Les métadonnées sont dans l'index, et `retainOnly` supprime les anciens `.json`.
- **Implémenté :** overlay en bas à gauche (date au format long de la langue de l'appareil, lieu en dessous), réglage `showPhotoInfo`, fondu synchronisé avec la photo via le callback `onFadeStart` de `showPhoto`.

## Reste à faire / non vérifié

- **Question ouverte :** le Nextcloud de l'utilisateur est-il en HTTPS valide ? Si c'est HTTP ou un certificat auto-signé, il faut ajouter un `network_security_config`. Aujourd'hui seul le HTTPS valide fonctionne. L'émulateur synchronise correctement, ce qui suggère du HTTPS valide.
- Le fondu n'a jamais été capturé pendant une transition. Il est dans le code mais n'a pas été vérifié visuellement.
- Le LRU du cache de secours (`trim`) n'a pas été testé en conditions réelles : 18 photos, soit environ 18 Mo, restent sous les 50 Mo.
- Repli hors connexion testé sur l'émulateur (`svc wifi disable`) : le diaporama bascule bien sur le cache.
- Sur la box, 4 originaux non réduits restent dans le cache (avant le correctif « invalid scale »). Ils seront évincés par le LRU.
- HEIC, fluidité et mémoire n'ont pas été vérifiés sur la Mi Box réelle.
- **Photos animées, ce qui reste à vérifier.** Le parcours complet a été observé sur
  l'émulateur Android TV 9 (API 28, la version de la Mi Box) avec des fichiers fabriqués
  pour l'occasion : Motion
  Photo avec carte de gain, leurre Ultra HDR, paire `.jpg`+`.mov` avec rotation, détection
  et cadrage corrects, réglage décoché, serveur coupé. **Jamais essayé sur de vrais
  fichiers d'appareil** : le `.mov` de test est un MP4/H.264 renommé, là où un iPhone
  produit du QuickTime/HEVC — à confirmer sur la box, de même que la fluidité du décodage.
  La Mi Box n'aura de toute façon probablement pas la place (~260 Mo libres) : la
  fonctionnalité s'y effacera d'elle-même, c'est voulu.
- Dans le dossier de test de l'utilisateur, **2 fichiers sur 22** sont animés : l'effet
  restera discret sur un dossier mêlant d'anciennes photos et des prises d'appareil photo.
- Logs en release : `ReleaseTree` (INFO et au-dessus, tag `NextcloudDream`) → `adb logcat -s NextcloudDream:V`.
