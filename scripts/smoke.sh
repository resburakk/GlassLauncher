#!/bin/bash
# Emülatörde uygulamayı kurup açar, temel hareketleri dener, ekran görüntüleri alır;
# çökme olursa yığın izini GitHub açıklaması olarak raporlar.
APK="$1"
adb install -r "$APK" || { echo "::error::Kurulum başarısız"; exit 1; }
adb logcat -c
adb shell cmd role add-role-holder --user 0 android.app.role.HOME dev.glass.launcher || true
# Cam efekti: sistem duvar kâğıdını okuma izni (telefonda kullanıcı verir)
adb shell appops set --uid dev.glass.launcher MANAGE_EXTERNAL_STORAGE allow || true
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

# Ekrandaki bir yazının merkezine dokun (uiautomator dökümünden)
tap_text() {
  want="$1"
  # Ekran dökümü bazen zaman aşımına uğrar (sürekli çizilen saniye ibresi vb.): birkaç kez dene.
  for t in 1 2 3 4 5; do
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell ls /sdcard/ui.xml >/dev/null 2>&1 && break
    sleep 1
  done
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  set -- $b
  if [ -n "$4" ]; then adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); return 0; fi
  echo "::warning::Ekranda bulunamadı: $want"; return 1
}

shot 01-ana-ekran
echo "::notice::DUVAR izin=$(adb shell appops get --uid dev.glass.launcher MANAGE_EXTERNAL_STORAGE | tr -d '\r' | head -1)"
echo "::notice::DUVAR kopya=$(adb shell run-as dev.glass.launcher ls -la files/ 2>&1 | tr -s ' ' | tr -d '\r' | head -8 | tr '\n' ' ')"

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
tap_text "Duvar Kâğıdı"; step "duvar kâğıdı paneli" 2
shot 04w-duvar-kagidi-paneli
adb shell input keyevent KEYCODE_BACK; step "paneli kapat" 1
adb shell input keyevent KEYCODE_BACK; step "geri"

# 3) Uzun bas + sürükle
adb shell input swipe $ix $iy $((W*60/100)) $((H*25/100)) 2500; step "sürükle-bırak" 4
adb shell input keyevent KEYCODE_BACK; step "geri"
# Takvim'i Saat'in üstüne sürükleyip bekleyerek klasör oluştur, sonra aç
jx=$((W*38/100))
adb shell input motionevent DOWN $ix $iy; sleep 0.7
for f in 1 2 3 4 5 6 7 8; do adb shell input motionevent MOVE $((ix + (jx-ix)*f/8)) $iy; sleep 0.08; done
sleep 1.5
adb shell input motionevent UP $jx $iy; step "klasör oluştur" 2
adb shell input keyevent KEYCODE_BACK; step "geri" 2
shot 04a-surukle-sonrasi
adb shell input tap $ix $iy; step "klasör" 2
shot 04b-klasor
# Klasörün içindeki ilk simgeye basılı tut: menü klasörün üstünde açılmalı
fx=$((W*28/100)); fy=$((H*38/100))
adb shell input motionevent DOWN $fx $fy; sleep 1.2
adb shell input motionevent UP $fx $fy; step "klasör içi menü" 2
shot 04c-klasor-menu
adb shell input keyevent KEYCODE_BACK; step "menüyü kapat" 1
adb shell input keyevent KEYCODE_BACK; step "geri"

# 4) Sayfalar, arama
adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa"
shot 04d-ikinci-sayfa
adb shell input swipe $((W*2/10)) $cy $((W*8/10)) $cy 250; step "önceki sayfa"
adb shell input swipe $((W*80/100)) $((H*40/100)) $((W*80/100)) $((H*70/100)) 300; step "sağdan kaydırma: kontrol merkezi" 2
shot 05k-sag-kaydirma
adb shell cmd statusbar collapse; step "paneli kapat" 2
adb shell input tap $cx $((H*81/100)); step "arama (Ara düğmesi)" 2
shot 05-arama
adb shell dumpsys input_method | grep -iE "mInputShown|mShowRequested|mServedView|mIsInputViewShown|hardKeyboard|mShowExplicitlyRequested|mShowForced" | head -8 | while IFS= read -r l; do echo "::notice::IME $l"; done
adb shell input text "c"; step "arama yaz" 2
shot 05b-arama-sonuc
adb shell input text "c"; step "arama yaz" 2
shot 05b-arama-sonuc
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; step "geri"

# 5) Çift dokunma (izin penceresi)
adb shell input tap $cx $((H*62/100)); adb shell input tap $cx $((H*62/100)); step "çift dokunma" 2
shot 06-cift-dokunma
adb shell input keyevent KEYCODE_BACK; step "geri"

# 6) Arşiv
for i in 1 2 3 4 5 6; do adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 200; sleep 1; done
shot 07a-arsiv-hemen
step "arşiv" 3
shot 07-arsiv
adb shell input keyevent KEYCODE_HOME; step "ana ekran"

# 7) Üstten kaydırma (sistem Kontrol Merkezi) — en sonda, sistem paneli diğer adımları etkilemesin
adb shell input swipe $cx $((H*8/100)) $cx $((H*50/100)) 300; step "kontrol merkezi" 3
shot 08-kontrol-merkezi
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_HOME; step "ana ekran"

# 8) Ayarlar
adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 4
shot 09-ayarlar
tap_text "Varsayılan uygulama sıralaması"; step "sıralama düğmesi" 2
shot 09b-siralama-onay
tap_text "Uygula"; step "sıralamayı uygula" 2
shot 09c-siralama-uygulandi
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
shot 10-siralama-sonrasi
# Çoklu seçim: düzenleme modu > Seç > iki simge > Klasör Oluştur
adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
tap_text "Seç"; step "seçim modu" 2
adb shell input tap $ix $iy; sleep 0.6
adb shell input tap $((W*38/100)) $iy; step "iki simge seçildi" 2
shot 12-coklu-secim
# Toplu sürükleme: seçili simgeyi tut, ortaya taşı, havadayken görüntü al
adb shell input motionevent DOWN $ix $iy; sleep 0.8
for f in 1 2 3 4 5 6; do adb shell input motionevent MOVE $((ix + (cx-ix)*f/6)) $((iy + (H*55/100-iy)*f/6)); sleep 0.08; done
sleep 0.6
shot 12d-toplu-surukleme
adb shell input motionevent UP $cx $((H*55/100)); step "bırak" 2
tap_text "Klasör Oluştur"; step "klasör oluştur" 2
shot 12b-secimden-klasor
adb shell input tap $ix $iy; step "seçim modunda klasörü aç" 2
adb shell input tap $((W*28/100)) $((H*38/100)); step "klasör içinden seç" 2
shot 12c-klasor-icinde-secim
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; step "geri"

# Uygulama aç (simgeden büyüyerek) ve ana ekrana dön (simgeye küçülme efekti)
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input keyevent KEYCODE_HOME; step "öndeyken Home: ilk sayfa" 2
shot 13-ilk-sayfa
adb shell input tap $ix $((H*25/100)); sleep 0.25
shot 13a-acilis
step "uygulama açık" 3
adb shell input keyevent KEYCODE_HOME; sleep 0.12
shot 13b-donus
step "dönüş bitti" 2
shot 13c-donus-sonrasi

# Widget galerisi: düzenleme modu > + > Clock > Widget Ekle (bağlama izni teste özel komutla verilir)
adb shell appwidget grantbind --package dev.glass.launcher --user 0 || true
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
tap_text "+"; step "widget galerisi" 7
shot 14a-widget-galerisi
tap_text "Clock"; step "saat widgetları" 4
shot 14b-widget-detay
adb shell input swipe $((W*80/100)) $((H*50/100)) $((W*20/100)) $((H*50/100)) 300; step "sonraki boyut" 2
shot 14c-widget-detay-2
tap_text "Widget Ekle"; step "widget eklendi" 4
shot 14e-widget-ekle-sonrasi
adb shell dumpsys appwidget | grep -A3 "dev.glass.launcher" | head -12 | while IFS= read -r l; do echo "::notice::WIDGET $l"; done
adb shell input tap $cx $((H*90/100)); step "boşluğa dokun: düzenlemeden çık" 2
shot 14d-widget-ana-ekran

# Galeriden "Farklı ayarla" penceresi (ATTACH_DATA)
adb shell am start -a android.intent.action.ATTACH_DATA -t image/png -d content://media/external/images/media/1 -n dev.glass.launcher/.WallpaperImportActivity; step "farklı ayarla" 3
shot 11-farkli-ayarla
adb shell input keyevent KEYCODE_BACK; step "geri"

# Cam kırılması testi: çizgili test duvar kâğıdı launcher duvar kâğıdı olarak ayarlanır (galeriden
# "Farklı ayarla" yoluyla), sonra dock ve bağlam menüsü görüntülenir. Çizgiler cam kenarında bükülmeli.
adb push "$(dirname "$0")/test-wallpaper.png" /sdcard/Pictures/glass-test.png >/dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 2
mid=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name | grep glass-test | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2)
echo "::notice::Test duvar kâğıdı medya kimliği: ${mid:-yok}"
if [ -n "$mid" ]; then
  adb shell am start -a android.intent.action.ATTACH_DATA -t image/png -d content://media/external/images/media/$mid --grant-read-uri-permission -n dev.glass.launcher/.WallpaperImportActivity; step "farklı ayarla" 3
  tap_text "Ana ekran"; step "launcher duvar kâğıdı" 5
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
  shot 15a-cam-dock
  adb shell input motionevent DOWN $ix $iy; sleep 1.2
  adb shell input motionevent UP $ix $iy; step "menü" 2
  shot 15b-cam-menu
  adb shell input keyevent KEYCODE_BACK; step "geri"
fi

adb logcat -d > logcat.txt
grep "GlassDiag" logcat.txt | sed 's/^.*GlassDiag: //' | sort -u | head -20 | while IFS= read -r line; do echo "::notice::DIAG $line"; done
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
