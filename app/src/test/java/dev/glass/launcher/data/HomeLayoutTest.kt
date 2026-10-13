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
}
