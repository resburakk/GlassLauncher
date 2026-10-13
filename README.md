# Glass Launcher — Aşama 1

## Derleme
1. Android Studio ile `GlassLauncher` klasörünü açın. Gradle wrapper jar'ı pakette yoktur;
   Android Studio Sync sırasında wrapper'ı kendisi oluşturur
   (veya terminalde: `gradle wrapper --gradle-version 8.11.1`).
2. SDK Manager'dan Android 16 (API 36) platformunu kurun. JDK 17+ gerekir.
3. Telefonda Geliştirici seçenekleri > USB hata ayıklama açık, kablo ile bağlayıp Run.
4. APK: `./gradlew assembleDebug` -> app/build/outputs/apk/debug/app-debug.apk

## Varsayılan ana ekran
Uygulamayı açın, üstteki "Varsayılan ana ekran yap" düğmesine dokunup onaylayın.
Olmazsa: Ayarlar > Uygulamalar > Varsayılan uygulamalar > Ana ekran uygulaması.
Geri dönmek için aynı yerden Honor'un kendi ana ekranını seçin.
