#!/bin/bash
# Emülatörde uygulamayı kurup açar, temel hareketleri dener; çökme olursa yığın izini GitHub açıklaması olarak raporlar.
APK="$1"
adb install -r "$APK" || { echo "::error::Kurulum başarısız"; exit 1; }
adb logcat -c
adb shell am start -W -n dev.glass.launcher/.MainActivity
sleep 20

size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${size%x*}; H=${size#*x}
echo "Ekran: ${W}x${H}"
cx=$((W/2)); cy=$((H/2))
step() { echo "--- $1"; sleep "${2:-3}"; }

adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa"
adb shell input swipe $((W*2/10)) $cy $((W*8/10)) $cy 250; step "önceki sayfa"
adb shell input swipe $cx $((H*4/10)) $cx $((H*7/10)) 300; step "arama"
adb shell input keyevent KEYCODE_BACK; step "geri"
adb shell input swipe $cx 5 $cx $((H*5/10)) 300; step "kontrol paneli"
adb shell input keyevent KEYCODE_BACK; step "geri"
# İlk simgeye uzun bas ve bırak (bağlam menüsü)
ix=$((W*18/100)); iy=$((H*12/100))
adb shell input swipe $ix $iy $ix $iy 900; step "uzun basma menüsü"
adb shell input keyevent KEYCODE_BACK; step "geri"
# Uzun bas ve sürükle (düzenleme modu + taşıma)
adb shell input swipe $ix $iy $((W*60/100)) $((H*25/100)) 2500; step "sürükle-bırak" 4
adb shell input keyevent KEYCODE_BACK; step "geri"
# Son sayfalara (Uygulama Arşivi) git
for i in 1 2 3 4 5 6; do adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 200; sleep 1; done; step "arşiv" 3
adb shell input tap $cx $((H*10/100)); step "arşiv araması"
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; step "geri"
adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 4
adb shell input keyevent KEYCODE_BACK; step "geri"

adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A45 "FATAL EXCEPTION" logcat.txt | head -60 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do
    echo "::error::CRASH ${line//$'%'/%25}"
  done
  exit 1
fi
pid=$(adb shell pidof dev.glass.launcher)
if [ -z "$pid" ]; then
  echo "::error::Uygulama çalışmıyor (çökme kaydı bulunamadı)"
  grep -iE "glass|AndroidRuntime" logcat.txt | tail -30 | while IFS= read -r line; do echo "::error::${line//$'%'/%25}"; done
  exit 1
fi
echo "::notice::Uygulama açıldı, hareketler denendi, çökme yok (pid $pid)"
