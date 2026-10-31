# Glass Launcher 1.0

Honor 600 (Android 16) için iOS 26 "Liquid Glass" ana ekranından esinlenen gerçek bir Android ana ekran uygulaması.
Kotlin + Jetpack Compose, MVVM, Coroutines/Flow, DataStore. Root, internet, reklam veya takip yok.

## Kurulum (telefondan, en kolay yol)

1. Bu depoda **Releases → "Glass Launcher (son derleme)"** sayfasını aç.
2. `GlassLauncher.apk` dosyasını indir ve kur. Bilinmeyen kaynak izni isteyebilir.
3. Uygulama listesinde **Glass Ayarları**'nı aç ve en üstteki **Varsayılan ana ekran yap**'a dokun.
   Olmazsa: Ayarlar → Uygulamalar → Varsayılan uygulamalar → Ana ekran.
4. Eski ana ekrana dönmek için aynı yerden Honor'un kendi ana ekranını seç.

Her APK aynı anahtarla imzalanır. Yeni sürümü eskisini kaldırmadan üzerine kurabilirsin.

## Derleme

- **GitHub Actions:** `claude/build` dalına yapılan her gönderim birim testlerini çalıştırır, debug ve release
  APK üretir, lint raporu çıkarır ve release APK'sını Releases'e yükler.
- **Bilgisayarda:** Android Studio ile aç (JDK 17, SDK 36), sonra `gradle assembleRelease` çalıştır.
  Çıktı: `app/build/outputs/apk/release/app-release.apk`.
- Testler: `gradle testDebugUnitTest` (yerleşim, ızgara yerleşimi, arama, kategori ve ayar JSON'u).

Sürümler: AGP 8.9.1, Gradle 8.11.1, Kotlin 2.1.20, Compose BOM 2025.04.01, compileSdk/targetSdk 36, minSdk 33.
Tüm kütüphaneler ücretsiz ve açık kaynaktır.

## Özellikler

| Bölüm | Neler var |
|---|---|
| Ana ekran | iPhone oranlarında 4 sütun (3–5 ayarlanabilir), çok sayfa, tek satır ortalı adlar, üstten hizalı ızgara, sayfa noktaları ↔ "Ara" hapı |
| Düzenleme | Uzun bas → iOS bağlam menüsü; basılı tutup sürükle → sallanma modu, canlı yer değiştirme, sayfalar arası taşıma, dock'a taşıma |
| Klasör | Simgeyi simgenin üzerine bırak → klasör; kapalıyken 3×3 önizleme (son bakılan sayfa), açıkken ortada cam panel, ad düzenleme, 9'arlı sayfalar, sürükleyerek sıralama, dışarı sürükleyince ana ekrana taşıma, boş/tek klasör otomatik çözülür |
| Dock | 4 simge, cam yüzey, yükseklik/genişlik/saydamlık ayarı, kayarak yer değiştirme |
| Uygulama Arşivi | Son sayfadan sonra; Öneriler, Son Eklenenler ve kategoriler; alfabetik liste ve arama; ana ekrana ekleme/kaldırma |
| Arama | Aşağı kaydır veya "Ara": cam kutu, otomatik klavye, son kullanılanlar, Türkçe karakter duyarsız sıralı arama |
| Widget'lar | Gerçek Android widget'ları (AppWidgetHost) ve launcher'ın kendi widget'ları (saat, analog saat, takvim, pil, fotoğraf); Küçük/Orta/Büyük boyut, taşıma, yapılandırma, kaldırma |
| Simgeler | Orijinal / Açık / Koyu / Renkli; köşe yuvarlaklığı; uygulamaya özel PNG/WebP simge; monokrom katman desteği |
| Liquid Glass | Buzlu dolgu, speküler parlama, kenar ışığı, gölge; launcher duvar kâğıdı seçiliyse gerçek arka plan bulanıklığı + hafif mercek kırılması; açık/koyu tema |
| Animasyon | Yay (spring) tabanlı geçişler, ayarlanabilir hız, simgeden açılan uygulama animasyonu (sistem çizer) |
| Performans | Ekran boyutunda simge bitmap'leri + bellek sınırlı önbellek, sürekli tarama yok, otomatik kalite düşürme, düşük güç modu |
| Ayarlar | Ana Ekran, Simgeler, Dock, Klasörler, Liquid Glass, Duvar Kâğıdı, Animasyonlar, Widget'lar, Uygulama Arşivi, Yedekleme, Performans, Hakkında |
| Yedekleme | Yerleşim + ayarlar JSON olarak dışa/içe aktarma (Android dosya seçicisiyle, izin gerekmez) |
| Üst panel | Üstten aşağı kaydır: saat, internet/Bluetooth/uçak modu kısayolları, fener, medya sesi, pil |

## Hareketler

| Hareket | Sonuç |
|---|---|
| Yatay kaydır | Sayfa değiştir; son sayfadan sonra Uygulama Arşivi |
| Boş alanda aşağı kaydır | Arama |
| Ekranın üstünden aşağı kaydır | Kontrol paneli |
| Simgeye basılı tut, bırak | Bağlam menüsü (kısayollar, simgeyi değiştir, kaldır…) |
| Simgeye basılı tut, sürükle | Düzenleme modu + taşıma |
| Simgeyi simgenin ortasında beklet | Klasör oluştur / klasöre ekle |
| Boş alana basılı tut | Düzenleme modu ("+" widget, "Ayarlar", "Bitti") |
| Home tuşu (launcher açıkken) | İlk sayfaya dön, açık katmanları kapat |

## İzinler

- **Uygulama listesi:** `<queries>` ile yalnızca başlatılabilir uygulamalar sorgulanır. `QUERY_ALL_PACKAGES` kullanılmaz.
- **REQUEST_DELETE_PACKAGES:** "Uygulamayı Sil" seçilince sistemin onay penceresini açmak için. Silme kararını her zaman kullanıcı verir.
- **SET_WALLPAPER:** yalnızca "Sistem duvar kâğıdı olarak da ayarla" açıkken kullanılır.
- **İnternet izni yok.** Depolama izni yok. Görseller sistemin seçicisiyle alınır.

## Bilinen sınırlamalar

- Android, başka uygulamaların (sistem duvar kâğıdı dahil) arkasını bulanıklaştırmaya izin vermez.
  Gerçek bulanıklık yalnızca launcher'a seçilen duvar kâğıdı üzerinde çalışır. Aksi halde cam efekti katmanlı gradyanla çizilir.
- Bildirim gölgesi, Wi-Fi/Bluetooth anahtarları ve uygulama açılış animasyonu sisteme aittir.
- Uygulama kısayolları yalnızca Glass Launcher varsayılan ana ekranken gelir.
- Kategoriler, uygulamanın bildirdiği türe ve adına göre tahmin edilir.
- MagicOS pil yönetimi launcher'ı kapatırsa: Ayarlar → Pil → Uygulama başlatma → Glass Launcher → "Elle yönet".
- Özel simge/fotoğraf görselleri ve başka cihaza bağlı sistem widget'ları yedeğe girmez.
- Android 17 uyumluluğu güncel API'lerle hedeflenmiştir ama cihazda doğrulanmamıştır.

## Cihazda test edilmesi gerekenler

Kod derlenir ve birim testlerinden geçer. Aşağıdakiler ancak gerçek telefonda doğrulanabilir:

1. Varsayılan ana ekran seçimi, Home tuşu, yeniden başlatma sonrası açılış
2. Uzun basma → menü, sürükleme → yer değiştirme, sayfa kenarında bekleyince sayfa geçişi
3. Klasör oluşturma, açma, adlandırma, klasörden dışarı sürükleme
4. Dock'a sürükleme (dolu dock'ta yer değişimi)
5. Sistem widget'ı ekleme (izin penceresi, yapılandırma ekranı), boyut değiştirme
6. Simge görünümleri (özellikle MagicOS temalı simgeler) ve özel simge
7. Duvar kâğıdı seçimi ve gerçek bulanıklık, Pil Tasarrufu'nda sadeleşme
8. Akıcılık (60/90/120 Hz) — FPS garanti edilmez

## Proje yapısı

```
app/src/main/java/dev/glass/launcher/
├── LauncherApp.kt              Uygulama: depolar, simge önbelleği, widget host
├── MainActivity.kt             HOME etkinliği, varsayılan rol, widget ekleme akışı, durum çubuğu tonu
├── data/
│   ├── AppRepository.kt        LauncherApps: uygulama listesi, başlatma, kısayollar, kaldırma
│   ├── AppInfo.kt, AppCategorizer.kt, SearchRanker.kt
│   ├── HomeLayout.kt           Yerleşim modeli (uygulama/klasör/widget/dock/arşiv) + JSON
│   ├── GridPacker.kt           Widget'lı ızgara yerleşimi
│   ├── SettingsStore.kt        DataStore ayarları + yedekleme
│   ├── IconRenderer.kt, IconCache.kt   Simge görünümleri ve önbellek
│   └── WallpaperRepository.kt, BlurUtil.kt   Duvar kâğıdı, fotoğraflar, bulanık kopya
├── widget/WidgetHostManager.kt AppWidgetHost
└── ui/
    ├── HomeScreen.kt           Kök: katmanlar, hareketler, menüler
    ├── HomeContent.kt          Sayfalar, arşiv sayfası, dock, canlı yer değiştirme
    ├── Drag.kt                 Uzun basma/sürükle-bırak, hücreler, klasör simgesi
    ├── FolderOverlay.kt, LibraryUi.kt, SearchOverlay.kt, ControlPanel.kt
    ├── ContextMenu.kt, WidgetPicker.kt, Widgets.kt, SettingsScreen.kt
    └── Glass.kt, Glyphs.kt, Motion.kt, HomeMetrics.kt, LauncherViewModel.kt
```

## İmza anahtarı hakkında

`keystore/glass-launcher.jks` yalnızca kişisel kullanım içindir: her derlemenin aynı imzayı taşıması ve
güncellemelerin üzerine kurulabilmesi için depoya eklendi. Depo herkese açıksa, bu anahtarla başkası da APK
imzalayabilir. Depoyu gizli (Private) yapman önerilir.
