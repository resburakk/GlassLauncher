#!/bin/bash
# Emülatörde uygulamayı kurup açar, temel hareketleri dener, ekran görüntüleri alır;
# çökme olursa yığın izini GitHub açıklaması olarak raporlar.
APK="$1"
adb install -r "$APK" || { echo "::error::Kurulum başarısız"; exit 1; }
adb logcat -c
adb shell cmd role add-role-holder --user 0 android.app.role.HOME dev.glass.launcher || true
# Cam efekti: sistem duvar kâğıdını okuma izni (telefonda kullanıcı verir)
adb shell pm grant dev.glass.launcher android.permission.READ_MEDIA_IMAGES || true
adb shell appops set --uid dev.glass.launcher MANAGE_EXTERNAL_STORAGE allow || true
# Ana ekranda gezinti çubuğu gizlenir: Android'in ilk seferlik "tam ekran" bilgi penceresi görülmüş sayılır
# (telefonda kullanıcı bir kez "Anladım" der; testte ekran görüntülerini kapatmasın).
adb shell settings put secure immersive_mode_confirmations confirmed || true
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
  xml=$(adb shell cat /sdcard/ui.xml | tr '>' '\n')
  b=$(echo "$xml" | grep -F "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  # Bulunamazsa: yazısı bununla başlayan öğe (birleşik satırlar, ör. "Glass Launcher\n5 widget")
  [ -z "$b" ] && b=$(echo "$xml" | grep -F "text=\"$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  set -- $b
  if [ -n "$4" ]; then adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); return 0; fi
  echo "::warning::Ekranda bulunamadı: $want"; return 1
}

# Ekrandaki bir yazının merkezine basılı tut (uzun basma: menü açılır)
press_text() {
  adb shell rm -f /sdcard/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  set -- $b
  if [ -n "$4" ]; then
    x=$(( ($1+$3)/2 )); y=$(( $2 - ($4-$2)*2 ))
    [ $y -lt 0 ] && y=$(( ($2+$4)/2 ))
    adb shell input motionevent DOWN $x $y; sleep 1.2; adb shell input motionevent UP $x $y; return 0
  fi
  echo "::warning::Basılı tutulacak öğe bulunamadı"; return 1
}

# Ekrandaki bir öğeye erişilebilirlik açıklamasından dokun
tap_desc() {
  adb shell rm -f /sdcard/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "content-desc=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  set -- $b
  if [ -n "$4" ]; then adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); return 0; fi
  echo "::warning::Ekranda bulunamadı (açıklama)"; return 1
}

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
tap_text "+"; step "artı menüsü" 2
shot 04p-arti-menu
tap_text "Duvar Kâğıdı"; step "duvar kâğıdı paneli" 2
shot 04w-duvar-kagidi-paneli
adb shell input keyevent KEYCODE_BACK; step "paneli kapat" 1
adb shell input keyevent KEYCODE_BACK; step "geri"

# 3) Uzun bas + sürükle
adb shell input swipe $ix $iy $((W*60/100)) $((H*25/100)) 2500; step "sürükle-bırak" 4
adb shell input keyevent KEYCODE_BACK; step "geri"
# Klasör, çoklu seçimle kurulur (sürükleyip bekleme emülatörde yer değiştirmeye dönüşüp klasör oluşturmuyordu).
adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
tap_text "Seç"; step "seçim modu" 2
adb shell input tap $ix $iy; sleep 0.6
adb shell input tap $((W*38/100)) $iy; step "iki simge seçildi" 2
tap_text "Klasör Oluştur"; step "klasör oluştur" 2
tap_text "Bitti"; step "seçimi bitir" 1
adb shell input keyevent KEYCODE_BACK; step "düzenlemeden çık" 2
shot 04a-surukle-sonrasi
# Klasör adından bulunur (simge yerleşimi değişince sabit koordinat başka uygulamaya denk geliyordu).
{ tap_text "Klasör" || adb shell input tap $ix $iy; }; step "klasör" 2
shot 04b-klasor
# Klasörün içindeki ilk simgeye basılı tut: menü klasörün üstünde açılmalı
fx=$((W*28/100)); fy=$((H*38/100))
adb shell input motionevent DOWN $fx $fy; sleep 1.2
adb shell input motionevent UP $fx $fy; step "klasör içi menü" 2
shot 04c-klasor-menu
# Menü boşluğa dokunarak kapatılır; ikinci basılı tutmada menü yeniden açılmalı (telefonda yalnızca bir kez açılıyordu).
adb shell input tap $((W/2)) $((H*8/100)); step "menüyü dokunarak kapat" 2
shot 04c1-klasor-menu-kapandi
adb shell input motionevent DOWN $fx $fy; sleep 1.2
adb shell input motionevent UP $fx $fy; step "klasör içi menü 2" 2
shot 04c2-klasor-menu-ikinci
# Üçüncü ve dördüncü kez (telefonda üçüncüde açılmıyordu)
for n in 3 4; do
  adb shell input tap $((W/2)) $((H*8/100)); step "menüyü kapat $n" 2
  adb shell input motionevent DOWN $fx $fy; sleep 1.2
  adb shell input motionevent UP $fx $fy; step "klasör içi menü $n" 2
  shot 04c$n-klasor-menu-$n
done
# Klasör içinde sıralama: ikinci simgeyi (Photos) basılı tutup ilk simgenin (Calendar) yerine sürükle.
adb shell input tap $((W/2)) $((H*8/100)); step "menüyü kapat (sıralama)" 2
gx=$((W*50/100))
adb shell input motionevent DOWN $gx $fy; sleep 1.3
for f in 1 2 3 4 5 6 7 8; do adb shell input motionevent MOVE $((gx + (fx-gx)*f/8)) $fy; sleep 0.12; done
sleep 0.8
shot 04e0-klasor-surukleme
adb shell input motionevent UP $fx $fy; step "klasör içinde bırak" 2
shot 04e-klasor-siralama
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
order=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -oE 'text="(Calendar|Photos)"[^/]*bounds="\[[0-9]+' | sed -E 's/text="([A-Za-z]+)".*\[([0-9]+)/\2 \1/' | sort -n | awk '{print $2}' | tr '\n' ' ')
echo "::notice::Klasör içi sıra (soldan sağa): $order"
adb shell input keyevent KEYCODE_BACK; step "menüyü kapat" 1
adb shell input keyevent KEYCODE_BACK; step "geri"

# Uygulama kısayolları: menüde tek satırda ("Kısayollar"), dokununca açılır; simgeler tek renk, zeminsiz olmalı.
for app in Gmail Clock Maps Camera; do
  if press_text "$app"; then
    step "$app menüsü" 2
    tap_text "Kısayollar" && step "kısayollar açıldı" 2
    shot 04k-kisayol-$app
    adb shell input keyevent KEYCODE_BACK; step "menüyü kapat" 2
  fi
done

# 4) Sayfalar, arama
adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa"
shot 04d-ikinci-sayfa
adb shell input swipe $((W*2/10)) $cy $((W*8/10)) $cy 250; step "önceki sayfa"
adb shell input swipe $((W*80/100)) $((H*40/100)) $((W*80/100)) $((H*70/100)) 300; step "ortadan aşağı kaydırma: arama (varsayılan)" 2
shot 05k-sag-kaydirma
adb shell cmd statusbar collapse; step "paneli kapat" 2
adb shell input keyevent KEYCODE_BACK; step "klavyeyi kapat" 1
adb shell input keyevent KEYCODE_BACK; step "aramayı kapat" 2
adb shell input tap $cx $((H*81/100)); step "arama (Ara düğmesi)" 2
shot 05-arama
tap_text "Daha Fazla" && { step "öneriler: daha fazla" 2; sleep 3; shot 05a-oneri-8; tap_text "Daha Az" && step "öneriler: daha az" 1; }
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
tap_text "Uygulama Arşivi" && { step "arşiv arama listesi" 3; shot 07b-arsiv-liste
  tap_text "M" && { step "harf dizini: M" 2; shot 07b2-arsiv-dizin-M; }
  adb shell input keyevent KEYCODE_BACK; step "klavye kapandı" 1
  adb shell input keyevent KEYCODE_BACK; step "geri: liste kapanmalı" 2
  shot 07c-arsiv-geri; }
tap_desc "Son Eklenenler kategorisini aç" && { sleep 0.15; shot 07d0-kategori-acilis; step "kategori tam ekran" 2
  shot 07d-arsiv-kategori
  adb shell input tap $cx $((H*90/100)); step "boş yere dokun: kapanmalı" 2
  shot 07e-kategori-kapandi; }
adb shell input keyevent KEYCODE_HOME; step "ana ekran"
# Düzenleme modunda arşive geçiş: Seç / Bu Sayfayı Sil arşivde görünmemeli
adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
for i in 1 2 3 4 5 6; do adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 200; sleep 1; done
step "arşiv (düzenleme modu)" 2
shot 07f-arsiv-duzenleme
adb shell input keyevent KEYCODE_BACK; step "düzenlemeden çık" 1
adb shell input keyevent KEYCODE_HOME; step "ana ekran"

# Widget sayfası (iOS Bugün görünümü): ilk sayfada sağa kaydır
adb shell input swipe $((W*15/100)) $cy $((W*60/100)) $cy 400; sleep 0.05; shot 21a0-bugun-acilis; step "widget sayfası" 2
shot 21a-bugun
tap_text "Widget Ekle" && { step "widget galerisi (bugün)" 7
  shot 21b-bugun-galeri
  adb shell input keyevent KEYCODE_BACK; step "galeri kapandı" 2; }
adb shell input swipe $((W*85/100)) $cy $((W*15/100)) $cy 300; step "widget sayfası kapandı" 2
shot 21c-bugun-kapandi
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
tap_text "+"; step "artı menüsü" 2
tap_text "Widget Ekle"; step "widget galerisi" 7
shot 14a-widget-galerisi
tap_text "Clock"; step "saat widgetları" 4
shot 14b-widget-detay
adb shell input swipe $((W*80/100)) $((H*50/100)) $((W*20/100)) $((H*50/100)) 300; step "sonraki boyut" 2
shot 14c-widget-detay-2
tap_text "Widget Ekle"; step "widget eklendi" 4
shot 14e-widget-ekle-sonrasi
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

# 17) Bildirim sayıları: erişim izni + örnek bildirim (çökme olmamalı)
adb shell cmd notification allow_listener dev.glass.launcher/dev.glass.launcher.service.BadgeListener || true
adb shell cmd notification post -t "Test" glasstest "Rozet testi" >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_HOME; step "rozet" 3
shot 18a-rozet

# 18) Yeni yüklenen uygulama ana ekranın sonuna gelmeli
newpkg=""; newlabel=""
for pl in "com.google.android.contacts:Contacts" "com.android.contacts:Contacts" "com.google.android.calculator:Calculator" "com.android.calculator2:Calculator"; do
  p=${pl%%:*}
  if adb shell pm path $p >/dev/null 2>&1; then newpkg=$p; newlabel=${pl#*:}; break; fi
done
if [ -n "$newpkg" ]; then
  adb shell pm uninstall -k --user 0 $newpkg >/dev/null; step "uygulama kaldırıldı" 4
  adb shell cmd package install-existing $newpkg >/dev/null; step "uygulama yeniden yüklendi" 5
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
  shot 18b-yeni-uygulama
  found=""
  for pg in 1 2 3 4 5 6; do
    for t in 1 2 3 4 5; do
      adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      adb shell ls /sdcard/ui.xml >/dev/null 2>&1 && break; sleep 1
    done
    xml=$(adb shell cat /sdcard/ui.xml)
    echo "$xml" | grep -qF "Son Eklenenler" && break   # Uygulama Arşivi'ne gelindi: ana ekran sayfaları bitti
    if echo "$xml" | grep -qF "text=\"$newlabel\""; then found=$pg; shot 18c-yeni-uygulama-sayfa; break; fi
    adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa" 2
  done
  if [ -n "$found" ]; then echo "::notice::YENİ UYGULAMA ana ekranda, sayfa $found: $newlabel"
  else echo "::warning::YENİ UYGULAMA ana ekran sayfalarında yok: $newlabel ($newpkg)"; fi
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
fi

# 16) Son uygulamalar: ana ekran içeriği solmalı (yarı saydam kart görünmemeli)
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input keyevent KEYCODE_APP_SWITCH; sleep 0.25
shot 16a-son-uygulamalar-gecis
step "son uygulamalar" 2
shot 16b-son-uygulamalar
adb shell input keyevent KEYCODE_HOME; step "ana ekrana dönüş" 2
shot 16c-donus

# 19) Simgeyi sağ kenara sürükleyip bekle: sonda yeni boş sayfa açılmalı, simge oraya geçmeli
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input motionevent DOWN $ix $iy; sleep 1.3
adb shell input motionevent MOVE $((W*60/100)) $((H*40/100)); sleep 0.3
adb shell input motionevent MOVE $((W*97/100)) $((H*40/100)); sleep 1.6
shot 19a-kenarda-bekle
adb shell input motionevent MOVE $((W*96/100)) $((H*40/100)); sleep 1.6
adb shell input motionevent MOVE $((W*50/100)) $((H*30/100)); sleep 0.8
shot 19b-yeni-sayfa-surukle
adb shell input motionevent UP $((W*50/100)) $((H*30/100)); step "bırak" 2
shot 19c-yeni-sayfa-birakildi
adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa" 2
shot 19d-sonraki-sayfa
adb shell input keyevent KEYCODE_BACK; step "düzenlemeden çık" 1

# 20) Koyu mod: tüm simgeler iOS gibi koyu sürüme geçmeli
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 1
adb shell cmd uimode night yes; step "koyu mod" 6
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
shot 20a-koyu-mod
adb shell input swipe $((W*8/10)) $cy $((W*2/10)) $cy 250; step "sonraki sayfa" 3
shot 20b-koyu-mod-sayfa2
adb shell cmd uimode night no; step "açık mod" 5
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
shot 20c-acik-mod

# 22) Şeffaf (iOS 26 clear) simgeler ve Gizli/Kilitli uygulamalar sayfası
adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 4
tap_text "Simgeler" && { step "simgeler sayfası" 2
  tap_text "Şeffaf" && step "şeffaf simge" 2
  shot 22a0-simge-ayari
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 4
  shot 22a-seffaf-simgeler
  adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 3
  tap_text "Renkli" && step "renkli simge" 2
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 4
  shot 22c-renkli-simgeler
  adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 3
  tap_text "Açık" && step "açık simge" 2
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 4
  shot 22d-acik-simgeler
  adb shell am start -W -n dev.glass.launcher/.SettingsEntry; step "ayarlar" 3
  tap_text "Orijinal"; step "orijinal simge" 2
  adb shell input keyevent KEYCODE_BACK; step "geri" 2; }
adb shell input swipe $cx $((H*80/100)) $cx $((H*20/100)) 300; step "aşağı kaydır" 2
tap_text "Gizli ve Kilitli Uygulamalar" && { step "gizlilik sayfası" 3; shot 22b-gizlilik; }
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3

# 23) Hızlı cevaplar: hesap makinesi, birim çevirme, ayar arama
adb shell input tap $cx $((H*81/100)); step "arama" 2
adb shell "input text '12*7+3'"; step "hesap" 5
shot 23a-hesap
for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_DEL; done
adb shell "input text '5%skm%smil'"; step "birim" 5
shot 23b-birim
for i in 1 2 3 4 5 6 7 8 9 10; do adb shell input keyevent KEYCODE_DEL; done
adb shell input text "wifi"; step "ayar arama" 2
shot 23c-ayar
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; step "geri" 2

# 24) Kilit açılışı animasyonu: ekranı kapat-aç, açılırken kareler
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input keyevent KEYCODE_SLEEP; step "ekran kapalı" 2
adb shell input keyevent KEYCODE_WAKEUP; sleep 0.5
adb shell wm dismiss-keyguard; sleep 0.25; shot 24a-acilis-1
sleep 0.2; shot 24b-acilis-2
step "açıldı" 2; shot 24c-acilis-son

# 24b) Serbest yerleşim: ilk simgeyi sayfanın alt sağındaki boş hücreye sürükle (önüne boşluk eklenmeli)
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
tx=$((W*85/100)); ty=$((H*60/100))
adb shell input motionevent DOWN $ix $iy; sleep 0.8
for f in 1 2 3 4 5 6 7 8 9 10; do adb shell input motionevent MOVE $((ix + (tx-ix)*f/10)) $((iy + (ty-iy)*f/10)); sleep 0.08; done
sleep 0.8
adb shell input motionevent UP $tx $ty; step "boş hücreye bırak" 2
adb shell input keyevent KEYCODE_BACK; step "düzenlemeden çık" 2
shot 24d-serbest-yerlesim

# 25) Düzenleme: Sayfaları Düzenle ve Özelleştir paneli
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
tap_text "Sayfalar" && { step "sayfalar" 3; shot 25a-sayfalari-duzenle; tap_text "Bitti"; step "kapat" 2; }
tap_text "+" && { step "artı menü" 2; tap_text "Özelleştir" && { step "özelleştir" 3; shot 25b-ozellestir; adb shell input keyevent KEYCODE_BACK; step "geri" 1; }; }
adb shell input keyevent KEYCODE_BACK; step "düzenlemeden çık" 1

# 26) Kendi widget'larımız (Takvim, Hava Durumu) ve sayfa kayarken cam: parmak ekrandayken kareler
add_builtin() {
  adb shell input swipe $cx $((H*62/100)) $cx $((H*62/100)) 900; step "düzenleme modu" 2
  tap_text "+"; step "artı" 2
  tap_text "Widget Ekle"; step "galeri" 5
  # Kendi widget'larımız listenin başında, "Glass Launcher Widget'ları" başlığı altında.
  shot 26g-galeri-baslik
  tap_text "$1" && { step "$1" 3; tap_text "Widget Ekle"; step "eklendi" 3; }
  adb shell input keyevent KEYCODE_BACK; step "geri" 1
  adb shell input keyevent KEYCODE_BACK; step "düzenlemeden çık" 2
}
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
add_builtin "Takvim"
add_builtin "Hava Durumu"
# Yeni widget'lar: her biri eklendiği sayfada görüntülenir.
n=1
for wname in "Uygulama Önerileri" "Yaklaşan Etkinlikler" "Şimdi Çalıyor" "Namaz Vakitleri"; do
  add_builtin "$wname"
  shot 26n$n-yeni-widget
  n=$((n+1))
done
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
shot 26a-widgetlar
adb shell input motionevent DOWN $((W*80/100)) $((H*55/100)); sleep 0.1
for f in 1 2 3 4 5 6; do adb shell input motionevent MOVE $((W*80/100 - W*f/20)) $((H*55/100)); sleep 0.05; done
sleep 0.3; shot 26b-widget-kayarken
adb shell input motionevent MOVE $((W*80/100)) $((H*55/100)); adb shell input motionevent UP $((W*80/100)) $((H*55/100)); step "bırak" 2
shot 26c-widget-durunca
# 27) Widget'a dokununca ilgili uygulama açılır (iOS gibi): Takvim widget'ı
tap_desc "Takvim widget'ı" && { step "takvim widget dokunuş" 3; shot 27a-widget-dokunus; adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | sed 's/^ */::notice::ACIK /'; }
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2

# 28) Widget sayfası geçişi yarıda tutulur: ana ekrandaki widget camlarının içi duvar kâğıdıyla hizalı olmalı.
adb shell input keyevent KEYCODE_HOME; step "ana ekran" 2
adb shell input keyevent KEYCODE_HOME; step "ilk sayfa" 2
# Önceki adımlardan kalan düzenleme/seçim modu geçişi engellemesin: launcher yeniden başlatılır.
adb shell am force-stop dev.glass.launcher; sleep 1
adb shell am start -W -n dev.glass.launcher/.MainActivity >/dev/null; step "launcher yeniden" 5
shot 28a-gecis-oncesi
adb shell input motionevent DOWN $((W*20/100)) $((H*45/100))
for f in 1 2 3 4 5 6 7 8 9 10; do adb shell input motionevent MOVE $((W*20/100 + W*f*3/100)) $((H*45/100)); done
sleep 0.8; shot 28b-gecis-yarida
adb shell input motionevent MOVE $((W*20/100)) $((H*45/100)); adb shell input motionevent UP $((W*20/100)) $((H*45/100)); step "bırak" 2

# 29) Cam 27 simge paketi: kurulur, Ayarlar > Simgeler'den seçilir, ana ekran görüntülenir.
PACK=iconpack/build/outputs/apk/release/iconpack-release.apk
if [ -f "$PACK" ]; then
  adb install -r "$PACK" >/dev/null && echo "::notice::Simge paketi kuruldu" || echo "::warning::Simge paketi kurulamadı"
  adb shell am start -W -n dev.glass.launcher/.SettingsEntry >/dev/null; step "ayarlar" 3
  tap_text "Simgeler" && step "simgeler" 2
  shot 29a-simge-ayarlari
  tap_text "Cam 27" && step "paket seçildi" 2
  adb shell input keyevent KEYCODE_HOME; step "ana ekran" 3
  adb shell input keyevent KEYCODE_HOME; step "ilk sayfa" 2
  shot 29b-simge-paketi
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
