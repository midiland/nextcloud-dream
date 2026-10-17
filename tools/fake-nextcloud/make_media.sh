#!/usr/bin/env bash
# Fabrique les médias de test (3 photos, 4 vidéos seules) dans le dossier donné.
# Chaque vidéo couvre un cas du diaporama : portrait, paysage avec date, boucle, plafond.
# Usage : make_media.sh <dossier>
set -euo pipefail
dir=${1:?usage: make_media.sh <dossier>}
mkdir -p "$dir"
cd "$dir"

ff() { ffmpeg -loglevel error -y "$@"; }

ff -f lavfi -i "testsrc2=size=1920x1080" -frames:v 1 -q:v 3 photo_paysage.jpg
ff -f lavfi -i "mandelbrot=size=1920x1080" -frames:v 1 -q:v 3 photo_mandelbrot.jpg
ff -f lavfi -i "smptehdbars=size=1080x1440" -frames:v 1 -q:v 3 photo_portrait.jpg

# Paysage, moov en tête, date dans mvhd → la date doit s'afficher en bas à gauche
ff -f lavfi -i "testsrc=size=1280x720:rate=30" -t 15 -c:v libx264 -pix_fmt yuv420p \
   -movflags +faststart -metadata creation_time=2024-07-14T10:00:00Z video_paysage_15s.mp4
# Portrait, moov en fin de fichier → affichée en entier sur un fond flou
ff -f lavfi -i "life=size=720x1280:rate=25:mold=10:ratio=0.3" -t 10 -c:v libx264 \
   -pix_fmt yuv420p -metadata creation_time=2023-12-25T18:00:00Z video_portrait_10s.mp4
# Plus courte qu'une photo → doit tourner en boucle
ff -f lavfi -i "rgbtestsrc=size=1280x720:rate=30" -t 3 -c:v libx264 -pix_fmt yuv420p video_courte_3s.mp4
# Plus longue que le plafond (20 s dans la config de test) → doit être coupée
ff -f lavfi -i "testsrc2=size=1920x1080:rate=30" -t 30 -c:v libx264 -pix_fmt yuv420p \
   -preset veryfast video_longue_30s.mov

ls -la "$dir"
