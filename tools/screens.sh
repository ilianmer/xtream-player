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
    sleep 0.6
  done
}
# Filme l'écran pendant $2 secondes (en arrière-plan) sous le nom $1
rec_start() {
  adb shell rm -f "/sdcard/$1.mp4" >/dev/null 2>&1
  adb shell screenrecord --size 1280x720 --bit-rate 8000000 --time-limit "$2" "/sdcard/$1.mp4" >/dev/null 2>&1 &
  sleep 0.8
}
# Attend la fin du film, le récupère et en fait une planche (images par seconde = $3)
rec_sheet() {
  sleep "${2:-1}"
  adb pull "/sdcard/$1.mp4" "/tmp/$1.mp4" >/dev/null 2>&1 || return 0
  ffmpeg -loglevel error -y -i "/tmp/$1.mp4" -vf "fps=${3:-6},scale=480:-1,tile=6x5:padding=4:color=black" -frames:v 1 "$OUT/anim_$1.png" || true
  cp "/tmp/$1.mp4" "$OUT/$1.mp4" 2>/dev/null || true
}
UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4
top() { key $UP $UP $UP $UP $UP; sleep 1; }

adb shell settings put global stay_on_while_plugged_in 7 || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat -G 4M >/dev/null 2>&1 || true

# 1. Ouverture : animation du logo (filmée), puis mise à jour des listes
rec_start splash 8
adb shell am start -n $PKG/.StartActivity --es demo_server http://10.0.2.2:8080
sleep 4; shot sync
rec_sheet splash 6 8
sleep 30; shot home

# 2. Accueil
key $DOWN; sleep 1.5; shot home_d1
key $UP; sleep 1; shot home_u1
top; shot home_top

# 3. Direct : guide, lecteur, menu du lecteur
rec_start tabs 7
key $RIGHT; sleep 4
rec_sheet tabs 3 8
shot direct
key $DOWN; sleep 2; shot direct_in
key $DOWN; sleep 1.5; shot direct_in2
key $OK; sleep 10; shot player
key $OK; sleep 2; shot player_info
key $OK; sleep 4; shot player_menu
key $DOWN; sleep 1.5; shot player_menu2
key $BACK; sleep 2; shot player_menu_closed
key $BACK; sleep 4; shot back_direct

# 4. Sport
top
key $RIGHT; sleep 6; shot sport
key $DOWN; sleep 1.5; shot sport_in

# 5. Films : rangées, fiche
top
key $RIGHT; sleep 6; shot films
key $DOWN; sleep 2; shot films_in
key $RIGHT; sleep 1.5; shot films_in_r
key $DOWN; sleep 2; shot films_in2
key $OK; sleep 6; shot movie
key $DOWN; sleep 1.5; shot movie_d
key $BACK; sleep 3

# 6. Séries : fiche, saisons
top
key $RIGHT; sleep 6; shot series
key $DOWN; sleep 2; shot series_in
key $OK; sleep 6; shot serie
key $DOWN; sleep 1.5; shot serie_d
key $BACK; sleep 3

# 7. Favoris, menu du profil (fenêtre de choix en verre)
top
key $RIGHT; sleep 4; shot favoris
key $RIGHT; sleep 1.5; shot profil_chip
key $OK; sleep 3; shot profil_menu
key $BACK; sleep 1.5

# 8. Réglages : sections, lignes, interrupteur animé, choix
key $RIGHT; sleep 1; shot settings_btn
key $OK; sleep 5; shot settings_0
key $DOWN; sleep 2; shot settings_1
key $DOWN; sleep 2; shot settings_2
key $DOWN; sleep 2; shot settings_3
key $DOWN; sleep 2; shot settings_4
key $DOWN; sleep 2; shot settings_5
key $RIGHT; sleep 1.5; shot settings_row1
key $DOWN; sleep 1; shot settings_row2
rec_start toggle 5
key $OK; sleep 3
rec_sheet toggle 2 10
shot settings_toggled
key $OK; sleep 1.5
key $DOWN $DOWN $DOWN $DOWN; sleep 1.5; shot settings_row6
key $OK; sleep 3; shot settings_choice
key $BACK; sleep 1.5
key $BACK; sleep 1; key $BACK; sleep 3; shot back_main

# 9. Fenêtre « Quitter ? » (en verre), puis on reste
key $BACK; sleep 3; shot quit_sheet
key $OK; sleep 2

# 10. Recherche
top
key $LEFT $LEFT $LEFT $LEFT $LEFT $LEFT $LEFT $LEFT; sleep 1.5; shot search_btn
key $OK; sleep 4; shot search

# Journal : erreurs et plantages éventuels
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
adb logcat -d -v time -s AndroidRuntime:E > "$OUT/crash.txt" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>/dev/null || true
exit 0
