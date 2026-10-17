#!/usr/bin/env bash
# Monte de bout en bout un test de l'économiseur sur un émulateur Android TV :
#   médias de test → build debug → émulateur → installation → faux Nextcloud →
#   configuration de l'application (saisie au D-pad) → synchro → économiseur lancé.
#
# Usage (depuis la racine du dépôt) :
#   tools/fake-nextcloud/run_emulator_test.sh
# Variables facultatives :
#   AVD=<nom>        AVD à démarrer si aucun appareil n'est connecté (défaut : 1er AVD « TV »)
#   WORK_DIR=<dir>   médias, journaux et captures (défaut : $TMPDIR/nextcloud-dream-test)
#   PORT=8080        port du faux serveur
#   NO_BUILD=1       réutiliser l'APK déjà compilé
#
# Idempotent : on peut le relancer, l'application est remise à zéro (pm clear) à chaque fois.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$REPO/tools/fake-nextcloud"
WORK_DIR=${WORK_DIR:-${TMPDIR:-/tmp}/nextcloud-dream-test}
PORT=${PORT:-8080}
PKG=fr.midiland.nextclouddream
mkdir -p "$WORK_DIR"

step() { printf '\n=== %s\n' "$*"; }
die() { printf 'ÉCHEC : %s\n' "$*" >&2; exit 1; }

# --- Outils ------------------------------------------------------------------
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [[ -d $d ]] && ANDROID_SDK_ROOT=$d && break; done
fi
[[ -d "${ANDROID_SDK_ROOT:-}" ]] || die "SDK Android introuvable (définir ANDROID_SDK_ROOT)"
export ANDROID_SDK_ROOT
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"

# Gradle 8.14 / AGP 8.13 refusent le JDK 25 : JDK 17 ou 21 obligatoire
if [[ -z "${JAVA_HOME:-}" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE '"(17|21)\.'; then
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" /usr/lib/jvm/java-17-openjdk-amd64; do
    [[ -x "$j/bin/java" ]] && JAVA_HOME=$j && break
  done
fi
export JAVA_HOME
command -v ffmpeg >/dev/null || die "ffmpeg manquant (brew install ffmpeg)"
command -v python3 >/dev/null || die "python3 manquant"

# --- Médias ------------------------------------------------------------------
step "Médias de test dans $WORK_DIR/files"
if [[ -z "$(ls "$WORK_DIR/files" 2>/dev/null)" ]]; then
  bash "$HERE/make_media.sh" "$WORK_DIR/files"
else
  echo "déjà présents (supprimer $WORK_DIR/files pour les refaire)"
fi

# --- Build -------------------------------------------------------------------
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
if [[ -z "${NO_BUILD:-}" ]]; then
  step "Build debug (JAVA_HOME=$JAVA_HOME)"
  (cd "$REPO" && ./gradlew assembleDebug -q)
fi
[[ -f $APK ]] || die "APK absent : $APK"

# --- Émulateur ---------------------------------------------------------------
step "Émulateur"
# awk et non grep : le grep de macOS ne connaît pas \s
if [[ -z "$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice/ {print $1}')" ]]; then
  AVD=${AVD:-$("$EMULATOR" -list-avds | grep -i tv | head -1)}
  [[ -n $AVD ]] || die "aucun AVD Android TV (voir docs/TESTING_GUIDE.md, « Créer un AVD »)"
  echo "démarrage de $AVD"
  nohup "$EMULATOR" -avd "$AVD" -no-snapshot-load -no-audio >"$WORK_DIR/emulator.log" 2>&1 &
fi
"$ADB" wait-for-device
until [[ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice/ {print $1; exit}')
[[ -n $SERIAL ]] || die "un appareil est connecté mais ce n'est pas un émulateur"
A() { "$ADB" -s "$SERIAL" "$@"; }
echo "prêt : $SERIAL"

# --- Installation ------------------------------------------------------------
step "Installation (données remises à zéro)"
A install -r -t "$APK" >/dev/null
A shell pm clear "$PKG" >/dev/null

# --- Faux serveur ------------------------------------------------------------
step "Faux Nextcloud sur 127.0.0.1:$PORT"
pkill -f "fake_nextcloud.py" 2>/dev/null || true
sleep 1
nohup python3 -I "$HERE/fake_nextcloud.py" "$WORK_DIR/files" "$PORT" >"$WORK_DIR/server.log" 2>&1 &
sleep 1
curl -sf -o /dev/null -u test:test-password -X PROPFIND \
  "http://127.0.0.1:$PORT/remote.php/dav/files/test/Photos/ScreenSaver/" || die "le serveur ne répond pas (voir $WORK_DIR/server.log)"
# L'émulateur joint le Mac par localhost grâce à adb reverse : seul localhost/127.0.0.1
# est autorisé en HTTP clair, et seulement dans le build debug
A reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

# --- Configuration -----------------------------------------------------------
step "Configuration de l'application"
# Le clavier virtuel capte les touches envoyées par adb : on le coupe pendant la saisie
for ime in $(A shell ime list -s | tr -d '\r'); do A shell ime disable "$ime" >/dev/null; done

ui_dump() { A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; A shell cat /sdcard/ui.xml; }
# Texte du widget dont l'id est $1
ui_text() { ui_dump | python3 -I -c '
import re, sys
xml = sys.stdin.read()
m = re.search(r"text=\"([^\"]*)\" resource-id=\"[^\"]*:id/" + sys.argv[1] + "\"", xml)
print(m.group(1) if m else "")' "$1"; }
# Id du widget qui a le focus
ui_focused() { ui_dump | python3 -I -c '
import re, sys
m = re.search(r"resource-id=\"[^\"]*:id/(\w+)\"[^>]*focused=\"true\"", sys.stdin.read())
print(m.group(1) if m else "")'; }
key() { A shell input keyevent "$@"; }
# Vide le champ qui a le focus puis y tape $1
type_in() { key KEYCODE_MOVE_END; key $(printf 'KEYCODE_DEL %.0s' {1..40}); A shell input text "$1"; }

A shell am start -n "$PKG/.MainActivity" >/dev/null
for _ in {1..20}; do [[ "$(ui_focused)" == server_url ]] && break; sleep 1; done
[[ "$(ui_focused)" == server_url ]] || die "écran de configuration non affiché"

# Ordre des champs (activity_main.xml) : URL, identifiant, mot de passe, case « afficher »,
# dossier, durée photo, plafond vidéo… Les flèches haut/bas passent d'un champ à l'autre.
type_in "http://localhost:$PORT"
# Vérifié tout de suite : une fois le formulaire défilé, le champ sort du dump uiautomator
[[ "$(ui_text server_url)" == "http://localhost:$PORT" ]] || die "saisie de l'URL incorrecte : '$(ui_text server_url)'"
key KEYCODE_DPAD_DOWN
type_in "test";                     key KEYCODE_DPAD_DOWN
type_in "test-password";            key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
type_in "/Photos/ScreenSaver";      key KEYCODE_DPAD_DOWN
type_in "8";                        key KEYCODE_DPAD_DOWN   # durée d'une photo (s), min 5
type_in "20";                                               # plafond vidéo (s), min 10

for id in username folder_path slide_interval max_video; do
  printf '  %-15s %s\n' "$id" "$(ui_text "$id")"
done

# Descendre jusqu'au bouton « Save and sync » puis le valider
for _ in {1..15}; do
  [[ "$(ui_focused)" == save_button ]] && break
  key KEYCODE_DPAD_DOWN
done
[[ "$(ui_focused)" == save_button ]] || die "bouton save_button jamais atteint"
key KEYCODE_DPAD_CENTER

status=""
for _ in {1..30}; do
  status=$(ui_text status)
  [[ $status =~ (Sync\ complete|Synchronisation\ terminée|failed|échou|Error|Erreur) ]] && break
  sleep 1
done
echo "  statut : $status"
[[ $status =~ (Sync\ complete|Synchronisation\ terminée) ]] || die "synchro non terminée (voir $WORK_DIR/server.log)"

# --- Économiseur -------------------------------------------------------------
step "Économiseur"
A shell settings put secure screensaver_enabled 1
A shell settings put secure screensaver_components "$PKG/.dream.NextcloudDreamService"
A logcat -c
A shell am start -n com.android.systemui/.Somnambulator >/dev/null
sleep 4
A shell dumpsys dreams | grep -q "mCurrentDreamName=ComponentInfo{$PKG/" || die "l'économiseur n'a pas démarré"
A exec-out screencap -p >"$WORK_DIR/dream_start.png"

cat <<EOF

OK : l'économiseur tourne sur $SERIAL.
  Requêtes du serveur : tail -f $WORK_DIR/server.log
  Logs de l'appli     : $ADB -s $SERIAL logcat --pid=\$($ADB -s $SERIAL shell pidof $PKG) | grep -E 'MotionLayer|SlideshowView|NextcloudDream'
  Capture d'écran     : $ADB -s $SERIAL exec-out screencap -p > $WORK_DIR/shot.png
  Relancer le dream   : $ADB -s $SERIAL shell am start -n com.android.systemui/.Somnambulator
  Arrêter le serveur  : pkill -f fake_nextcloud.py
  Rendre le clavier   : $ADB -s $SERIAL shell ime reset
EOF
