# CLAUDE.md — Nextcloud Dream (Android TV)

Économiseur d'écran Android TV (`DreamService`) qui affiche en diaporama les photos d'un dossier Nextcloud, via WebDAV. Usage personnel, installé en sideload sur une Mi Box. Code et commentaires **en français**.

**Langues de l'interface :** anglais par défaut (`res/values/strings.xml`), français dans `res/values-fr/strings.xml`. Toute nouvelle chaîne va dans les **deux** fichiers (lint `MissingTranslation`). Les pluriels utilisent `<plurals>` ; en français, il faut `one`, `many` et `other`. Les messages d'erreur affichés passent par `MainActivity.describeError()`, jamais par `exception.message`. Les logs restent en français.

## Commandes

Il n'y a pas de `java` utilisable dans le PATH système : utiliser le JDK d'Android Studio.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug        # APK debug ~14 Mo, logs Timber actifs, run-as possible
./gradlew assembleRelease      # APK release ~1,9 Mo (R8), signé avec la clé debug, logs INFO+ (tag NextcloudDream)
```

- Debug et release ont la même signature (clé debug). On passe de l'un à l'autre avec `adb install -r`, et les données sont conservées.
- `adb` se trouve dans `~/Library/Android/sdk/platform-tools/adb`. Il est ajouté au PATH dans `~/.zshrc`, mais les shells non interactifs ne le voient pas : utiliser le chemin complet.

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

Les objets partagés passent par **`AppContainer`** (`context.container`) : un seul `SettingsManager` et un seul `PhotoRepository` par processus. Ne jamais instancier ces classes ailleurs. Les classes de `storage/` et de `photos/` reçoivent des `File` et des dépendances (pas de `Context`), ce qui les rend testables sur la JVM.

| Fichier | Rôle |
|---|---|
| `NextcloudDreamApp.kt` | Porte `container`. Timber : `DebugTree` en debug, `ReleaseTree` (INFO et plus, tag `NextcloudDream`) en release. `ImageLoaderFactory` Coil sans cache mémoire ni disque. Planifie `SyncWorker` |
| `AppContainer.kt` | `settings`, `repository` (lazy) ; extension `Context.container` |
| `MainActivity.kt` | Écran de configuration, navigable au D-pad (viewBinding). Sert aussi de `settingsActivity` du dream. **Les flèches haut/bas sont rendues au focus** par `enableDpadNavigation()` : un `EditText` les consomme sinon (déplacement du curseur) et le focus reste bloqué sur un champ. Le premier champ prend le focus au démarrage ; `windowSoftInputMode="stateAlwaysHidden"` empêche le clavier de s'ouvrir tout seul |
| `dream/NextcloudDreamService.kt` | Cycle de vie, UI (messages, horloge, overlay) et boucle d'affichage avec 1 photo d'avance. Boucle protégée par try/catch (pause de 10 s). Photo illisible → `repository.evict` + `slides.remove` |
| `dream/SlideSource.kt` | Choix de la photo suivante, indépendant d'Android (`PhotoSource` et horloge injectées). Playlist de **clés**. `FetchResult` : Ready ; Unavailable → photo suivante (10 au plus) ; Offline → `offlineUntil` 5 min, puis cache. Hors ligne, **jusqu'à 2 cycles** sont parcourus pour trouver une photo en cache (1 cycle ne suffit pas : bug trouvé par les tests). Index relu au plus une fois par minute s'il manque des métadonnées |
| `photos/PhotoSource.kt` | Interface implémentée par `PhotoRepository`, pour tester `SlideSource` avec une fausse source |
| `dream/PhotoPlaylist.kt` | Générique `PhotoPlaylist<T>`. Mélange par cycles. Les photos vues récemment (au moins 1) passent en fin du cycle suivant |
| `ui/SlideshowView.kt` | Deux `Slide` (fond flou + photo), fondu de 1,5 s avec `withLayer()`. Règle de cadrage dans `image/Framing` |
| `ui/BlurredBackground.kt` | Flou maison sur 48 px (`RenderEffect` n'existe qu'à partir de l'API 31) |
| `photos/PhotoRepository.kt` | `syncNow()` : (1) index sauvé tout de suite, (2) préchargement du cache de secours (borné par `maxCacheMb` / 1 Mo), (3) EXIF, index sauvé toutes les 30 s. `fetchForDisplay()` renvoie un `FetchResult` ; `evict()`. Aperçu serveur, repli sur l'original seulement si `HttpStatusException.isFileSpecific` |
| `photos/IndexedPhoto.kt`, `PhotoMetadata.kt`, `FetchResult.kt` | Modèles. `PhotoMetadata` : JSON et `formattedDate()` |
| `remote/NextcloudWebDavClient.kt` | PROPFIND (`oc:fileid`), `downloadPreview`, `readHead` (Range), `download`. Basic Auth en UTF-8. `HttpStatusException(code)` avec `isFileSpecific` et `isPermanent` |
| `remote/MultistatusParser.kt` | Lecture de la réponse PROPFIND (`XmlPullParser` injecté, testé avec kxml2) |
| `remote/ExifReader.kt` | Date et GPS EXIF (début du JPEG, ou fichier complet) |
| `remote/PlaceResolver.kt` | `Geocoder` Android, avec cache par zone d'environ 1 km |
| `storage/PhotoCacheManager.kt` | `directory/<key>.jpg`, avec `key = sha256(url\|etag)[:32]`. Fichiers temporaires **uniques** (`createTempFile`), fsync puis rename. LRU via `touch`/`trim`. `retainOnly` épargne les `.part` de moins d'une heure |
| `storage/PhotoIndexStore.kt` | `AtomicFile` sur `photo_index.json` |
| `image/Framing.kt`, `image/ImageResizer.kt` | Règle de cadrage partagée ; réduction sur l'appareil avec contournement Android 9 « invalid scale » |
| `settings/SettingsManager.kt` | `SharedPreferences` classiques (`nextcloud_dream_prefs`). Seul le mot de passe est chiffré (`app_password_encrypted`), via `KeystoreCipher`, puis gardé déchiffré en mémoire |
| `settings/KeystoreCipher.kt` | AES-256-GCM, clé `nextcloud_dream_app_password` dans l'Android Keystore. Format : Base64(IV de 12 octets + texte chiffré). `decrypt` renvoie null si la clé est perdue : seul le mot de passe est alors à ressaisir |
| `update/ReleaseParser.kt` | Lecture de l'API GitHub `/releases/latest` : tag → `versionCode` (**même formule que `parseAppVersion` dans `build.gradle.kts`**, les deux doivent rester d'accord) et URL de l'APK. Indépendant d'Android, testé sur la JVM. Refuse un tag hors format, une release sans APK, et une URL non HTTPS |
| `update/ReleaseChecker.kt` | Requête GitHub et téléchargement de l'APK (OkHttp). Bloquant, à appeler depuis `Dispatchers.IO` |
| `update/ApkInstaller.kt` | Remise de l'APK au `PackageInstaller` du système, autorisation « sources inconnues » (`canRequestPackageInstalls`), seuil d'espace disque. `InstallResultReceiver` ouvre l'écran de confirmation que le système renvoie (`STATUS_PENDING_USER_ACTION`) |
| `worker/SyncWorker.kt` | WorkManager, contrainte réseau. `failure()` sur une erreur permanente, sinon `retry()` (3 au plus) |

**Tests** (`app/src/test`, `./gradlew testDebugUnitTest`, 30 tests, aussi exécutés en CI) : `PhotoPlaylist`, `SlideSource` (en ligne, indisponible, hors ligne avec horloge simulée, cache sans index, métadonnées), `PhotoCacheManager` (dont une **valeur de référence de `keyFor`**, qui ne doit jamais changer), `PhotoIndexStore`, `PhotoMetadata`, `MultistatusParser`. `testOptions.unitTests.isReturnDefaultValues = true`. org.json et kxml2 sont ajoutés en `testImplementation`.

**Couverture** : `./gradlew jacocoTestReport` (tâche déclarée dans `app/build.gradle.kts`, dépend de `testDebugUnitTest`). Rapports dans `app/build/reports/jacoco/jacocoTestReport/` : HTML pour la lecture, XML à côté. Mesure à la demande, la CI ne s'en sert pas. `enableUnitTestCoverage = true` sur le build type `debug` ; le code généré (R, BuildConfig, viewBinding) est exclu. État au 2026-10-07 : **22,6 % des lignes** (instructions 22,8 %). Par paquet : `storage` 88 %, `dream` 36 %, `remote` 22 %, `photos` 21 %, et 0 % pour `ui`, `settings`, `worker`, `image` et `NextcloudDreamApp`, qui dépendent d'Android et ne sont pas atteignables depuis la JVM.

### Mise à jour depuis l'application

`MainActivity.checkForUpdate()` interroge les releases GitHub à l'ouverture de l'écran de configuration. Le bouton n'apparaît **que** si une version plus récente existe ; une requête en échec ne dit rien, l'écran devant rester utilisable hors ligne. Points à connaître :

- **La signature doit être identique** à celle de la version installée, sinon Android refuse la mise à jour. Tant que la CI signe avec la clé debug du runner (secrets `SIGNING_*` absents), le bouton échoue systématiquement.
- `REQUEST_INSTALL_PACKAGES` dans le manifeste, **plus** l'autorisation par application (« sources inconnues ») que l'utilisateur accorde une fois. Sans elle, on l'envoie sur `ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
- Pas d'installation silencieuse possible : il faudrait être device owner ou application système. L'écran de confirmation du système est incontournable.
- API GitHub non authentifiée : 60 requêtes/heure par IP. Suffisant pour une vérification à l'ouverture de l'écran, pas pour une vérification périodique.
- **Vérifié sur l'émulateur Android TV 9** le 2026-10-08, API GitHub simulée en HTTPS (autorité de confiance installée dans le magasin système, `api.github.com` redirigé par `/system/etc/hosts` + `adb reverse`) : parcours complet 1.0.0 → 1.0.1 avec deux APK signés de la même clé, cas « à jour », et refus propre avec deux clés différentes. Jamais essayé sur la Mi Box.
- Le verdict de l'installation arrive pendant que l'écran système est au premier plan : le récepteur de `MainActivity` est donc inscrit de `onCreate` à `onDestroy`, pas de `onStart` à `onStop` — sinon l'écran reste sur « installation en cours » après un échec (bug trouvé au test).

### Invariants à respecter

- **Écriture atomique.** Le cache et l'index n'exposent jamais de fichier incomplet. Les fichiers temporaires `*.part` sont renommés à la fin. `listPhotos()` ne liste que les `*.jpg`.
- **La clé du cache est stable.** `keyFor(url, etag)` donne le même résultat qu'avant le mode hybride, donc les anciens caches sont réutilisés. Si la photo est modifiée sur Nextcloud, l'ETag change, donc la clé aussi, et l'ancienne version est purgée.
- **Purge seulement après un listing réussi.** `retainOnly` n'est appelé qu'après un PROPFIND réussi. Un serveur injoignable ne doit jamais vider le cache ni l'index.
- **Stockage.** Aucun téléchargement si `StorageManager.getAllocatableBytes` est sous 30 Mo. Android garde une réserve d'environ 5 % : sur la Mi Box, il faut environ 290 Mo libres dans `df` pour pouvoir écrire.
- **Mémoire.** Au plus deux bitmaps vivent en même temps dans `SlideshowView`, plus une image préchargée sur disque (pas en mémoire). Ne pas activer le cache mémoire de Coil.
- **Coroutines.** Ne pas avaler `CancellationException` (voir `syncNow` et `fetchForDisplay`).

### Mesures (18 photos de test)

- Aperçus serveur : 200 Ko à 2,2 Mo, environ 1 Mo en moyenne. Le cache de secours de 50 Mo tient donc environ 50 photos.
- Lecture EXIF par Range sur 18 photos : environ 6 s. 15 sur 18 ont un GPS, et les lieux sont correctement résolus.

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

## Versions (alignées sur les outils installés sur ce Mac)

- AGP 8.13.0, Kotlin 2.0.21, Gradle 8.14.3 (déjà en cache dans `~/.gradle`), compileSdk/targetSdk 36, minSdk 28.
- Coil **2.7** (API `coil.*`, pas Coil 3), OkHttp 4.12, WorkManager 2.10, Timber 5.
- `proguard-rules.pro` ne contient plus aucune règle : les `-dontwarn` ne servaient qu'à Tink (`security-crypto`), retiré.

## Environnements de test

### Émulateur : `Android_TV_1080p_API31`

- Image `system-images;android-31;android-tv;arm64-v8a`. Le Mac est Apple Silicon : les images TV API 28 n'existent qu'en x86 et ne démarrent pas.
- Créé avec `avdmanager` (cmdline-tools installés dans `~/Library/Android/sdk/cmdline-tools/latest`).
- **Clavier :** il a fallu passer `hw.keyboard = yes` dans `~/.android/avd/Android_TV_1080p_API31.avd/config.ini`, sinon le clavier du Mac n'est pas transmis. Clavier AZERTY : la saisie peut être mal mappée, utiliser `adb shell input text "..."`.
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
- Logs en release : `ReleaseTree` (INFO et au-dessus, tag `NextcloudDream`) → `adb logcat -s NextcloudDream:V`.
