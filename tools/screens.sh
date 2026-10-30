#!/bin/bash
# Captures d'écran automatiques de l'appli sur un émulateur Android TV (CI seulement).
# Chaque capture est numérotée dans l'ordre du parcours : shots/NN_nom.png
# Les animations sont filmées puis découpées en planches d'images : shots/anim_*.png
set -u
PKG=com.ilian.xtreamplayer
OUT=shots
mkdir -p "$OUT"
n=0

shot() {
  n=$((n + 1))
  adb exec-out screencap -p > "$OUT/$(printf %02d $n)_$1.png" 2>/dev/null || true
}
key() {
  for k in "$@"; do
    adb shell input keyevent "$k"
    sleep 0.5
  done
}
# Filme l'écran pendant $2 secondes (en arrière-plan) sous le nom $1
rec_start() {
  adb shell rm -f "/sdcard/$1.mp4" >/dev/null 2>&1
  adb shell screenrecord --size 1280x720 --bit-rate 8000000 --time-limit "$2" "/sdcard/$1.mp4" >/dev/null 2>&1 &
  sleep 0.6
}
# Récupère la vidéo et en fait une planche (6 images par seconde, 6 par ligne)
rec_sheet() {
  sleep "${2:-1}"
  adb pull "/sdcard/$1.mp4" "/tmp/$1.mp4" >/dev/null 2>&1 || return 0
  ffmpeg -loglevel error -y -i "/tmp/$1.mp4" -vf "fps=${3:-6},scale=480:-1,tile=6x5:padding=4:color=black" -frames:v 1 "$OUT/anim_$1.png" || true
  cp "/tmp/$1.mp4" "$OUT/$1.mp4" 2>/dev/null || true
}
UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4

adb shell settings put global stay_on_while_plugged_in 7 || true
adb shell wm size || true
adb shell wm density || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c

# 1. Ouverture : animation du logo (filmée), puis mise à jour des listes
rec_start splash 7
adb shell am start -n $PKG/.StartActivity --es demo_server http://10.0.2.2:8080
rec_sheet splash 7 8
sleep 8; shot home

# 2. Accueil
key $DOWN; sleep 1; shot home_d1
key $DOWN; sleep 1; shot home_d2
key $RIGHT; sleep 1; shot home_d2_r
key $UP $UP $UP $UP; sleep 1; shot home_top

# 3. Barre d'onglets (filmée : la pastille qui glisse)
rec_start tabs 9
key $RIGHT; sleep 0.8; key $RIGHT; sleep 0.8; key $LEFT; sleep 2.5
rec_sheet tabs 3
shot direct
key $DOWN; sleep 1.5; shot direct_in
key $DOWN; sleep 1; shot direct_in2
key $OK; sleep 9; shot player
key $OK; sleep 1; shot player_info
key $OK; sleep 1.5; shot player_menu
key $DOWN; sleep 0.8; shot player_menu2
key $BACK; sleep 1; key $BACK; sleep 3; shot back_direct

# 4. Sport
key $UP $UP $UP $UP; sleep 1
key $RIGHT; sleep 5; shot sport
key $DOWN; sleep 1; shot sport_in

# 5. Films
key $UP $UP $UP $UP; sleep 1
key $RIGHT; sleep 5; shot films
key $DOWN; sleep 1.2; shot films_in
key $RIGHT; sleep 0.8; shot films_in_r
key $DOWN; sleep 1.2; shot films_in2
key $OK; sleep 5; shot movie
key $DOWN; sleep 1; shot movie_d
key $BACK; sleep 2

# 6. Séries
key $UP $UP $UP $UP $UP; sleep 1
key $RIGHT; sleep 5; shot series
key $DOWN; sleep 1.2; shot series_in
key $OK; sleep 5; shot serie
key $DOWN; sleep 1; shot serie_d
key $DOWN; sleep 1; shot serie_d2
key $BACK; sleep 2

# 7. Favoris, profil, réglages
key $UP $UP $UP $UP $UP; sleep 1
key $RIGHT; sleep 3; shot favoris
key $RIGHT; sleep 1; shot profil_chip
key $RIGHT; sleep 1; shot settings_btn
key $OK; sleep 4; shot settings_0
for i in 1 2 3 4 5; do key $DOWN; sleep 1.5; shot "settings_$i"; done
key $RIGHT; sleep 1; shot settings_5_row
key $DOWN; sleep 0.6; key $DOWN; sleep 0.8; shot settings_5_row3
rec_start toggle 4
key $OK; sleep 2
rec_sheet toggle 2 10
shot settings_toggle
key $DOWN; key $DOWN; key $DOWN; key $DOWN; sleep 0.8; shot settings_5_row7
key $OK; sleep 1.5; shot settings_choice
key $BACK; sleep 1
key $BACK; sleep 1; key $BACK; sleep 2; shot after_settings

# 8. Recherche
key $UP $UP $UP; key $LEFT $LEFT $LEFT $LEFT $LEFT $LEFT $LEFT; sleep 1; shot search_btn
key $OK; sleep 3; shot search

# Journal : erreurs et plantages éventuels
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
adb logcat -d -v time -s AndroidRuntime:E > "$OUT/crash.txt" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>/dev/null || true
exit 0
