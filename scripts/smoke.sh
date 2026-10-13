#!/bin/bash
# Emülatörde uygulamayı kurup açar, temel hareketleri dener, ekran görüntüleri alır;
# çökme olursa yığın izini GitHub açıklaması olarak raporlar.
APK="$1"
adb install -r "$APK" || { echo "::error::Kurulum başarısız"; exit 1; }
adb logcat -c
adb shell cmd role add-role-holder --user 0 android.app.role.HOME dev.glass.launcher || true
adb shell am start -W -n dev.glass.launcher/.MainActivity
sleep 20
adb shell input keyevent KEYCODE_HOME; sleep 4
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }
step() { echo "--- $1"; sleep "${2:-3}"; }
size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${size%x*}; H=${size#*x}
cx=$((W/2)); cy=$((H/2))
ix=$((W*15/100)); iy=$((H*14/100))

shot 01-ana-ekran

# 1) Uzun basma: menü parmak ekrandayken açılmalı
adb shell input motionevent DOWN $ix $iy
sleep 0.8
shot 02-menu-basili
sleep 1.5
shot 02b-menu-basili-2sn
adb shell input motionevent UP $ix $iy
step "menü (bırakıldı)" 2
shot 03-menu
adb shell input keyevent KEYCODE_BACK; step "geri"

# 2) Boş alana uzun bas: düzenleme modu ve düğmeler
adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
shot 04-duzenleme
adb shell input keyevent KEYCODE_BACK; step "geri"

# 3) Uzun bas + sürükle
adb shell input swipe $ix $iy $((W*60/100)) $((H*25/100)) 2500; step "sürükle-bırak" 4
adb shell input keyevent KEYCODE_BACK; step "geri"

# 4) Sayfalar, arama
adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa"
adb shell input swipe $((W*2/10)) $cy $((W*8/10)) $cy 250; step "önceki sayfa"
adb shell input swipe $cx $((H*40/100)) $cx $((H*70/100)) 300; step "arama" 2
shot 05-arama
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; step "geri"

# 5) Çift dokunma (izin penceresi)
adb shell input tap $cx $((H*62/100)); adb shell input tap $cx $((H*62/100)); step "çift dokunma" 2
shot 06-cift-dokunma
adb shell input keyevent KEYCODE_BACK; step "geri"

# 6) Arşiv
for i in 1 2 3 4 5 6; do adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 200; sleep 1; done; step "arşiv" 3
shot 07-arsiv
adb shell input keyevent KEYCODE_HOME; step "ana ekran"

# 7) Üstten kaydırma (sistem Kontrol Merkezi) — en sonda, sistem paneli diğer adımları etkilemesin
adb shell input swipe $cx $((H*8/100)) $cx $((H*50/100)) 300; step "kontrol merkezi" 3
shot 08-kontrol-merkezi
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_HOME; step "ana ekran"

# 8) Ayarlar
adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 4
shot 09-ayarlar
adb shell input keyevent KEYCODE_BACK; step "geri"

adb logcat -d > logcat.txt
grep "GlassDiag" logcat.txt | sed 's/^.*GlassDiag: //' | sort -u | head -40 | while IFS= read -r line; do echo "::notice::DIAG $line"; done
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A45 "FATAL EXCEPTION" logcat.txt | head -60 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do
    echo "::error::CRASH ${line//$'%'/%25}"
  done
  exit 1
fi
pid=$(adb shell pidof dev.glass.launcher)
if [ -z "$pid" ]; then
  echo "::error::Uygulama çalışmıyor (çökme kaydı bulunamadı)"
  exit 1
fi
echo "::notice::Uygulama açıldı, hareketler denendi, çökme yok (pid $pid)"
