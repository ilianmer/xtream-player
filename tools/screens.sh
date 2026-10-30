#!/bin/bash
# Captures d'écran automatiques de l'appli sur un émulateur Android TV (CI seulement).
# Chaque écran est ouvert directement (extra « demo_go », version de test uniquement),
# puis capturé : shots/NN_nom.png. Les animations sont filmées : shots/anim_*.png
set -u
PKG=com.ilian.xtreamplayer
SERVER=http://10.0.2.2:8080
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
    sleep 0.7
  done
}
# Ouvre un écran précis (appli relancée à neuf), puis attend $2 secondes
go() {
  adb shell am start -S -n $PKG/.StartActivity --es demo_server $SERVER --es demo_go "$1" >/dev/null 2>&1
  sleep "${2:-10}"
}
rec_start() {
  adb shell rm -f "/sdcard/$1.mp4" >/dev/null 2>&1
  adb shell screenrecord --size 1280x720 --bit-rate 8000000 --time-limit "$2" "/sdcard/$1.mp4" >/dev/null 2>&1 &
  sleep 0.8
}
rec_sheet() {
  sleep "${2:-1}"
  adb pull "/sdcard/$1.mp4" "/tmp/$1.mp4" >/dev/null 2>&1 || return 0
  ffmpeg -loglevel error -y -i "/tmp/$1.mp4" -vf "fps=${3:-6},scale=480:-1,tile=6x5:padding=4:color=black" -frames:v 1 "$OUT/anim_$1.png" || true
  cp "/tmp/$1.mp4" "$OUT/$1.mp4" 2>/dev/null || true
}
UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4

adb shell settings put global stay_on_while_plugged_in 7 || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat -G 4M >/dev/null 2>&1 || true

# 1. Première ouverture : animation du logo (filmée), mise à jour des listes, accueil
rec_start splash 8
adb shell am start -n $PKG/.StartActivity --es demo_server $SERVER >/dev/null
sleep 4; shot sync
rec_sheet splash 6 8
sleep 28; shot home
key $DOWN; sleep 1.5; shot home_focus

# 2. Onglets
go LIVE 12; shot live
key $DOWN; sleep 1.5; shot live_d1
key $DOWN; sleep 1.5; shot live_d2
go SPORT 14; shot sport
key $DOWN; sleep 1.5; shot sport_d1
go MOVIE 12; shot films
rec_start cards 6
key $DOWN; sleep 1.2; key $RIGHT; sleep 1.2; key $RIGHT; sleep 2
rec_sheet cards 1 8
shot films_cards
go SERIES 12; shot series
key $DOWN; sleep 1.5; shot series_d1
go FAV 9; shot favoris

# 3. Fiches
go movie 12; shot movie
key $DOWN; sleep 1.5; shot movie_d1
go serie 12; shot serie
key $DOWN; sleep 1.5; shot serie_d1

# 4. Lecteur : bandeau (filmé), menu, format d'image
go player 16; shot player
rec_start overlay 5
key $OK; sleep 4
rec_sheet overlay 1 8
shot player_overlay
key $OK; sleep 5; shot player_menu
key $DOWN; sleep 1.5; shot player_menu_d1
key $OK; sleep 4; shot player_submenu
key $BACK; sleep 2

# 5. Réglages, section par section
for s in 0 1 2 3 4; do go "settings:$s" 9; shot "settings_$s"; done
go settings:5 9; shot settings_5
key $RIGHT; sleep 1.5; shot settings_5_row
key $OK; sleep 4; shot settings_5_sheet
key $BACK; sleep 1.5
key $DOWN $DOWN $DOWN; sleep 1.5; shot settings_5_toggle_row
rec_start toggle 4
key $OK; sleep 2.5
rec_sheet toggle 1 10
shot settings_5_toggled
key $OK; sleep 1

# 6. Catégories (et ordre), profils, recherche
go categories 9; shot categories
key $DOWN $DOWN $DOWN $DOWN; sleep 1.5; shot categories_left
go profiles 4
rec_start profiles 5
sleep 5
rec_sheet profiles 1 8
shot profiles
go search 9; shot search

# 7. Fenêtre « Quitter ? »
go HOME 12
key $BACK; sleep 1.5; key $BACK; sleep 4; shot quit_sheet
key $OK; sleep 1

# Journal : erreurs et plantages éventuels
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
adb logcat -d -v time -s AndroidRuntime:E > "$OUT/crash.txt" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>/dev/null || true
exit 0
