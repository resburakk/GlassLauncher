package dev.glass.launcher.ui

/**
 * Durum/gezinti çubuğu yazı tonu. Ana ekranda duvar kâğıdına göre belirlenir; açık renkli bir ekran (Ayarlar)
 * açıkken [override] onu sabitler. Öne gelişte ya da duvar kâğıdı renkleri değişince yapılan yeniden hesap da
 * bu sabitlemeye uyar (yoksa ayarlarda beyaz yazılar kalıyordu).
 */
object BarTone {
    /** null: duvar kâğıdına göre; true: koyu yazı (açık arka plan); false: açık yazı. */
    @Volatile var override: Boolean? = null
        set(v) { field = v; apply?.invoke() }
    /** MainActivity'nin tonu uygulayan işlevi. */
    @Volatile var apply: (() -> Unit)? = null
}
