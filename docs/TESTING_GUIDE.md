# 🧪 Guide de Test pour NextCloudDreamAndroidTv

*Dernière mise à jour : 17 octobre 2026*

Ce guide décrit comment configurer un environnement de test complet pour l'application **NextCloudDreamAndroidTv**, y compris la lecture des vidéos seules, des Live Photos et des Motion Photos.

---

## 📋 Table des Matières

1. [Prérequis](#-prérequis)
2. [Configuration de l'environnement](#-configuration-de-lenvironnement)
3. [Build de l'application](#-build-de-lapplication)
4. [Gestion des émulateurs Android TV](#-gestion-des-émulateurs-android-tv)
5. [Installation de l'application](#-installation-de-lapplication)
6. [Configuration de l'application](#-configuration-de-lapplication)
7. [Test des fonctionnalités](#-test-des-fonctionnalités)
8. [Vérification via les logs](#-vérification-via-les-logs)
9. [Test sans serveur Nextcloud](#-test-sans-serveur-nextcloud)
10. [Dépannage](#-dépannage)

---

## 📌 Prérequis

### Matériel
- Un Mac avec architecture Intel ou Apple Silicon
- Android Studio installé
- SDK Android configuré (dans `~/Library/Android/sdk`)
- Au moins 8 Go de RAM (16 Go recommandé pour l'émulateur)

### Logiciels
- Java JDK 17 ou 21 (fournis avec Android Studio)
- Gradle 8.14+
- ADB et outils Android
- Git

---

## 🛠️ Configuration de l'environnement

### 1. Configurer les variables d'environnement

**Dans ton terminal (`~/.zshrc` ou `~/.bashrc`) :**

```bash
# SDK Android
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
export PATH=$ANDROID_SDK_ROOT/platform-tools:$PATH
export PATH=$ANDROID_SDK_ROOT/emulator:$PATH

# JDK (utiliser celui d'Android Studio)
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
```

**Appliquer les changements :**
```bash
source ~/.zshrc  # ou ~/.bashrc selon ton shell
```

### 2. Vérifier la configuration

```bash
# Vérifier les versions
java -version          # Doit afficher OpenJDK 21.x (ou 17.x)
adb --version         # Doit être dans le PATH
emulator -list-avds   # Doit lister tes AVDs
```

---

## 🔨 Build de l'application

### Build standard (APK debug)

```bash
cd /Users/midiland/project/NextCloudDreamAndroidTv

# Builder l'APK debug (avec tests)
./gradlew :app:assembleDebug --no-daemon

# L'APK sera générée ici :
# app/build/outputs/apk/debug/app-debug.apk
```

### Exécuter les tests unitaires

```bash
# Lancer tous les tests JVM (52+ tests)
./gradlew :app:testDebugUnitTest --no-daemon

# Voir le rapport de couverture (nécessite Jacoco)
./gradlew jacocoTestReport
# Rapport disponible dans : app/build/reports/jacoco/jacocoTestReport/html/index.html
```

---

## 📺 Gestion des émulateurs Android TV

### Créer un AVD Android TV 9 (recommandé - même version que Mi Box)

```bash
# Lister les images disponibles
echo "y" | sdkmanager --list | grep "android-tv"

# Créer un AVD Android TV 9 (API 28)
echo "no" | avdmanager create avd \
  -n tv28 \
  -k "system-images;android-28;android-tv;x86" \
  --device "Android_TV_1080p" \
  --force
```

### Créer un AVD Android TV 12

```bash
# Utiliser l'image API 31 (Android 12)
echo "no" | avdmanager create avd \
  -n Android_TV_1080p_API31 \
  -k "system-images;android-31;android-tv;x86_64" \
  --device "Android_TV_1080p" \
  --force
```

### Lancer un émulateur

```bash
# Lancer avec interface graphique
emulator -avd tv28

# Lancer sans interface (pour les tests automatisés)
emulator -avd tv28 -no-window -no-snapshot-load &

# Attendre que l'émulateur soit prêt (environ 30-60 secondes)
sleep 60
adb wait-for-device
```

### Arrêter un émulateur

```bash
# Lister les émulateurs en cours
adb devices

# Arrêter un émulateur spécifique
adb -s emulator-5554 emu kill

# Arrêter tous les émulateurs
adb devices | grep emulator | cut -f1 | while read line; do adb -s $line emu kill; done
```

---

## 📱 Installation de l'application

### Installer l'APK

```bash
# Installer sur un émulateur spécifié
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk

# Installer sur le seul appareil connecté
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

### Réinstaller avec effacement des données

```bash
# Effacer les données puis réinstaller
adb -s emulator-5554 shell pm clear fr.midiland.nextclouddream
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

### Lancer l'application

```bash
# Lancer l'activité principale (écran de configuration)
adb shell am start -n fr.midiland.nextclouddream/.MainActivity

# Lancer directement l'économiseur (pour test)
adb shell am start -n fr.midiland.nextclouddream/.dream.NextcloudDreamService
```

---

## ⚙️ Configuration de l'application

### Configuration manuelle (recommandé)

1. **Lancer l'application** depuis le launcher de l'émulateur
2. **Remplir les champs** :
   - **Server URL** : URL de ton serveur Nextcloud (ex: `https://demo.nextcloud.com`)
   - **Username** : Ton nom d'utilisateur Nextcloud
   - **App Password** : Un mot de passe d'application (à générer dans Nextcloud sous Paramètres → Sécurité → Mot de passe d'application)
   - **Folder Path** : Chemin vers ton dossier (ex: `/Photos`)
3. **Activer les fonctionnalités** :
   - ✅ **Show clock** : Affiche l'heure
   - ✅ **Show photo info** : Affiche date et lieu
   - ✅ **Play live photos** : Joue les clips Live Photo/Motion Photo
   - ✅ **Play videos** : **Joue les vidéos seules** (nouvelle fonctionnalité !)
4. **Tester la connexion** : Clique sur "Test connection"
5. **Sauvegarder** : Clique sur "Save and sync"

### Configuration minimale pour tester les vidéos

| Champ | Valeur pour test local |
|-------|------------------------|
| Server URL | `http://10.0.2.2:8000` |
| Username | (laisser vide) |
| App Password | (laisser vide) |
| Folder Path | `/` |
| Play videos | ✅ **Coché** |

> **Note** : `10.0.2.2` est l'IP spéciale qui permet à l'émulateur d'accéder à ton Mac local.

---

## 🎬 Test des fonctionnalités

### Préparer un dossier de test

Crée un dossier avec différents types de médias :

```
/ton/dossier/test/
├── photo1.jpg          # Photo normale
├── photo2.png          # Photo PNG
├── live.jpg            # Live Photo (iPhone)
├── live.mov            # Clip Live Photo
├── motion.jpg          # Motion Photo (Pixel)
├── sunset.mp4          # Vidéo seule (< 20 Mo)
└── vacances.mov        # Vidéo seule
```

### Lancer un serveur web local

```bash
cd /ton/dossier/test
python3 -m http.server 8000
```

### Configurer l'application pour utiliser ce serveur

Dans l'application :
- Server URL : `http://10.0.2.2:8000`
- Username : (vide)
- App Password : (vide)
- Folder Path : `/`

### Comportement attendu

| Type de fichier | Comportement |
|----------------|--------------|
| `photo1.jpg` | Affichage normal pendant 20 secondes |
| `live.jpg` + `live.mov` | Photo affichée + clip joué une fois |
| `motion.jpg` | Photo affichée + clip MP4 intégré joué |
| `sunset.mp4` | **Vidéo lue en streaming, sans son** |
| `vacances.mov` | **Vidéo lue en streaming, sans son** |

---

## 📊 Vérification via les logs

### Afficher les logs de l'application

```bash
# Méthode 1 : Filtrer par nom de package
adb logcat | grep NextcloudDream

# Méthode 2 : Filtrer par PID
adb shell "pidof fr.midiland.nextclouddream" | xargs -I {} adb logcat --pid={}

# Méthode 3 : Sauvegarder les logs dans un fichier
adb logcat -d > logs.txt
```

### Logs attendus

**Au démarrage de l'économiseur :**
```
I/NextcloudDream: Économiseur démarré
```

**Pendant la synchronisation :**
```
I/NextcloudDream: WebDAV : 4 image(s) trouvée(s) dans /test, dont 1 animée(s), et 2 vidéo(s) seule(s)
I/NextcloudDream: Synchro terminée : 4 photo(s), 2 vidéo(s)
```

**Pendant la lecture d'une vidéo :**
```
I/NextcloudDream: Vidéo seule : sunset.mp4
D/NextcloudDream: Vidéo sunset.mp4 : 1920x1080, 30 s
D/NextcloudDream: Vidéo terminée (Completed) : sunset.mp4
```

**Pour une Live Photo :**
```
I/NextcloudDream: Photo animée : live.jpg (clip de 3528 Ko)
D/NextcloudDream: Clip live_clip.mov : 1280x720, départ à 1043 ms
```

---

## 🌐 Test sans serveur Nextcloud

### Méthode 1 : Utiliser le cache local

1. Copie des fichiers dans le cache de l'application :
   ```bash
   # Copier une photo et une vidéo
   adb push /chemin/vers/photo.jpg /data/local/tmp/
   adb push /chemin/vers/video.mp4 /data/local/tmp/
   ```

2. Dans l'application, après la première synchro, les fichiers seront déplacés dans le bon cache.

3. L'économiseur les affichera automatiquement.

### Méthode 2 : Utiliser un serveur web local simple

```bash
# Dans un terminal, lancer un serveur Python
cd /ton/dossier/avec/médias
python3 -m http.server 8000

# Configurer l'application avec :
# Server URL: http://10.0.2.2:8000
# Username: (vide)
# App Password: (vide)
# Folder Path: /
```

---

## 🛠️ Dépannage

### Problème : L'émulateur ne démarre pas

**Solution 1 : Vérifier les images installées**
```bash
sdkmanager --list
# Si l'image est manquante :
sdkmanager "system-images;android-28;android-tv;x86"
```

**Solution 2 : Augmenter la mémoire**
```bash
# Modifier la configuration de l'AVD
android avd
# Sélectionner ton AVD → Edit → Augmenter la RAM à 2048 Mo
```

**Solution 3 : Utiliser le mode headless**
```bash
emulator -avd tv28 -no-window -no-snapshot-load &
```

---

### Problème : Le build échoue avec JDK 25

**Solution :** Utiliser JDK 17 ou 21 d'Android Studio

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew clean
./gradlew :app:assembleDebug
```

---

### Problème : ADB ne trouve pas l'appareil

**Solution :**
```bash
# Lister tous les appareils
adb devices

# Si aucun appareil, vérifier que l'émulateur est bien lancé
ps aux | grep emulator

# Redémarrer le serveur ADB
adb kill-server
adb start-server
```

---

### Problème : L'application crash au démarrage

**Solution :** Voir les logs détaillés
```bash
adb logcat -d > crash_log.txt
# Chercher des lignes avec "Exception" ou "Error"
grep -i "exception\|error\|crash" crash_log.txt
```

---

### Problème : Les vidéos ne se lisent pas

**Vérifications :**

1. **Vérifier que `playVideos` est activé** dans les paramètres
2. **Vérifier le format** : Seuls MP4 et MOV sont supportés
3. **Vérifier la taille** : Les clips Live Photo sont limités à 20 Mo
4. **Vérifier les codecs** : Certaines vidéos nécessitent des codecs spécifiques

**Test avec une vidéo simple :**
```bash
# Créer une vidéo de test MP4 (3 secondes, sans son)
ffmpeg -f lavfi -i testsrc=duration=3:size=640x480:rate=30 -c:v libx264 -pix_fmt yuv420p -y test_video.mp4
```

---

## 📝 Checklist avant de commencer un test

- [ ] Environnement configuré (`ANDROID_SDK_ROOT`, `JAVA_HOME`, `PATH`)
- [ ] Emulateur Android TV créé (`tv28` recommandé)
- [ ] APK build avec succès (`./gradlew assembleDebug`)
- [ ] Serveur de test prêt (Nextcloud ou serveur local)
- [ ] Dossier de test avec photos + vidéos
- [ ] Application configurée avec `playVideos = true`

---

## 📚 Ressources utiles

- [Documentation Android TV](https://developer.android.com/training/tv)
- [Commandes ADB](https://developer.android.com/studio/command-line/adb)
- [Émulateur Android](https://developer.android.com/studio/run/emulator)
- [Générer un mot de passe d'application Nextcloud](https://docs.nextcloud.com/server/latest/user_manual/en/webapp_settings.html#app-passwords)

---

## 🎯 Commandes rapide (Cheat Sheet)

```bash
# === CONFIGURATION ===
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
export PATH=$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home

# === BUILD ===
cd /Users/midiland/project/NextCloudDreamAndroidTv
./gradlew assembleDebug
./gradlew testDebugUnitTest

# === ÉMULATEUR ===
emulator -avd tv28
adb devices
adb -s emulator-5554 shell am start -n fr.midiland.nextclouddream/.MainActivity

# === INSTALLATION ===
adb install -r app/build/outputs/apk/debug/app-debug.apk

# === LOGS ===
adb logcat | grep NextcloudDream

# === NETTOYAGE ===
adb uninstall fr.midiland.nextclouddream
adb -s emulator-5554 emu kill
```

---

**Document maintenu par :** Mistral Vibe  
**Dernière mise à jour :** 17 octobre 2026
