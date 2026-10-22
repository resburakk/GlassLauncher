package dev.glass.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface HomeItem { val id: String }
data class AppItem(val key: String) : HomeItem { override val id: String get() = key }
data class FolderItem(override val id: String, val name: String, val keys: List<String>) : HomeItem

/**
 * Görünmez sayfa sonu: kendisinden sonraki öğeler yeni sayfadan başlar (iOS'ta 1. sayfanın altı boş
 * kalırken klasörlerin 2. sayfada durması gibi). Ekranda çizilmez, sürüklenemez.
 */
data class PageBreak(override val id: String) : HomeItem

/** Widget türleri: sistem (AppWidgetHost) veya launcher'ın kendi widget'ları. */
enum class WidgetKind(val code: String, val title: String) {
    System("sys", "Widget"),
    DigitalClock("clock", "Saat"),
    AnalogClock("analog", "Analog Saat"),
    Calendar("calendar", "Takvim"),
    Battery("battery", "Pil"),
    Photo("photo", "Fotoğraf");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: System
    }
}

/**
 * Ana ekranda birden çok hücre kaplayan widget. [spanX]×[spanY] hücre boyutudur
 * (iOS: küçük 2×2, orta 4×2, büyük 4×4). [appWidgetId] yalnızca sistem widget'larında anlamlıdır.
 * [data] türe özgü ek bilgi (ör. fotoğraf dosyası adı).
 */
data class WidgetItem(
    override val id: String,
    val kind: WidgetKind,
    val spanX: Int,
    val spanY: Int,
    val appWidgetId: Int = -1,
    val data: String = "",
    /** Widget yığını (iOS Akıllı Yığın): aynı yerde, dikey kaydırarak geçilen diğer widget'lar. */
    val stack: List<WidgetItem> = emptyList(),
) : HomeItem

/**
 * Ana ekran yerleşimi. [items] düz bir listedir; sayfalara ekranda sütun×satır sayısına göre bölünür.
 * [dock] en çok 4 uygulama anahtarı içerir. [libraryOnly] ana ekrandan kaldırılmış, yalnızca
 * Uygulama Arşivi'nde duran uygulamalardır. Uygulama anahtarı = [AppInfo.key].
 * Tüm işlemler yeni bir nesne döndürür (değişmez veri).
 */
data class HomeLayout(
    val items: List<HomeItem> = emptyList(),
    val dock: List<String> = emptyList(),
    val libraryOnly: List<String> = emptyList(),
    /** Widget sayfası (iOS "Bugün" görünümü, ilk sayfanın solunda): yukarıdan aşağı sıralı widget'lar. */
    val today: List<WidgetItem> = emptyList(),
) {

    /** Ana ekranda (ızgara, klasör veya dock) görünen tüm uygulama anahtarları. */
    val homeKeys: Set<String>
        get() = buildSet {
            addAll(dock)
            items.forEach { item ->
                when (item) {
                    is AppItem -> add(item.key)
                    is FolderItem -> addAll(item.keys)
                    is WidgetItem, is PageBreak -> Unit
                }
            }
        }

    private fun normalized(): HomeLayout {
        val out = ArrayList<HomeItem>()
        for (item in items) {
            when (item) {
                is AppItem -> out += item
                is FolderItem -> when (item.keys.size) {
                    0 -> Unit                         // boş klasör otomatik kaldırılır
                    1 -> out += AppItem(item.keys[0]) // tek uygulamalı klasör çözülür
                    else -> out += item
                }
                is WidgetItem -> out += item
                is PageBreak -> if (out.lastOrNull() !is PageBreak) out += item // art arda sayfa sonu tek olur
            }
        }
        return copy(items = out)
    }

    private fun withoutKey(key: String): HomeLayout = copy(
        items = items.mapNotNull { item ->
            when (item) {
                is AppItem -> if (item.key == key) null else item
                is FolderItem -> item.copy(keys = item.keys - key)
                is WidgetItem, is PageBreak -> item
            }
        },
        dock = dock - key,
        libraryOnly = libraryOnly - key,
    ).normalized()

    /** Anahtarı düzenin her yerinden (ızgara, klasör, dock, arşiv) siler. */
    fun dropKey(key: String): HomeLayout = withoutKey(key)

    /** Ana ekrandaki bir öğeyi (uygulama veya klasör) [toIndex] konumuna taşır. */
    fun moveItem(id: String, toIndex: Int): HomeLayout {
        val item = items.firstOrNull { it.id == id } ?: return this
        val list = items.filter { it.id != id }.toMutableList()
        list.add(toIndex.coerceIn(0, list.size), item)
        return copy(items = list)
    }

    /** Dock'tan, bir klasörden veya Uygulama Arşivi'nden uygulamayı ana ekrana [toIndex] konumuna taşır. */
    fun moveAppToGrid(key: String, toIndex: Int): HomeLayout {
        val base = withoutKey(key)
        val list = base.items.toMutableList()
        list.add(toIndex.coerceIn(0, list.size), AppItem(key))
        return base.copy(items = list)
    }

    /** Uygulamayı dock'a koyar veya dock içinde yerini değiştirir. Dock doluysa yerindeki ana ekrana gider. */
    fun moveToDock(key: String, index: Int): HomeLayout {
        if (key in dock) {
            val list = (dock - key).toMutableList()
            list.add(index.coerceIn(0, list.size), key)
            return copy(dock = list)
        }
        val base = withoutKey(key)
        val d = base.dock.toMutableList()
        var gridItems = base.items
        if (d.size >= 4) {
            val kicked = d.removeAt(index.coerceIn(0, d.size - 1))
            gridItems = gridItems + AppItem(kicked)
        }
        d.add(index.coerceIn(0, d.size), key)
        return base.copy(items = gridItems, dock = d)
    }

    /** [draggedKey] uygulamasını [targetId] öğesinin üzerine bırakır: yeni klasör oluşur veya klasöre eklenir. */
    fun mergeInto(targetId: String, draggedKey: String, newFolderName: String = "Klasör"): HomeLayout {
        if (targetId == draggedKey || items.none { it.id == targetId && (it is AppItem || it is FolderItem) }) return this
        // Uygulama zaten bu klasördeyse (klasörden çıkarıp kendi klasörüne bırakma) değişiklik yok.
        if (items.any { it is FolderItem && it.id == targetId && draggedKey in it.keys }) return this
        val base = withoutKey(draggedKey)
        val newItems = base.items.map { item ->
            if (item.id != targetId) item
            else when (item) {
                is AppItem -> FolderItem(UUID.randomUUID().toString(), newFolderName, listOf(item.key, draggedKey))
                is FolderItem -> item.copy(keys = item.keys + draggedKey)
                is WidgetItem, is PageBreak -> item
            }
        }
        return base.copy(items = newItems)
    }

    fun reorderInFolder(folderId: String, key: String, toIndex: Int): HomeLayout = copy(
        items = items.map { item ->
            if (item is FolderItem && item.id == folderId && key in item.keys) {
                val list = (item.keys - key).toMutableList()
                list.add(toIndex.coerceIn(0, list.size), key)
                item.copy(keys = list)
            } else item
        }
    )

    fun renameFolder(id: String, name: String): HomeLayout = copy(
        items = items.map { if (it is FolderItem && it.id == id) it.copy(name = name) else it }
    )

    /** Uygulamayı ana ekrandan kaldırır; uygulama Uygulama Arşivi'nde kalır (silinmez). */
    fun removeFromHome(key: String): HomeLayout {
        val base = withoutKey(key)
        return base.copy(libraryOnly = base.libraryOnly + key)
    }

    /** Klasörü dağıtır: içindeki uygulamalar klasörün yerine sırayla ana ekrana yerleşir. */
    fun dissolveFolder(id: String): HomeLayout = copy(
        items = items.flatMap { item ->
            if (item is FolderItem && item.id == id) item.keys.map { AppItem(it) } else listOf(item)
        }
    )

    /**
     * Bir sayfayı siler: o sayfadaki öğeler ana ekrandan kalkar. Uygulamalar (klasördekiler dahil)
     * silinmez, Uygulama Arşivi'nde kalır; widget'lar ve sayfa sonları kaldırılır.
     */
    fun removeItems(ids: Set<String>): HomeLayout {
        val lib = ArrayList(libraryOnly)
        val kept = items.filter { item ->
            if (item.id !in ids) return@filter true
            when (item) {
                is AppItem -> lib += item.key
                is FolderItem -> lib += item.keys
                is WidgetItem, is PageBreak -> Unit
            }
            false
        }
        return copy(items = kept, libraryOnly = lib.distinct()).normalized()
    }

    // --- Çoklu seçim (toplu işlemler). [ids]: seçilen uygulama anahtarları; ana ekranda veya bir klasörün
    // içinde olabilirler (klasör kimliği verilirse içindekilerin hepsi sayılır). ---

    /** Seçili uygulama anahtarları, ana ekrandaki sırayla (klasör içindekiler klasörün yerinde). */
    fun appKeysOf(ids: Collection<String>): List<String> {
        val set = ids.toSet()
        return items.flatMap { item ->
            when (item) {
                is AppItem -> if (item.key in set) listOf(item.key) else emptyList()
                is FolderItem -> if (item.id in set) item.keys else item.keys.filter { it in set }
                is WidgetItem, is PageBreak -> emptyList()
            }
        }.distinct()
    }

    /** Anahtarları ana ekrandan ve klasörlerden çıkarır (tek uygulama kalan klasör çözülür). */
    private fun extract(keys: Set<String>): HomeLayout = copy(
        items = items.mapNotNull { item ->
            when (item) {
                is AppItem -> if (item.key in keys) null else item
                is FolderItem -> item.copy(keys = item.keys - keys)
                is WidgetItem, is PageBreak -> item
            }
        },
    ).normalized()

    /**
     * Seçili uygulamaları sıralarını koruyarak birlikte [toIndex] konumuna taşır (klasördekiler klasörden
     * çıkar). [toIndex], seçilenler çıkarılmış listeye göredir.
     */
    fun moveGroup(ids: Collection<String>, toIndex: Int): HomeLayout {
        val keys = appKeysOf(ids)
        if (keys.isEmpty()) return this
        val base = extract(keys.toSet())
        val list = base.items.toMutableList()
        list.addAll(toIndex.coerceIn(0, list.size), keys.map { AppItem(it) })
        return base.copy(items = list, dock = base.dock - keys.toSet(), libraryOnly = base.libraryOnly - keys.toSet())
    }

    /**
     * [id] öğesini (ana ekrandaki öğe ya da dock/klasör/arşivdeki uygulama) en sona, YENİ bir sayfaya taşır
     * (sürüklerken sondaki boş sayfaya bırakıldığında). Son sayfa doluysa sayfa sonu etkisizdir.
     */
    fun moveToNewPage(id: String): HomeLayout {
        val top = items.firstOrNull { it.id == id }
        val base = if (top != null) copy(items = items.filter { it.id != id }) else withoutKey(id)
        val item = top ?: AppItem(id)
        return base.copy(items = base.items + PageBreak(UUID.randomUUID().toString()) + item).normalized()
    }

    /** Seçilen uygulamaları birlikte en sona, YENİ bir sayfaya taşır. */
    fun moveGroupToNewPage(ids: Collection<String>): HomeLayout {
        val keys = appKeysOf(ids)
        if (keys.isEmpty()) return this
        val base = extract(keys.toSet())
        return base.copy(
            items = base.items + PageBreak(UUID.randomUUID().toString()) + keys.map { AppItem(it) },
            dock = base.dock - keys.toSet(),
            libraryOnly = base.libraryOnly - keys.toSet(),
        ).normalized()
    }

    /**
     * Seçili uygulamalardan yeni klasör oluşturur. Klasör [anchorId] öğesinin yerine (yoksa seçilenlerin
     * ilkinin bulunduğu yere) konur; klasörlerden alınanlar o klasörlerden çıkar.
     */
    fun createFolderFrom(ids: Collection<String>, name: String, anchorId: String? = null): HomeLayout {
        val keys = appKeysOf(ids)
        if (keys.isEmpty()) return this
        val set = keys.toSet()
        val anchor = anchorId?.takeIf { a -> items.any { it.id == a } }
            ?: items.firstOrNull { item ->
                (item is AppItem && item.key in set) || (item is FolderItem && item.keys.any { it in set })
            }?.id
        val folder = FolderItem(UUID.randomUUID().toString(), name, keys)
        val out = ArrayList<HomeItem>()
        var placed = false
        for (item in items) {
            if (item.id == anchor) {
                out += folder; placed = true
                // Kaynak bir klasörse kalanlarıyla yerinde durur (yeni klasör önüne gelir).
                if (item is FolderItem) out += item.copy(keys = item.keys - set)
                continue
            }
            when (item) {
                is AppItem -> if (item.key !in set) out += item
                is FolderItem -> out += item.copy(keys = item.keys - set)
                is WidgetItem, is PageBreak -> out += item
            }
        }
        if (!placed) out += folder
        return copy(items = out, dock = dock - set, libraryOnly = libraryOnly - set).normalized()
    }

    /** Seçili uygulamaları mevcut [folderId] klasörünün sonuna taşır (başka klasörlerden de çıkarak). */
    fun moveIntoFolder(folderId: String, ids: Collection<String>): HomeLayout {
        val target = items.firstOrNull { it.id == folderId } as? FolderItem ?: return this
        val keys = appKeysOf(ids - folderId).filter { it !in target.keys }
        if (keys.isEmpty()) return this
        val set = keys.toSet()
        val out = items.mapNotNull { item ->
            when {
                item.id == folderId -> target.copy(keys = target.keys + keys)
                item is AppItem -> if (item.key in set) null else item
                item is FolderItem -> item.copy(keys = item.keys - set)
                else -> item
            }
        }
        return copy(items = out, dock = dock - set, libraryOnly = libraryOnly - set).normalized()
    }

    /** Seçili uygulamaları ana ekrandan (ve klasörlerden) kaldırır; Uygulama Arşivi'nde kalırlar. */
    fun removeAppsFromHome(ids: Collection<String>): HomeLayout {
        val keys = appKeysOf(ids)
        if (keys.isEmpty()) return this
        val base = extract(keys.toSet())
        return base.copy(libraryOnly = (base.libraryOnly + keys).distinct())
    }

    /** Uygulama Arşivi'ndeki bir uygulamayı ana ekranın sonuna ekler. */
    fun addToHome(key: String): HomeLayout {
        if (key in homeKeys) return this
        val base = withoutKey(key)
        return base.copy(items = base.items + AppItem(key))
    }

    /** Klasördeki uygulamayı klasörden çıkarıp ana ekranın sonuna koyar. */
    fun takeOutOfFolder(key: String): HomeLayout {
        val base = withoutKey(key)
        return base.copy(items = base.items + AppItem(key))
    }

    /** Widget'ı [index] konumuna ekler (iOS: geçerli sayfanın başına). */
    fun addWidget(w: WidgetItem, index: Int): HomeLayout {
        val list = items.toMutableList()
        list.add(index.coerceIn(0, list.size), w)
        return copy(items = list)
    }

    fun removeItem(id: String): HomeLayout = copy(
        items = items.filter { it.id != id }.map { if (it is WidgetItem && it.stack.any { s -> s.id == id }) it.copy(stack = it.stack.filter { s -> s.id != id }) else it },
        today = today.filter { it.id != id }.map { if (it.stack.any { s -> s.id == id }) it.copy(stack = it.stack.filter { s -> s.id != id }) else it },
    )

    /** [targetId] widget'ının yığınına [w] eklenir (yığındakiler aynı boyutta). */
    fun addToStack(targetId: String, w: WidgetItem): HomeLayout {
        fun f(t: WidgetItem) = if (t.id == targetId) t.copy(stack = t.stack + w.copy(spanX = t.spanX, spanY = t.spanY, stack = emptyList())) else t
        return copy(items = items.map { if (it is WidgetItem) f(it) else it }, today = today.map { f(it) })
    }

    /** Yığını dağıtır: yığındaki widget'lar hemen arkasına ayrı widget'lar olarak yerleşir. */
    fun unstack(targetId: String): HomeLayout = copy(
        items = items.flatMap { if (it is WidgetItem && it.id == targetId) listOf(it.copy(stack = emptyList())) + it.stack else listOf(it) },
        today = today.flatMap { if (it.id == targetId) listOf(it.copy(stack = emptyList())) + it.stack else listOf(it) },
    )

    fun resizeWidget(id: String, spanX: Int, spanY: Int): HomeLayout = copy(
        items = items.map { if (it is WidgetItem && it.id == id) it.copy(spanX = spanX, spanY = spanY, stack = it.stack.map { s -> s.copy(spanX = spanX, spanY = spanY) }) else it },
        today = today.map { if (it.id == id) it.copy(spanX = spanX, spanY = spanY, stack = it.stack.map { s -> s.copy(spanX = spanX, spanY = spanY) }) else it },
    )

    fun setWidgetData(id: String, data: String): HomeLayout = copy(
        items = items.map { if (it is WidgetItem && it.id == id) it.copy(data = data) else it },
        today = today.map { if (it.id == id) it.copy(data = data) else it },
    )

    /** Widget sayfasının sonuna ekler. */
    fun addTodayWidget(w: WidgetItem): HomeLayout = copy(today = today + w)

    /** Widget sayfasında bir widget'ı [toIndex] sırasına taşır. */
    fun moveTodayWidget(id: String, toIndex: Int): HomeLayout {
        val w = today.firstOrNull { it.id == id } ?: return this
        val rest = today.filter { it.id != id }.toMutableList()
        rest.add(toIndex.coerceIn(0, rest.size), w)
        return copy(today = rest)
    }

    /** Ana ekrandaki widget'lar. */
    val homeWidgets: List<WidgetItem> get() = items.filterIsInstance<WidgetItem>()

    /** Tüm widget'lar (ana ekran + widget sayfası). */
    val widgets: List<WidgetItem> get() = (homeWidgets + today).flatMap { listOf(it) + it.stack }

    fun toJson(): String = toJsonObject().toString()

    fun toJsonObject(): JSONObject {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                when (item) {
                    is AppItem -> JSONObject().put("t", "a").put("k", item.key)
                    is FolderItem -> JSONObject().put("t", "f").put("id", item.id)
                        .put("n", item.name).put("k", JSONArray(item.keys))
                    is WidgetItem -> widgetJson(item)
                    is PageBreak -> JSONObject().put("t", "b").put("id", item.id)
                }
            )
        }
        return JSONObject()
            .put("v", 3)
            .put("items", arr)
            .put("dock", JSONArray(dock))
            .put("lib", JSONArray(libraryOnly))
            .put("today", JSONArray().also { a -> today.forEach { a.put(widgetJson(it)) } })
    }

    private fun widgetJson(item: WidgetItem): JSONObject = JSONObject().put("t", "w").put("id", item.id)
        .put("k", item.kind.code).put("sx", item.spanX).put("sy", item.spanY)
        .put("aw", item.appWidgetId).put("d", item.data)
        .also { o -> if (item.stack.isNotEmpty()) o.put("st", JSONArray().also { a -> item.stack.forEach { a.put(widgetJson(it)) } }) }

    companion object {
        fun fromJson(json: String): HomeLayout? = try {
            fromJsonObject(JSONObject(json))
        } catch (e: Exception) {
            null
        }

        fun fromJsonObject(o: JSONObject): HomeLayout? = try {
            val ia = o.getJSONArray("items")
            val items = ArrayList<HomeItem>()
            for (i in 0 until ia.length()) {
                val j = ia.getJSONObject(i)
                val t = j.getString("t")
                if (t == "a") {
                    items += AppItem(j.getString("k"))
                } else if (t == "b") {
                    items += PageBreak(j.optString("id").ifEmpty { UUID.randomUUID().toString() })
                } else if (t == "w") {
                    items += widgetFrom(j)
                } else {
                    val ka = j.getJSONArray("k")
                    items += FolderItem(
                        j.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        j.optString("n", "Klasör"),
                        List(ka.length()) { ka.getString(it) },
                    )
                }
            }
            val da = o.optJSONArray("dock") ?: JSONArray()
            val la = o.optJSONArray("lib") ?: JSONArray()
            val ta = o.optJSONArray("today") ?: JSONArray()
            HomeLayout(
                items, List(da.length()) { da.getString(it) }, List(la.length()) { la.getString(it) },
                List(ta.length()) { widgetFrom(ta.getJSONObject(it)) },
            )
        } catch (e: Exception) {
            null
        }

        private fun widgetFrom(j: JSONObject): WidgetItem = WidgetItem(
            id = j.optString("id").ifEmpty { UUID.randomUUID().toString() },
            kind = WidgetKind.of(j.optString("k")),
            spanX = j.optInt("sx", 2).coerceIn(1, 6),
            spanY = j.optInt("sy", 2).coerceIn(1, 6),
            appWidgetId = j.optInt("aw", -1),
            data = j.optString("d"),
            stack = j.optJSONArray("st")?.let { a -> List(a.length()) { widgetFrom(a.getJSONObject(it)) } } ?: emptyList(),
        )

        /**
         * Kayıtlı yerleşimi yüklü uygulamalarla uzlaştırır: kaldırılanlar silinir, yeni uygulamalar
         * [addNewToHome] açıksa ana ekranın sonuna, kapalıysa yalnızca Uygulama Arşivi'ne eklenir.
         * Kayıt yoksa alfabetik sıra ve varsayılan dock ile başlar.
         */
        fun reconcile(
            base: HomeLayout?,
            apps: List<AppInfo>,
            defaultDockPkgs: List<String>,
            addNewToHome: Boolean = true,
            /** Listede olmasa da korunacak anahtarlar (kısayol listesi henüz okunamadıysa kayıtlı kısayollar). */
            keepKeys: Set<String> = emptySet(),
        ): HomeLayout = reconcileKeys(
            base,
            apps.map { it.key } + keepKeys,
            defaultDockPkgs.mapNotNull { pkg -> apps.firstOrNull { it.componentName.packageName == pkg && !it.isShortcut }?.key },
            addNewToHome,
        )

        /** [reconcile]'in Android'den bağımsız çekirdeği (birim testlerde kullanılır). */
        fun reconcileKeys(
            base: HomeLayout?,
            appKeys: List<String>,
            defaultDockKeys: List<String>,
            addNewToHome: Boolean = true,
        ): HomeLayout {
            val valid = appKeys.toSet()
            val src = base ?: HomeLayout(dock = defaultDockKeys.distinct().take(4))
            val seen = HashSet<String>()
            val dock = src.dock.filter { it in valid && seen.add(it) }.take(4)
            val items = ArrayList<HomeItem>()
            for (item in src.items) {
                when (item) {
                    is AppItem -> if (item.key in valid && seen.add(item.key)) items += item
                    is FolderItem -> {
                        val ks = item.keys.filter { it in valid && seen.add(it) }
                        if (ks.isNotEmpty()) items += item.copy(keys = ks)
                    }
                    is WidgetItem, is PageBreak -> items += item
                }
            }
            val lib = ArrayList<String>()
            // Kısayollar arşivde durmaz (yalnızca ana ekranda): arşive düşen kısayol düzenden çıkar.
            for (k in src.libraryOnly) if (k in valid && seen.add(k) && !k.startsWith("sc:")) lib += k
            // İlk kurulumda (kayıt yok) tüm uygulamalar ana ekrana gelir; sonra ayara göre.
            val toHome = base == null || addNewToHome
            for (k in appKeys) if (seen.add(k)) {
                // Yeni sabitlenen kısayol her zaman ana ekrana gelir (kullanıcı "Ana ekrana ekle" dedi).
                if (toHome || k.startsWith("sc:")) items += AppItem(k) else lib += k
            }
            return HomeLayout(items, dock, lib, src.today).normalized()
        }
    }
}
