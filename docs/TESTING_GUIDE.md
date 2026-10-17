# Guide de test sur émulateur — Nextcloud Dream

*Mis à jour le 17 octobre 2026. Écrit pour les agents de code comme pour les humains.*

Ce guide explique comment voir l'économiseur tourner **pour de vrai** sur un émulateur
Android TV, branché sur un faux serveur Nextcloud, et comment vérifier ce qu'il fait.
Les tests unitaires (`./gradlew testDebugUnitTest`) ne suffisent pas pour la lecture
vidéo, les fondus ou le cadrage : il faut regarder l'écran et les journaux.

> **Agent : lis d'abord la section [Règles pour les agents](#règles-pour-les-agents).**
> Elle liste les erreurs déjà commises sur ce projet.

---

## 1. Démarrage rapide (une seule commande)

Depuis la racine du dépôt :

```bash
tools/fake-nextcloud/run_emulator_test.sh
```

Durée : 1 à 3 minutes. À la fin, le script affiche `OK : l'économiseur tourne sur emulator-5554`
et les commandes utiles (journaux, capture d'écran…). En cas de problème, il s'arrête
sur une ligne `ÉCHEC : …` qui dit quelle étape a échoué.

Le script est **idempotent** : on peut le relancer autant de fois qu'on veut. Il remet
les données de l'application à zéro à chaque fois (`pm clear`).

Variables facultatives :

| Variable | Défaut | Rôle |
|---|---|---|
| `AVD` | premier AVD dont le nom contient « tv » | AVD démarré si aucun émulateur ne tourne |
| `WORK_DIR` | `$TMPDIR/nextcloud-dream-test` | médias, `server.log`, `emulator.log`, captures |
| `PORT` | `8080` | port du faux serveur |
| `NO_BUILD` | vide | `NO_BUILD=1` réutilise l'APK déjà compilé |

Exemple pour un agent qui a un dossier de travail temporaire :

```bash
WORK_DIR=/chemin/vers/mon/scratchpad/run NO_BUILD=1 tools/fake-nextcloud/run_emulator_test.sh
```

### Prérequis

- Un AVD Android TV (voir la [section 5](#5-créer-un-avd-android-tv) s'il n'y en a pas : `emulator -list-avds`).
- `ffmpeg` (`brew install ffmpeg`) et `python3`.
- JDK 17 ou 21. Le script le trouve tout seul (Android Studio sur Mac, `/usr/lib/jvm/java-17-openjdk-amd64` sur Linux).
  **Le JDK 25 du PATH ne marche pas** avec Gradle 8.14 / AGP 8.13.

---

## 2. Ce que fait le script, étape par étape

Utile si une étape échoue, ou pour la refaire à la main.

1. **Médias de test** (`tools/fake-nextcloud/make_media.sh`) : 3 photos et 4 vidéos fabriquées par ffmpeg, dans `$WORK_DIR/files`.
2. **Build** : `./gradlew assembleDebug` avec le bon `JAVA_HOME`.
3. **Émulateur** : démarré s'il n'y en a pas, puis attente de `sys.boot_completed = 1`.
4. **Installation** : `adb install -r -t`, puis `pm clear fr.midiland.nextclouddream`.
5. **Faux serveur** (`tools/fake-nextcloud/fake_nextcloud.py`) sur `127.0.0.1:8080`, puis `adb reverse tcp:8080 tcp:8080`.
6. **Configuration** : le script coupe le clavier virtuel, ouvre l'écran de configuration et saisit les valeurs au D-pad (voir le tableau ci-dessous). Il vérifie les champs avec `uiautomator dump`, descend jusqu'à « Save and sync » et le valide. Il attend ensuite le message « Sync complete ».
7. **Économiseur** : `screensaver_components` pointé sur notre service, lancé par `Somnambulator`, vérifié par `dumpsys dreams`.

Configuration saisie :

| Champ | Valeur |
|---|---|
| Nextcloud server URL | `http://localhost:8080` |
| Username | `test` |
| App password | `test-password` |
| Photo folder | `/Photos/ScreenSaver` |
| Time each photo is shown | `8` (minimum 5) |
| Maximum time a video plays | `20` (minimum 10) |
| Cases à cocher | toutes cochées (valeurs par défaut) |

### Le faux serveur

`fake_nextcloud.py` imite juste assez Nextcloud pour l'application :

- `PROPFIND` (Depth 1) sur `/remote.php/dav/files/test/Photos/ScreenSaver/`, avec `getetag`, `getcontentlength`, `oc:fileid` ;
- `GET` et `HEAD` avec `Range` (y compris `bytes=N-` et `bytes=-N`) sur les fichiers ;
- `/index.php/core/preview?fileId=…` : renvoie le JPEG original (pas de réduction) ;
- authentification Basic obligatoire : `401` sans les bons identifiants.

Chaque requête est écrite dans `$WORK_DIR/server.log`. Le client est indiqué entre crochets :
`[okhttp/…]` pour l'application, `[stagefright/…]` pour le lecteur vidéo d'Android.

**Limites :** un seul dossier (pas de sous-dossiers), pas de `nc:metadata-files-live-photo`. Une Live Photo n'est donc rattachée que par le nom de base (`IMG_1.jpg` + `IMG_1.mov` de moins de 20 Mo). Pas de géolocalisation non plus dans les médias fabriqués.

---

## 3. Résultat attendu

### Synchro

Message de l'écran de configuration : `Sync complete: 3 photos and 4 videos in the folder.`
Dans `server.log`, on voit : un PROPFIND, 3 aperçus, des lectures `Range` de 256 Ko sur les
photos (EXIF) et de quelques Ko sur les vidéos (boîtes MP4). **Aucune vidéo n'est
téléchargée en entier** : c'est un invariant du projet.

### Diaporama

| Fichier | Ce qui doit se passer |
|---|---|
| `photo_*.jpg` | affichée 8 s, fondu de 1,5 s, horloge en bas à droite |
| `video_portrait_10s.mp4` (720×1280) | fondu depuis la photo, vidéo entière au centre sur un fond flou, lue jusqu'à la fin (`Completed`) |
| `video_paysage_15s.mp4` | plein écran, date **« July 14, 2024 »** en bas à gauche (lue dans `mvhd`), lue jusqu'à la fin |
| `video_courte_3s.mp4` | plus courte qu'une photo : **tourne en boucle** jusqu'à 8 s, puis s'arrête à la fin d'un tour |
| `video_longue_30s.mov` | **coupée à 20 s** (`Capped`), puis élément suivant |

Lignes de logcat correspondantes (build debug, tag = nom de la classe) :

```
D MotionLayer: Vidéo VideoStream(http://localhost:8080/…/video_portrait_10s.mp4) : 720x1280, 10 s
D SlideshowView: Vidéo terminée (Completed) : VideoStream(…/video_portrait_10s.mp4)
D SlideshowView: Vidéo terminée (Capped) : VideoStream(…/video_longue_30s.mov)
D SlideshowView: Photo affichée : 451156e7….jpg (plein écran)
```

Dans `server.log`, chaque vidéo donne un `HEAD` (okhttp), puis des `GET` du lecteur
(`stagefright`), tous en `200`/`206`. **Un seul `401` venant de stagefright serait un
bug** : le lecteur n'aurait pas reçu l'en-tête d'authentification.

---

## 4. Vérifier et explorer

```bash
ADB=~/Library/Android/sdk/platform-tools/adb     # Linux : ~/Android/Sdk/platform-tools/adb
PKG=fr.midiland.nextclouddream

# Journaux de l'application (debug)
$ADB logcat -d --pid=$($ADB shell pidof $PKG) | grep -E 'MotionLayer|SlideshowView|Slide|PhotoRepository'
# Avertissements et erreurs seulement
$ADB logcat -d --pid=$($ADB shell pidof $PKG) '*:W'

# Requêtes reçues par le serveur
tail -f "$WORK_DIR/server.log"

# Capture d'écran (un agent peut ensuite lire le PNG comme une image)
$ADB exec-out screencap -p > "$WORK_DIR/shot.png"
sips -Z 640 "$WORK_DIR/shot.png" --out "$WORK_DIR/shot_small.png"   # Mac : réduire avant de la lire

# Relancer l'économiseur / vérifier que c'est le nôtre
$ADB shell am start -n com.android.systemui/.Somnambulator
$ADB shell dumpsys dreams | grep mCurrentDreamName

# Contenu du cache (debug uniquement, commande run-as entre guillemets)
$ADB shell "run-as $PKG ls -la files/photos"
```

Pour suivre un diaporama, prendre une capture toutes les 8 s environ, pendant 1 à 2 minutes.
Une capture prise pendant un fondu montre les deux images superposées : c'est normal.

### Scénarios supplémentaires

| Scénario | Comment | Attendu |
|---|---|---|
| Serveur coupé pendant une vidéo | `pkill -f fake_nextcloud.py` | bascule sur le cache de photos, pas d'écran noir ; vidéos sautées hors ligne |
| Serveur revenu | relancer le script avec `NO_BUILD=1` | — |
| Vidéos désactivées | décocher « Play videos » puis « Save and sync » | 0 vidéo jouée, 0 requête vers une vidéo dans `server.log` |
| Nouvelle vidéo | déposer un `.mp4`/`.mov` dans `$WORK_DIR/files`, puis « Save and sync » | elle apparaît au cycle suivant |
| Vidéo cassée | `head -c 100000 video_paysage_15s.mp4 > casse.mp4` dans `$WORK_DIR/files` | écartée, l'élément courant reste affiché |
| Touche pendant le dream | `$ADB shell input keyevent KEYCODE_DPAD_CENTER` | économiseur fermé en moins d'une seconde, pas d'ANR |

Pour naviguer dans l'écran de configuration à la main : `KEYCODE_DPAD_UP/DOWN` pour passer
d'un champ à l'autre, `KEYCODE_DPAD_CENTER` pour cocher ou valider. Pour savoir quel widget
a le focus : `$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml`,
puis chercher `focused="true"`.

---

## 5. Créer un AVD Android TV

`sdkmanager` et `avdmanager` sont dans `$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/`.

**Mac Apple Silicon** : uniquement des images arm64, donc API 30 ou plus. L'AVD existant sur cette machine est `Android_TV_1080p_API31`.

```bash
sdkmanager "system-images;android-31;android-tv;arm64-v8a"
echo no | avdmanager create avd -n Android_TV_1080p_API31 \
  -k "system-images;android-31;android-tv;arm64-v8a" -d tv_1080p
```

**Linux x86_64** : l'image API 28 = Android TV 9, **la version de la Mi Box**, donc la plus représentative.

```bash
sdkmanager "system-images;android-28;android-tv;x86"
echo no | avdmanager create avd -n tv28 -k "system-images;android-28;android-tv;x86" -d tv_1080p
```

Dans les deux cas, mettre `hw.keyboard = yes` dans `~/.android/avd/<nom>.avd/config.ini`
(sinon le clavier de la machine n'est pas transmis à l'émulateur).

---

## 6. Règles pour les agents

### À faire

- **Utiliser le script** plutôt que refaire les étapes à la main. S'il échoue, lire la ligne `ÉCHEC`, corriger la cause, relancer.
- **Prouver ce qu'on affirme** : une fonctionnalité vidéo n'est « vérifiée » que si l'on a vu la ligne logcat (`Completed`, `Capped`…), la requête dans `server.log` **et** une capture d'écran cohérente. Ne jamais écrire « ça marche » après un simple build.
- **Citer les preuves** dans le compte rendu : extraits de logs, nombre de requêtes, ce qu'on voit sur la capture.
- **Dire ce qui n'a pas été testé.** L'émulateur n'est ni la Mi Box (débit Wi-Fi, décodage matériel armeabi-v7a), ni un vrai Nextcloud, ni de vraies vidéos d'iPhone (HEVC/Dolby Vision).
- Toujours utiliser le chemin complet d'`adb` : les shells non interactifs ne l'ont pas dans leur PATH.
- Mettre les fichiers temporaires (captures, médias, journaux) **hors du dépôt**, dans `WORK_DIR`.

### À ne pas faire (erreurs déjà commises)

| Erreur | Pourquoi c'est faux |
|---|---|
| `python3 -m http.server` comme « serveur Nextcloud » | Il ne connaît ni PROPFIND, ni l'authentification, ni `core/preview`. La synchro échoue. Utiliser `fake_nextcloud.py`. |
| URL `http://10.0.2.2:…` | Le HTTP en clair n'est autorisé que vers `localhost`/`127.0.0.1` (`app/src/debug/res/xml/network_security_config.xml`). Utiliser `adb reverse` + `http://localhost:8080`. |
| Ajouter `usesCleartextTraffic` ou un `network_security_config` dans `app/src/main/` | Cela ouvrirait le HTTP en clair **dans la release**, ce qui est interdit (voir CLAUDE.md). Les réglages de test vont **uniquement** dans `app/src/debug/`. |
| Laisser l'identifiant ou le mot de passe vides | L'application refuse d'enregistrer (« Renseignez l'URL, l'identifiant… »). |
| `adb shell am start -n …/.dream.NextcloudDreamService` | C'est un service, pas une activité. Passer par `Somnambulator` (émulateur) ou l'action `SLEEP` (Mi Box, voir CLAUDE.md). |
| `adb logcat \| grep NextcloudDream` sur un build debug | En debug, le tag est le nom de la classe (`MotionLayer`, `SlideshowView`…). Le tag `NextcloudDream` n'existe qu'en release. |
| `adb shell input text` avec le clavier virtuel actif | Le clavier capte ou déforme la saisie. Le désactiver (`ime disable`, le script le fait) puis `adb shell ime reset` à la fin. |
| `grep -E '\s'` dans un script | Le grep de macOS ne connaît pas `\s`. Utiliser `awk` ou `[[:space:]]`. |
| `ffmpeg` mis dans une variable (`ff="ffmpeg -y"; $ff …`) | zsh ne découpe pas les variables en mots : « command not found ». Utiliser une fonction. |
| Variable de boucle nommée `path` en zsh | Elle est liée à `PATH` : le shell ne trouve plus aucune commande. |
| Lire un champ du formulaire par `uiautomator` après avoir fait défiler l'écran | Un champ sorti de l'écran disparaît du dump : son texte paraît vide. Vérifier juste après la saisie. |
| `withTimeout` dans le diaporama | Son dépassement est une `CancellationException` : il arrête tout le diaporama. Toujours `withTimeoutOrNull`. |

### Compte rendu type

> Testé sur l'émulateur `Android_TV_1080p_API31` (API 31), faux serveur local, build debug du commit `abc1234`.
> Synchro : 3 photos, 4 vidéos, aucune vidéo téléchargée en entier.
> Portrait 10 s `Completed` ; paysage 15 s `Completed`, date affichée ; courte 3 s en boucle ; `.mov` 30 s `Capped` à 20 s.
> 0 réponse 401 au lecteur vidéo. Captures : `…/dream_3.png`.
> Non vérifié : Mi Box, vrai Nextcloud, vidéos HEVC d'iPhone.

---

## 7. Mi Box (rappel)

Les détails sont dans CLAUDE.md (section « Mi Box »). En bref : `adb connect 192.168.1.XX:5555`.
Installer la **release** (stockage presque plein). Lancer l'économiseur avec
`adb shell am start -a com.google.android.pano.action.SLEEP -n com.android.tv.settings/.device.display.daydream.DaydreamVoiceAction`.
Logs : `adb logcat -s NextcloudDream:V`. Le faux serveur n'y est pas utilisable : la release refuse le HTTP en clair.
