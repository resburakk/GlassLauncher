#!/bin/bash
# Emülatörde uygulamayı kurup açar; çökme olursa yığın izini GitHub açıklaması olarak raporlar.
APK="$1"
adb install -r "$APK" || { echo "::error::Kurulum başarısız"; exit 1; }
adb logcat -c
adb shell am start -W -n dev.glass.launcher/.MainActivity
sleep 25
# Ayarlar kısayolu ile de aç (ayarlar ekranı)
adb shell am start -W -n dev.glass.launcher/.SettingsEntry
sleep 8
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
echo "::notice::Uygulama açıldı ve çalışıyor (pid $pid)"
