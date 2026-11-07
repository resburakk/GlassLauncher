package dev.glass.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private fun apps(vararg k: String) = k.map { AppItem(it) }

    @Test fun reconcile_firstRun_putsAllAppsOnHome_andDefaultDock() {
        val l = HomeLayout.reconcileKeys(null, listOf("a", "b", "c", "d"), listOf("d"))
        assertEquals(listOf("d"), l.dock)
        assertEquals(apps("a", "b", "c"), l.items)
        assertTrue(l.libraryOnly.isEmpty())
    }

    @Test fun reconcile_removesUninstalled_andAddsNew() {
        val base = HomeLayout(items = apps("a", "b"), dock = listOf("x"))
        val l = HomeLayout.reconcileKeys(base, listOf("a", "c", "x"), emptyList())
        assertEquals(apps("a", "c"), l.items)
        assertEquals(listOf("x"), l.dock)
    }

    @Test fun reconcile_newAppsGoToLibrary_whenSettingOff() {
        val base = HomeLayout(items = apps("a"))
        val l = HomeLayout.reconcileKeys(base, listOf("a", "n"), emptyList(), addNewToHome = false)
        assertEquals(apps("a"), l.items)
        assertEquals(listOf("n"), l.libraryOnly)
    }

    @Test fun reconcile_keepsWidgets_andFolders() {
        val w = WidgetItem("w1", WidgetKind.DigitalClock, 2, 2)
        val f = FolderItem("f1", "K", listOf("a", "b"))
        val base = HomeLayout(items = listOf(w, f, AppItem("c")))
        val l = HomeLayout.reconcileKeys(base, listOf("a", "b", "c"), emptyList())
        assertEquals(listOf(w, f, AppItem("c")), l.items)
    }

    @Test fun reconcile_folderWithOneSurvivor_isDissolved() {
        val base = HomeLayout(items = listOf(FolderItem("f", "K", listOf("a", "gone"))))
        val l = HomeLayout.reconcileKeys(base, listOf("a"), emptyList())
        assertEquals(apps("a"), l.items)
    }

    @Test fun mergeInto_createsFolder_andRemovesDragged() {
        val l = HomeLayout(items = apps("a", "b", "c")).mergeInto("a", "c")
        assertEquals(2, l.items.size)
        val f = l.items[0] as FolderItem
        assertEquals(listOf("a", "c"), f.keys)
        assertEquals(AppItem("b"), l.items[1])
    }

    @Test fun mergeInto_folder_appends() {
        val l = HomeLayout(items = listOf(FolderItem("f", "K", listOf("a", "b")), AppItem("c"))).mergeInto("f", "c")
        assertEquals(listOf(FolderItem("f", "K", listOf("a", "b", "c"))), l.items)
    }

    @Test fun mergeInto_ownFolder_isNoOp() {
        val base = HomeLayout(items = listOf(FolderItem("f", "K", listOf("a", "b"))))
        assertEquals(base, base.mergeInto("f", "a"))
    }

    @Test fun mergeInto_widget_isNoOp() {
        val base = HomeLayout(items = listOf(WidgetItem("w", WidgetKind.Battery, 2, 2), AppItem("a")))
        assertEquals(base, base.mergeInto("w", "a"))
    }

    @Test fun moveToDock_full_kicksSlotToGrid() {
        val base = HomeLayout(items = apps("e"), dock = listOf("a", "b", "c", "d"))
        val l = base.moveToDock("e", 1)
        assertEquals(listOf("a", "e", "c", "d"), l.dock)
        assertEquals(apps("b"), l.items)
    }

    @Test fun moveToDock_reorderWithinDock() {
        val l = HomeLayout(dock = listOf("a", "b", "c")).moveToDock("a", 2)
        assertEquals(listOf("b", "c", "a"), l.dock)
    }

    @Test fun moveAppToGrid_fromFolder_dissolvesTwoAppFolder() {
        val base = HomeLayout(items = listOf(FolderItem("f", "K", listOf("a", "b")), AppItem("c")))
        val l = base.moveAppToGrid("a", 0)
        assertEquals(apps("a", "b", "c"), l.items)
    }

    @Test fun removeFromHome_andAddBack() {
        val base = HomeLayout(items = apps("a", "b"))
        val removed = base.removeFromHome("a")
        assertEquals(apps("b"), removed.items)
        assertEquals(listOf("a"), removed.libraryOnly)
        val back = removed.addToHome("a")
        assertEquals(apps("b", "a"), back.items)
        assertTrue(back.libraryOnly.isEmpty())
    }

    @Test fun json_roundTrip_keepsEverything() {
        val base = HomeLayout(
            items = listOf(
                AppItem("a"),
                FolderItem("f", "Oyunlar", listOf("b", "c")),
                WidgetItem("w", WidgetKind.System, 4, 2, appWidgetId = 17, data = ""),
                WidgetItem("p", WidgetKind.Photo, 2, 2, data = "photo_1.img"),
            ),
            dock = listOf("d"),
            libraryOnly = listOf("z"),
        )
        val back = HomeLayout.fromJson(base.toJson())
        assertNotNull(back)
        assertEquals(base, back)
    }

    @Test fun fromJson_invalid_returnsNull() {
        assertEquals(null, HomeLayout.fromJson("{bozuk"))
    }

    @Test fun homeKeys_ignoresWidgets() {
        val l = HomeLayout(items = listOf(WidgetItem("w", WidgetKind.Calendar, 2, 2), AppItem("a")), dock = listOf("d"))
        assertEquals(setOf("a", "d"), l.homeKeys)
        assertFalse("w" in l.homeKeys)
    }

    @Test fun multiSelect_groupMove_folderCreate_moveInto() {
        val base = HomeLayout(
            items = listOf(AppItem("a"), AppItem("b"), FolderItem("f", "F", listOf("x", "y")), AppItem("c"), AppItem("d")),
        )
        // b ve d birlikte en başa
        val moved = base.moveGroup(listOf("d", "b"), 0)
        assertEquals(listOf("b", "d", "a", "f", "c"), moved.items.map { it.id })
        // a + klasör f → yeni klasör a'nın yerinde, f'nin içindekiler de girer
        val made = base.createFolderFrom(listOf("a", "f"), "Yeni")
        val nf = made.items[0] as FolderItem
        assertEquals(listOf("a", "x", "y"), nf.keys)
        assertEquals(listOf(nf.id, "b", "c", "d"), made.items.map { it.id })
        // c ve d klasör f'ye
        val into = base.moveIntoFolder("f", listOf("c", "d"))
        assertEquals(listOf("x", "y", "c", "d"), (into.items.first { it.id == "f" } as FolderItem).keys)
        assertEquals(listOf("a", "b", "f"), into.items.map { it.id })
    }

    @Test fun multiSelect_appsInsideFolders() {
        val base = HomeLayout(
            items = listOf(AppItem("a"), FolderItem("f", "F", listOf("x", "y", "z")), FolderItem("g", "G", listOf("p", "q"))),
        )
        // klasör f'den x ile ana ekrandan a → yeni klasör a'nın yerinde; f'de y,z kalır
        val made = base.createFolderFrom(listOf("x", "a"), "Yeni")
        val nf = made.items[0] as FolderItem
        assertEquals(listOf("a", "x"), nf.keys)
        assertEquals(listOf("y", "z"), (made.items.first { it.id == "f" } as FolderItem).keys)
        // f'den y ve z → g klasörüne; f'de tek x kalır ve klasör çözülür
        val into = base.moveIntoFolder("g", listOf("y", "z"))
        assertEquals(listOf("p", "q", "y", "z"), (into.items.first { it.id == "g" } as FolderItem).keys)
        assertTrue(into.items.any { it is AppItem && it.key == "x" })
        // klasörden x ve z en başa
        val moved = base.moveGroup(listOf("z", "x"), 0)
        // f'de tek y kalınca klasör çözülür
        assertEquals(listOf("x", "z", "a", "y", "g"), moved.items.map { it.id })
        // kaldır: arşive gider
        val rem = base.removeAppsFromHome(listOf("p"))
        assertTrue("p" in rem.libraryOnly)
        assertTrue(rem.items.any { it is AppItem && it.key == "q" })
    }

    @Test fun todayWidgets_surviveEditsJsonAndReconcile() {
        val t1 = WidgetItem("t1", WidgetKind.Battery, 2, 2)
        val t2 = WidgetItem("t2", WidgetKind.DigitalClock, 4, 2)
        var l = HomeLayout(items = apps("a", "b"), dock = listOf("x")).addTodayWidget(t1).addTodayWidget(t2)
        // Ana ekran düzenlemeleri widget sayfasını silmez
        l = l.removeAppsFromHome(listOf("a")).takeOutOfFolder("b").moveToDock("b", 1)
        assertEquals(listOf("t1", "t2"), l.today.map { it.id })
        // Sıralama, boyut, kaldırma
        l = l.moveTodayWidget("t2", 0)
        assertEquals(listOf("t2", "t1"), l.today.map { it.id })
        l = l.resizeWidget("t1", 4, 4)
        assertEquals(4, l.today.first { it.id == "t1" }.spanY)
        // JSON gidiş-dönüş ve uzlaştırma
        val back = HomeLayout.fromJson(l.toJson())!!
        assertEquals(l.today, back.today)
        val rec = HomeLayout.reconcileKeys(back, listOf("a", "b", "x"), emptyList())
        assertEquals(l.today, rec.today)
        assertEquals(listOf("t2"), l.removeItem("t1").today.map { it.id })
        assertEquals(2, l.widgets.size)
    }
}
