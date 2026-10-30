#!/bin/bash
# Captures d'écran automatiques de l'appli sur un émulateur Android TV (CI seulement).
# Chaque capture est numérotée dans l'ordre du parcours : shots/NN_nom.png
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
    sleep 0.45
  done
}
UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4; MENU=82

adb shell settings put global stay_on_while_plugged_in 7 || true
adb shell wm size || true
adb shell wm density || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c

# 1. Ouverture : animation du logo puis mise à jour des listes
adb shell am start -n $PKG/.StartActivity --es demo_server http://10.0.2.2:8080
for i in 1 2 3 4 5 6 7 8; do shot "splash_$i"; done
sleep 3; shot sync
sleep 40; shot home

# 2. Accueil : on descend dans le contenu
key $DOWN; sleep 1; shot home_down1
key $DOWN; sleep 1; shot home_down2
key $DOWN; sleep 1; shot home_down3
key $UP $UP $UP $UP; sleep 1; shot home_top

# 3. Onglets : Direct, Sport, Films, Séries…
key $RIGHT; sleep 3; shot tab_1
key $DOWN; sleep 2; shot tab_1_in
key $OK; sleep 7; shot tab_1_ok
key $OK; sleep 1; shot tab_1_ok2
key $OK; sleep 1; shot tab_1_ok3
key $BACK; sleep 1; key $BACK; sleep 3; shot back_from_player
key $UP $UP $UP; sleep 1
key $RIGHT; sleep 4; shot tab_2
key $DOWN; sleep 1; shot tab_2_in
key $UP $UP $UP; sleep 1
key $RIGHT; sleep 4; shot tab_3
key $DOWN; sleep 1; shot tab_3_in
key $DOWN; sleep 1; shot tab_3_in2
key $OK; sleep 4; shot tab_3_detail
key $BACK; sleep 2
key $UP $UP $UP $UP; sleep 1
key $RIGHT; sleep 4; shot tab_4
key $DOWN; sleep 1; shot tab_4_in
key $OK; sleep 4; shot tab_4_detail
key $DOWN; sleep 1; shot tab_4_detail_down
key $BACK; sleep 2
key $UP $UP $UP $UP; sleep 1
key $RIGHT; sleep 3; shot tab_5
key $RIGHT; sleep 3; shot tab_6
key $RIGHT; sleep 3; shot tab_7
key $RIGHT; sleep 3; shot tab_8
key $OK; sleep 4; shot tab_8_ok
key $DOWN; sleep 1; shot tab_8_ok_down
key $DOWN; sleep 1; shot tab_8_ok_down2
key $OK; sleep 2; shot tab_8_ok_ok
key $BACK; sleep 1; key $BACK; sleep 2; shot end

# Journal : erreurs et plantages éventuels
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
adb logcat -d -v time -s AndroidRuntime:E > "$OUT/crash.txt" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>/dev/null || true
exit 0
