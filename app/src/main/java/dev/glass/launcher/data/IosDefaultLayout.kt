package dev.glass.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.util.UUID

/**
 * iOS 26'nın fabrika ana ekran dizilimi (widget'sız), Android / Honor karşılıklarıyla.
 *
 * 1. sayfa (iOS sırası): FaceTime, Takvim, Fotoğraflar, Kamera · Mail, Notlar, Anımsatıcılar, Saat ·
 * TV, Oyunlar, App Store, Harita · Sağlık, Cüzdan, Ayarlar. Dock: Telefon, Safari, Mesajlar, Müzik.
 * Karşılığı yüklü olmayan yer atlanır, sıra korunur. Geri kalan uygulamalar 2. sayfadan itibaren
 * türlerine göre klasörlenir (iOS'un 2. sayfasındaki "Yardımcı Araçlar" klasörü mantığıyla).
 */
object IosDefaultLayout {

    /**
     * Bir iOS yerinin karşılığı. Sırasıyla denenir: [intents] ile sistemin varsayılan uygulaması,
     * sonra [packages] listesi (Honor, Huawei, Google, AOSP), en son etiket ([labels]) eşleşmesi.
     * Aynı pakette birden çok simge varsa (ör. Honor'da Telefon ve Kişiler) [labels] seçimi yapar.
     */
    private class Slot(
        val intents: List<Intent> = emptyList(),
        val packages: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
        val avoidLabels: List<String> = emptyList(),
    )

    private fun selector(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

    private val page1 = listOf(
        // FaceTime → görüntülü arama
        Slot(
            packages = listOf("com.hihonor.meetime", "com.huawei.meetime", "com.google.android.apps.tachyon", "com.whatsapp"),
            labels = listOf("meetime", "meet", "duo"),
        ),
        // Takvim
        Slot(
            intents = listOf(selector(Intent.CATEGORY_APP_CALENDAR)),
            packages = listOf("com.hihonor.calendar", "com.google.android.calendar", "com.android.calendar", "com.huawei.calendar"),
            labels = listOf("takvim", "calendar"),
        ),
        // Fotoğraflar
        Slot(
            intents = listOf(selector(Intent.CATEGORY_APP_GALLERY)),
            packages = listOf("com.hihonor.photos", "com.huawei.photos", "com.google.android.apps.photos", "com.android.gallery3d"),
            labels = listOf("galeri", "gallery", "fotograflar", "photos"),
        ),
        // Kamera
        Slot(
            intents = listOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            packages = listOf("com.hihonor.camera", "com.huawei.camera", "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            labels = listOf("kamera", "camera"),
        ),
        // Mail
        Slot(
            intents = listOf(selector(Intent.CATEGORY_APP_EMAIL)),
            packages = listOf("com.google.android.gm", "com.hihonor.email", "com.huawei.email", "com.android.email", "com.microsoft.office.outlook"),
            labels = listOf("gmail", "e-posta", "eposta", "email", "mail"),
        ),
        // Notlar
        Slot(
            packages = listOf("com.hihonor.notepad", "com.huawei.notepad", "com.example.android.notepad", "com.google.android.keep"),
            labels = listOf("not defteri", "notlar", "notes", "keep"),
        ),
        // Anımsatıcılar
        Slot(
            packages = listOf("com.google.android.apps.tasks", "com.microsoft.todos", "com.hihonor.reminder"),
            labels = listOf("gorevler", "tasks", "hatirlatici", "animsatici", "reminders"),
        ),
        // Saat
        Slot(
            intents = listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            packages = listOf("com.hihonor.deskclock", "com.android.deskclock", "com.google.android.deskclock", "com.huawei.deskclock"),
            labels = listOf("saat", "clock"),
        ),
        // TV
        Slot(
            packages = listOf("com.google.android.videos", "com.hihonor.himovie", "com.huawei.himovie", "com.hihonor.video"),
            labels = listOf("google tv", "video"),
        ),
        // Oyunlar
        Slot(
            packages = listOf("com.hihonor.gamecenter", "com.huawei.gamebox", "com.google.android.play.games", "com.hihonor.gamebox"),
            labels = listOf("oyun merkezi", "game center", "play oyunlar", "play games"),
        ),
        // App Store → Play Store (yoksa cihazın kendi mağazası)
        Slot(
            packages = listOf("com.android.vending", "com.hihonor.appmarket", "com.huawei.appmarket"),
            labels = listOf("play store", "appgallery", "app market"),
        ),
        // Harita
        Slot(
            intents = listOf(selector(Intent.CATEGORY_APP_MAPS)),
            packages = listOf("com.google.android.apps.maps", "com.huawei.maps.app", "com.hihonor.maps", "ru.yandex.yandexnavi"),
            labels = listOf("haritalar", "harita", "maps"),
        ),
        // Sağlık
        Slot(
            packages = listOf("com.hihonor.health", "com.huawei.health", "com.google.android.apps.fitness", "com.google.android.apps.healthdata"),
            labels = listOf("saglik", "health", "fit"),
        ),
        // Cüzdan
        Slot(
            packages = listOf("com.google.android.apps.walletnfcrel", "com.hihonor.wallet", "com.huawei.wallet"),
            labels = listOf("cuzdan", "wallet"),
        ),
        // Ayarlar
        Slot(
            intents = listOf(Intent(Settings.ACTION_SETTINGS)),
            packages = listOf("com.android.settings"),
            labels = listOf("ayarlar", "settings"),
        ),
    )

    private val dock = listOf(
        // Telefon (Honor'da Telefon ve Kişiler aynı pakette olabilir)
        Slot(
            intents = listOf(Intent(Intent.ACTION_DIAL)),
            packages = listOf("com.hihonor.contacts", "com.huawei.contacts", "com.google.android.dialer", "com.android.dialer", "com.android.contacts"),
            labels = listOf("telefon", "phone", "arama"),
            avoidLabels = listOf("kisiler", "contacts", "rehber"),
        ),
        // Safari → varsayılan tarayıcı
        Slot(
            intents = listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)),
            packages = listOf("com.android.chrome", "com.hihonor.browser", "com.huawei.browser", "org.mozilla.firefox"),
            labels = listOf("chrome", "tarayici", "browser"),
        ),
        // Mesajlar
        Slot(
            intents = listOf(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            packages = listOf("com.google.android.apps.messaging", "com.hihonor.mms", "com.huawei.message", "com.android.mms"),
            labels = listOf("mesajlar", "messages", "mesaj"),
        ),
        // Müzik
        Slot(
            intents = listOf(selector(Intent.CATEGORY_APP_MUSIC)),
            packages = listOf("com.hihonor.music", "com.huawei.music", "com.google.android.apps.youtube.music", "com.spotify.music", "com.android.music"),
            labels = listOf("muzik", "music"),
        ),
    )

    fun build(context: Context, apps: List<AppInfo>): HomeLayout {
        val me = Process.myUserHandle()
        // Önce ana kullanıcıdaki uygulamalar (iş profili kopyaları klasörlere düşer).
        val mine = apps.filter { it.user == me }.ifEmpty { apps }
        val used = HashSet<String>()

        fun pick(slot: Slot): AppInfo? {
            val free = mine.filter { it.key !in used }
            fun choose(cands: List<AppInfo>, resolved: ComponentName? = null): AppInfo? {
                if (cands.isEmpty()) return null
                resolved?.let { rc -> cands.firstOrNull { it.componentName == rc }?.let { return it } }
                if (cands.size == 1) return cands[0]
                val ok = cands.filter { a -> slot.avoidLabels.none { AppCategorizer.normalize(a.label).contains(it) } }
                    .ifEmpty { cands }
                return ok.firstOrNull { a -> slot.labels.any { AppCategorizer.normalize(a.label).contains(it) } } ?: ok[0]
            }
            for (intent in slot.intents) {
                val ri = runCatching {
                    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                }.getOrNull() ?: continue
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == "android") continue // seçici (varsayılan yok)
                choose(free.filter { it.packageName == pkg }, ComponentName(pkg, ri.activityInfo.name))?.let { return it }
            }
            for (pkg in slot.packages) choose(free.filter { it.packageName == pkg })?.let { return it }
            for (l in slot.labels) {
                free.firstOrNull { AppCategorizer.normalize(it.label) == l }?.let { return it }
            }
            return null
        }

        val dockKeys = dock.mapNotNull { pick(it)?.also { a -> used += a.key }?.key }
        val first = page1.mapNotNull { pick(it)?.also { a -> used += a.key }?.key }

        val rest = apps.filter { it.key !in used }
        val byCat = rest.groupBy { it.category }
        val folders = ArrayList<HomeItem>()
        val loose = ArrayList<String>()
        for (c in AppCategory.entries) {
            val list = byCat[c].orEmpty()
            when {
                list.size >= 2 && c != AppCategory.Other ->
                    folders += FolderItem(UUID.randomUUID().toString(), c.title, list.map { it.key })
                else -> loose += list.map { it.key }
            }
        }
        // Tek uygulamalı kategoriler ve "Diğer" tek klasörde toplanır.
        val tail: List<HomeItem> = when (loose.size) {
            0 -> emptyList()
            1 -> listOf(AppItem(loose[0]))
            else -> listOf(FolderItem(UUID.randomUUID().toString(), AppCategory.Other.title, loose))
        }

        val items = ArrayList<HomeItem>()
        first.forEach { items += AppItem(it) }
        if (items.isNotEmpty() && (folders.isNotEmpty() || tail.isNotEmpty())) {
            items += PageBreak(UUID.randomUUID().toString())
        }
        items += folders
        items += tail
        return HomeLayout(items = items, dock = dockKeys)
    }
}
