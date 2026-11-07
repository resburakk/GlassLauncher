package dev.glass.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Yüklü bir simge paketi (Play Store'daki Nova / ADW uyumlu paketler). */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * Simge paketi desteği. Paketler `appfilter.xml` dosyasında hangi uygulamaya hangi çizimin ait olduğunu
 * listeler (ComponentInfo{paket/sınıf} → drawable adı). Paketin içermediği uygulamalar kendi simgesiyle kalır.
 */
object IconPacks {
    private val THEME_INTENTS = listOf(
        Intent("org.adw.launcher.THEMES"),
        Intent("com.novalauncher.THEME"),
        Intent("com.gau.go.launcherex.theme"),
        Intent("com.anddoes.launcher.THEME"),
        Intent("com.teslacoilsw.launcher.THEME"),
        Intent(Intent.ACTION_MAIN).addCategory("com.fede.launcher.THEME_ICONPACK"),
        Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
    )

    private class Pack(val res: Resources, val pkg: String, val map: Map<String, String>, val byPackage: Map<String, String>)

    private val loaded = HashMap<String, Pack?>()

    /** Telefonda yüklü simge paketleri (ada göre sıralı). */
    fun installed(ctx: Context): List<IconPackInfo> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, IconPackInfo>()
        for (i in THEME_INTENTS) {
            runCatching { pm.queryIntentActivities(i, PackageManager.GET_META_DATA) }.getOrDefault(emptyList()).forEach { ri ->
                val p = ri.activityInfo?.packageName ?: return@forEach
                if (p !in seen) seen[p] = IconPackInfo(p, runCatching { ri.activityInfo.applicationInfo.loadLabel(pm).toString() }.getOrDefault(p))
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    @Synchronized
    private fun pack(ctx: Context, pkg: String): Pack? = loaded.getOrPut(pkg) {
        runCatching {
            val res = ctx.packageManager.getResourcesForApplication(pkg)
            val map = HashMap<String, String>()
            val byPkg = HashMap<String, String>()
            val parser: XmlPullParser? = run {
                val id = res.getIdentifier("appfilter", "xml", pkg)
                if (id != 0) res.getXml(id) else runCatching {
                    XmlPullParserFactory.newInstance().newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "UTF-8") }
                }.getOrNull()
            }
            if (parser != null) {
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component")
                        val draw = parser.getAttributeValue(null, "drawable")
                        if (comp != null && draw != null) {
                            // Biçim: ComponentInfo{com.paket/com.paket.Sinif}
                            val inner = comp.substringAfter("{", "").substringBefore("}", "")
                            val cn = ComponentName.unflattenFromString(inner)
                            if (cn != null) {
                                map[cn.flattenToString()] = draw
                                map[cn.flattenToShortString()] = draw
                                byPkg.putIfAbsent(cn.packageName, draw)
                            }
                        }
                    }
                    ev = parser.next()
                }
            }
            Pack(res, pkg, map, byPkg)
        }.getOrNull()
    }

    /** Paketteki simge (yoksa null: uygulama kendi simgesiyle gösterilir). */
    fun icon(ctx: Context, pkg: String, component: ComponentName, density: Int): Drawable? {
        val p = pack(ctx, pkg) ?: return null
        val name = p.map[component.flattenToString()] ?: p.map[component.flattenToShortString()]
            ?: p.byPackage[component.packageName] ?: return null
        val id = p.res.getIdentifier(name, "drawable", p.pkg)
        if (id == 0) return null
        return runCatching { p.res.getDrawableForDensity(id, density, null) }.getOrNull()
    }

    /** Paket değişti / güncellendi: önbellek temizlenir. */
    @Synchronized
    fun clear() = loaded.clear()
}
