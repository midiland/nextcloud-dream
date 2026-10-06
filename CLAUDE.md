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
| `MainActivity.kt` | Écran de configuration, navigable au D-pad (viewBinding). Sert aussi de `settingsActivity` du dream |
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
| `settings/LegacySettingsMigration.kt` | Migration unique depuis l'ancien `nextcloud_dream_secure_prefs` (EncryptedSharedPreferences), puis suppression du fichier et de la clé `_androidx_security_master_key_`. **Seule raison d'être de la dépendance `security-crypto`** : à retirer avec elle une fois tous les appareils migrés (émulateur et Mi Box migrés le 2026-10-04) |
| `worker/SyncWorker.kt` | WorkManager, contrainte réseau. `failure()` sur une erreur permanente, sinon `retry()` (3 au plus) |

**Tests** (`app/src/test`, `./gradlew testDebugUnitTest`, 30 tests, aussi exécutés en CI) : `PhotoPlaylist`, `SlideSource` (en ligne, indisponible, hors ligne avec horloge simulée, cache sans index, métadonnées), `PhotoCacheManager` (dont une **valeur de référence de `keyFor`**, qui ne doit jamais changer), `PhotoIndexStore`, `PhotoMetadata`, `MultistatusParser`. `testOptions.unitTests.isReturnDefaultValues = true`. org.json et kxml2 sont ajoutés en `testImplementation`.

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

- `.github/workflows/release.yml` se déclenche sur les tags `v[0-9]*` / `V[0-9]*`. Il exécute `./gradlew assembleRelease -PappVersion=$GITHUB_REF_NAME`, puis publie une GitHub Release (pré-release si `-rc.`) avec `nextcloud-dream-<version>.apk`.
- **Version :** `app/build.gradle.kts` → `parseAppVersion()`. Format `[vV]xx.xx.xx[-rc.xx]`, `versionCode = MMmmpp` + `rc` (`99` pour une finale), et `0.0.0-dev` / `1` sans `-PappVersion`. Vérifié : `V1.02.03-rc.04` → `1020304`, `v1.2.3` → `1020399`, `V1.2` → erreur.
- **Signature :** si `SIGNING_KEYSTORE_PATH` est défini (avec `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`), le signingConfig `release` est utilisé, sinon la clé debug. Vérifié localement avec une keystore de test. En CI, les variables viennent des secrets GitHub (`SIGNING_KEYSTORE_BASE64` décodé dans `$RUNNER_TEMP`).
- ⚠️ La Mi Box a actuellement une version signée avec la **clé debug locale**. Le premier APK de CI signé avec une autre clé imposera un `adb uninstall`, donc la configuration sera à ressaisir.
- ⚠️ Le package a été renommé de `com.nextclouddream` en `fr.midiland.nextclouddream` le 2026-10-06. Pour Android c'est une **application différente** : sur l'émulateur et sur la Mi Box il faut faire `adb uninstall com.nextclouddream` (l'ancienne reste installée et visible sinon), ressaisir la configuration, et repointer `screensaver_components` sur le nouveau composant. Le cache et l'index repartent de zéro. La migration `LegacySettingsMigration` ne sert plus à rien sur les nouveaux appareils : ses préférences sont lues dans le répertoire du nouveau package, qui est vide.
- Le dépôt est `git@github.com:midiland/nextcloud-dream.git`. Le workflow n'a encore jamais été déclenché (aucun tag poussé).

## Versions (alignées sur les outils installés sur ce Mac)

- AGP 8.13.0, Kotlin 2.0.21, Gradle 8.14.3 (déjà en cache dans `~/.gradle`), compileSdk/targetSdk 36, minSdk 28.
- **`security-crypto` doit être en 1.1.0 ou plus** : `MasterKey` n'existe pas en 1.0.0, la compilation échoue.
- Coil **2.7** (API `coil.*`, pas Coil 3), OkHttp 4.12, WorkManager 2.10, Timber 5.
- `proguard-rules.pro` : `-dontwarn` pour les annotations errorprone et javax référencées par Tink.

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
- Icône et bannière TV provisoires (vecteurs simples dans `res/drawable`).
- Logs en release : `ReleaseTree` (INFO et au-dessus, tag `NextcloudDream`) → `adb logcat -s NextcloudDream:V`.
