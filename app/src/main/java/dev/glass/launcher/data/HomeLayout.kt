package dev.glass.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface HomeItem { val id: String }
data class AppItem(val key: String) : HomeItem { override val id: String get() = key }
data class FolderItem(override val id: String, val name: String, val keys: List<String>) : HomeItem

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
) {

    /** Ana ekranda (ızgara, klasör veya dock) görünen tüm uygulama anahtarları. */
    val homeKeys: Set<String>
        get() = buildSet {
            addAll(dock)
            items.forEach { item ->
                when (item) {
                    is AppItem -> add(item.key)
                    is FolderItem -> addAll(item.keys)
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
            }
        }
        return copy(items = out)
    }

    private fun withoutKey(key: String): HomeLayout = HomeLayout(
        items = items.mapNotNull { item ->
            when (item) {
                is AppItem -> if (item.key == key) null else item
                is FolderItem -> item.copy(keys = item.keys - key)
            }
        },
        dock = dock - key,
        libraryOnly = libraryOnly - key,
    ).normalized()

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
        if (targetId == draggedKey || items.none { it.id == targetId }) return this
        // Uygulama zaten bu klasördeyse (klasörden çıkarıp kendi klasörüne bırakma) değişiklik yok.
        if (items.any { it is FolderItem && it.id == targetId && draggedKey in it.keys }) return this
        val base = withoutKey(draggedKey)
        val newItems = base.items.map { item ->
            if (item.id != targetId) item
            else when (item) {
                is AppItem -> FolderItem(UUID.randomUUID().toString(), newFolderName, listOf(item.key, draggedKey))
                is FolderItem -> item.copy(keys = item.keys + draggedKey)
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

    /** Uygulama Arşivi'ndeki bir uygulamayı ana ekranın sonuna ekler. */
    fun addToHome(key: String): HomeLayout {
        if (key in homeKeys) return this
        val base = withoutKey(key)
        return base.copy(items = base.items + AppItem(key))
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                when (item) {
                    is AppItem -> JSONObject().put("t", "a").put("k", item.key)
                    is FolderItem -> JSONObject().put("t", "f").put("id", item.id)
                        .put("n", item.name).put("k", JSONArray(item.keys))
                }
            )
        }
        return JSONObject()
            .put("v", 2)
            .put("items", arr)
            .put("dock", JSONArray(dock))
            .put("lib", JSONArray(libraryOnly))
            .toString()
    }

    companion object {
        fun fromJson(json: String): HomeLayout? = try {
            val o = JSONObject(json)
            val ia = o.getJSONArray("items")
            val items = ArrayList<HomeItem>()
            for (i in 0 until ia.length()) {
                val j = ia.getJSONObject(i)
                if (j.getString("t") == "a") {
                    items += AppItem(j.getString("k"))
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
            HomeLayout(items, List(da.length()) { da.getString(it) }, List(la.length()) { la.getString(it) })
        } catch (e: Exception) {
            null
        }

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
        ): HomeLayout = reconcileKeys(
            base,
            apps.map { it.key },
            defaultDockPkgs.mapNotNull { pkg -> apps.firstOrNull { it.componentName.packageName == pkg }?.key },
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
                }
            }
            val lib = ArrayList<String>()
            for (k in src.libraryOnly) if (k in valid && seen.add(k)) lib += k
            // İlk kurulumda (kayıt yok) tüm uygulamalar ana ekrana gelir; sonra ayara göre.
            val toHome = base == null || addNewToHome
            for (k in appKeys) if (seen.add(k)) {
                if (toHome) items += AppItem(k) else lib += k
            }
            return HomeLayout(items, dock, lib).normalized()
        }
    }
}
