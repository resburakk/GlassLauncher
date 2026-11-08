package dev.glass.launcher.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * iOS 27 (Liquid Glass) görünümlü klavye: kendi çizer (Compose yok; giriş penceresinde hızlı ve hafif).
 *
 * Düzen: üstte öneri/yapıştır şeridi, 4 tuş satırı, en altta dünya (klavye değiştir) ve mikrofon.
 * Sayfalar: harfler (Türkçe Q), 123, #+=, emoji.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val host: Host, private val emojiBar: Boolean = false) : View(ctx) {

    interface Host {
        fun commit(text: String)
        fun deleteBack()
        fun action()
        fun switchKeyboard(longPress: Boolean)
        fun voice()
        fun pasteChip(): String?
        fun pasteNow()
        fun dismissPaste()
        fun returnLabel(): String?
        fun returnIsAction(): Boolean
        fun autoCaps(): Boolean
        fun haptics(): Boolean
        /** Tuş sesi/titreşimi (tür: 0 harf, 1 sil, 2 boşluk, 3 dönüş). */
        fun click(kind: Int)
        /** Tek elle klavye: 0 kapalı, 1 sol, 2 sağ (ayara yazılır). */
        fun setOneHanded(mode: Int)
        /** Emoji paneli (Android'in tam emoji seçicisi) açılır/kapanır. */
        fun showEmoji(show: Boolean)
    }

    enum class Page { Letters, Numbers, Symbols, Emoji }
    private enum class Shift { Off, On, Caps }

    private data class Key(
        val label: String,
        val code: Code = Code.Char,
        val w: Float = 1f,
        val fn: Boolean = false,
    ) { val rect = RectF() }

    private enum class Code { Char, Shift, Delete, Space, Return, Page123, PageAbc, PageSym, PageEmoji, Globe, Mic, OneHandOff, OneHandSwap }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var dark = false
        set(v) { field = v; invalidate() }

    /** Arkası gerçekten bulanık cam (pencere arkası bulanıklığı açıksa zemin yarı saydam çizilir). */
    var glass = false
        set(v) { field = v; invalidate() }

    /** Ayarlar (yükseklik, tek elle, önizleme, hassasiyet...). */
    var cfg = KbConfig()
        set(v) { field = v; build(); requestLayout(); invalidate() }

    private var page = Page.Letters
    private var shift = Shift.Off
    private var lastShiftTap = 0L
    private var rows: List<List<Key>> = emptyList()
    private var bottomKeys: List<Key> = emptyList()
    private var pressed: Key? = null
    private val tr = Locale.forLanguageTag("tr")
    private val handler = Handler(Looper.getMainLooper())
    private var repeating = false

    // Ölçüler (iOS: tuş ~42, aralık 5/10, köşe ~9). Yükseklik ayara göre (kısa/normal/uzun).
    private val hScale get() = when (cfg.height) { 0 -> 0.88f; 2 -> 1.12f; else -> 1f }
    /** Üst şerit yalnızca yapıştır düğmesi varken açılır (yoksa boş duruyordu); kapalıyken ince bir pay kalır. */
    private val barH get() = if (emojiBar) 0f else dp(40f)
    private val keyH get() = dp(40f) * hScale
    private val rowGap get() = dp(10f) * hScale
    private val gapX = dp(5f)
    private val side = dp(7f)
    private val radius = dp(9f)
    private val bottomH = dp(12f)
    var navInset = 0
        set(v) { field = v; requestLayout() }

    private val pasteRect = RectF()
    private val pasteClose = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { build() }

    fun reset(page0: Page = Page.Letters) {
        page = page0
        shift = if (host.autoCaps()) Shift.On else Shift.Off
        build(); invalidate()
    }

    /** İmleç cümle başına geldiğinde büyük harf (Caps kilidi açık değilse). */
    fun updateAutoCaps() {
        if (page != Page.Letters || shift == Shift.Caps) return
        val want = if (host.autoCaps()) Shift.On else Shift.Off
        if (want != shift) { shift = want; build(); invalidate() }
    }

    private fun letters(): List<List<Key>> {
        fun row(s: String) = s.map { Key(it.toString()) }
        return listOf(
            row("qwertyuıopğü"),
            row("asdfghjklşi"),
            listOf(Key("⇧", Code.Shift, 1.35f, true)) + row("zxcvbnmöç") + Key("⌫", Code.Delete, 1.35f, true),
            bottomRow("123", Code.Page123),
        )
    }

    // Mikrofon (dikte) alt satırda, emojinin yanında (iOS'taki alt sıra yerine; orada Android'in kendi çubuğu var).
    private fun bottomRow(sw: String, code: Code) = listOfNotNull(
        Key(sw, code, 1.25f, true),
        Key("☺", Code.PageEmoji, 1.0f, true),
        if (cfg.dictation) Key("mic", Code.Mic, 1.0f, true) else null,
        Key("boşluk", Code.Space, if (cfg.dictation) 4.2f else 5.2f),
        Key("⏎", Code.Return, 2.0f),
    )

    private fun numbers() = listOf(
        "1234567890".map { Key(it.toString()) },
        listOf("-", "/", ":", ";", "(", ")", "₺", "&", "@", "\"").map { Key(it) },
        listOf(Key("#+=", Code.PageSym, 1.35f, true)) + listOf(".", ",", "?", "!", "'").map { Key(it, w = 1.4f) } + Key("⌫", Code.Delete, 1.35f, true),
        bottomRow("ABC", Code.PageAbc),
    )

    private fun symbols() = listOf(
        listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { Key(it) },
        listOf("_", "\\", "|", "~", "<", ">", "€", "$", "£", "·").map { Key(it) },
        listOf(Key("123", Code.Page123, 1.35f, true)) + listOf(".", ",", "?", "!", "'").map { Key(it, w = 1.4f) } + Key("⌫", Code.Delete, 1.35f, true),
        bottomRow("ABC", Code.PageAbc),
    )

    private val emojis = listOf(
        "😀", "😂", "🥹", "😊", "😍", "😘", "😎", "🤔",
        "😅", "😭", "😡", "🥳", "😴", "🙄", "😇", "🤗",
        "👍", "👏", "🙏", "💪", "👌", "✌️", "🤝", "👋",
        "❤️", "🔥", "✨", "🎉", "💯", "✅", "⭐", "☕",
    )

    private fun emojiPage(): List<List<Key>> = emojis.chunked(8).map { r -> r.map { Key(it) } }.take(3) +
        listOf(listOf(Key("ABC", Code.PageAbc, 1.25f, true)) + emojis.drop(24).map { Key(it) } + Key("⌫", Code.Delete, 1.25f, true))

    private fun build() {
        if (emojiBar) {
            // Emoji panelinin alt satırı: ABC, boşluk, sil.
            rows = listOf(listOf(Key("ABC", Code.PageAbc, 1.5f, true), Key("boşluk", Code.Space, 6f), Key("⌫", Code.Delete, 1.5f, true)))
            bottomKeys = emptyList(); layoutKeys(); return
        }
        rows = when (page) {
            Page.Letters -> letters()
            Page.Numbers -> numbers()
            Page.Symbols -> symbols()
            Page.Emoji -> emojiPage()
        }
        // Tek elle: boş kalan yanda "tam klavye" ve "öbür yana" düğmeleri.
        bottomKeys = if (cfg.oneHanded != 0) listOf(Key("full", Code.OneHandOff), Key("swap", Code.OneHandSwap)) else emptyList()
        layoutKeys()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (emojiBar) (dp(6f) + keyH + rowGap + bottomH).toInt() + navInset
            else (barH + dp(4f) + (keyH + rowGap) * 4 + bottomH).toInt() + navInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutKeys() }

    fun refreshBar() { layoutKeys(); requestLayout(); invalidate() }

    private fun layoutKeys() {
        val w = width.toFloat().takeIf { it > 0 } ?: return
        val oh = if (emojiBar) 0 else cfg.oneHanded
        val area = if (oh != 0) w * 0.84f else w
        val x0 = if (oh == 2) w - area else 0f
        val inner = area - side * 2
        // Birim: en kalabalık satırdaki (12 tuş) bir harf tuşunun genişliği.
        val unit = (inner - gapX * 11) / 12f
        var y = if (emojiBar) dp(6f) else barH + dp(4f)
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.w.toDouble() }.toFloat()
            var rowW = unit * total + gapX * (row.size - 1)
            val stretch = ri == rows.lastIndex || page != Page.Letters || row.size >= 12 || row.any { it.code == Code.Shift }
            val scale = if (stretch) (inner - gapX * (row.size - 1)) / (unit * total) else 1f
            rowW = unit * total * scale + gapX * (row.size - 1)
            var x = x0 + side + (inner - rowW) / 2f
            row.forEach { k ->
                val kw = unit * k.w * scale
                k.rect.set(x, y, x + kw, y + keyH)
                x += kw + gapX
            }
            y += keyH + rowGap
        }
        if (oh != 0) {
            val sx = if (oh == 1) area else 0f
            val sw = w - area
            val top = barH + dp(4f); val total = (keyH + rowGap) * 4
            bottomKeys[0].rect.set(sx, top + total * 0.22f, sx + sw, top + total * 0.48f)
            bottomKeys[1].rect.set(sx, top + total * 0.52f, sx + sw, top + total * 0.78f)
        }
    }

    // ---------- Çizim ----------
    val colBg get() = if (dark) 0xFF1B1B1E.toInt() else 0xFFE4E6EA.toInt()
    private val colKey get() = if (dark) 0xFF5B5B60.toInt() else 0xFFFFFFFF.toInt()
    private val colFn get() = if (dark) 0xFF3A3A3E.toInt() else 0xFFB4B9C2.toInt()
    private val colTxt get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()
    private val colSub get() = if (dark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    private val colShadow get() = if (dark) 0x80000000.toInt() else 0x47000000
    private val blue = 0xFF0A84FF.toInt()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Cam panel: üst köşeler yuvarlak, üstte ince ışık çizgisi, hafif dikey ton.
        val r = dp(30f)
        val rr0 = if (emojiBar) 0f else r
        val panel = Path().apply { addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(rr0, rr0, rr0, rr0, 0f, 0f, 0f, 0f), Path.Direction.CW) }
        fill.color = 0xFF000000.toInt() // gölgelerden kalan saydamlık zemine geçmesin
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            when {
                glass && dark -> intArrayOf(0x9E2A2A2E.toInt(), 0xB01B1B1E.toInt())
                glass -> intArrayOf(0xA8F4F5F8.toInt(), 0xBDE4E6EA.toInt())
                emojiBar -> intArrayOf(colBg, colBg)
                dark -> intArrayOf(0xFF26262A.toInt(), colBg)
                else -> intArrayOf(0xFFEEEFF2.toInt(), colBg)
            }, null, Shader.TileMode.CLAMP)
        c.drawPath(panel, fill); fill.shader = null

        if (!emojiBar) drawBar(c, w)
        rows.flatten().forEach { drawKey(c, it) }
        if (!emojiBar) drawBottom(c)
        pressed?.let { if (cfg.preview && it.code == Code.Char && page != Page.Emoji) drawPreview(c, it) }
    }

    private fun drawBar(c: Canvas, w: Float) {
        val chip = host.pasteChip()
        pasteRect.setEmpty(); pasteClose.setEmpty()
        if (chip != null) {
            // iOS 27 hızlı yapıştır: [pano] Yapıştır: “…”  |  ✕   (✕ düğmenin içinde, ince ayırıcıyla ayrı)
            text.textSize = dp(14.5f)
            val cy = barH / 2f + dp(1f)
            val maxW = w - side * 2 - dp(24f)
            val iconW = dp(30f); val closeW = dp(34f)
            var body = chip.replace('\n', ' ').trim()
            fun label(b: String) = "Yapıştır: “$b”"
            while (body.length > 1 && text.measureText(label(body)) > maxW - iconW - closeW - dp(16f)) body = body.dropLast(2).trimEnd() + "…"
            val lw = text.measureText(label(body))
            val cw = iconW + lw + dp(10f) + closeW
            pasteRect.set(w / 2 - cw / 2, cy - dp(16f), w / 2 + cw / 2 - closeW, cy + dp(16f))
            pasteClose.set(pasteRect.right, pasteRect.top, pasteRect.right + closeW, pasteRect.bottom)
            fill.color = if (dark) 0x2EFFFFFF else 0xE6FFFFFF.toInt()
            c.drawRoundRect(pasteRect.left, pasteRect.top, pasteClose.right, pasteClose.bottom, dp(16f), dp(16f), fill)
            // Pano simgesi: tahta + üstte klips
            stroke.color = colTxt; stroke.strokeWidth = dp(1.5f)
            val ix = pasteRect.left + dp(17f)
            c.drawRoundRect(ix - dp(6f), cy - dp(7f), ix + dp(6f), cy + dp(8f), dp(2.5f), dp(2.5f), stroke)
            fill.color = colTxt
            c.drawRoundRect(ix - dp(3.5f), cy - dp(9f), ix + dp(3.5f), cy - dp(5f), dp(1.5f), dp(1.5f), fill)
            c.drawLine(ix - dp(3f), cy, ix + dp(3f), cy, stroke); c.drawLine(ix - dp(3f), cy + dp(3.5f), ix + dp(1.5f), cy + dp(3.5f), stroke)
            text.color = colTxt; text.textAlign = Paint.Align.LEFT
            c.drawText(label(body), pasteRect.left + iconW, cy + dp(5f), text)
            text.textAlign = Paint.Align.CENTER
            // ayırıcı + ✕
            stroke.color = if (dark) 0x33FFFFFF else 0x22000000; stroke.strokeWidth = dp(1f)
            c.drawLine(pasteClose.left, cy - dp(9f), pasteClose.left, cy + dp(9f), stroke)
            stroke.color = colSub; stroke.strokeWidth = dp(1.6f)
            val xx = pasteClose.centerX(); val sz = dp(4.5f)
            c.drawLine(xx - sz, cy - sz, xx + sz, cy + sz, stroke); c.drawLine(xx - sz, cy + sz, xx + sz, cy - sz, stroke)
        }
    }

    private fun keyColor(k: Key): Int = when {
        k.code == Code.Return && host.returnIsAction() -> blue
        k.code == Code.Shift && shift != Shift.Off -> if (dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        k.fn || k.code == Code.Return -> colFn
        else -> colKey
    }

    private fun drawKey(c: Canvas, k: Key) {
        val isPressed = pressed === k
        val rr = k.rect
        // Gölge (alt kenarda 1 dp), sonra tuş.
        fill.color = colShadow
        c.drawRoundRect(rr.left, rr.top + dp(1f), rr.right, rr.bottom + dp(1f), radius, radius, fill)
        var col = keyColor(k)
        if (isPressed && (k.fn || k.code != Code.Char || page == Page.Emoji)) col = if (k.fn || k.code == Code.Return) colKey else colFn
        fill.color = col
        c.drawRoundRect(rr, radius, radius, fill)

        val cx = rr.centerX(); val cy = rr.centerY()
        val onBlue = k.code == Code.Return && host.returnIsAction()
        text.color = if (onBlue) 0xFFFFFFFF.toInt() else if (k.code == Code.Shift && shift != Shift.Off) 0xFF111111.toInt() else colTxt
        when (k.code) {
            Code.Shift -> drawShift(c, cx, cy, text.color, shift == Shift.Caps)
            Code.Delete -> drawDelete(c, cx, cy, text.color)
            Code.PageEmoji -> drawSmile(c, cx, cy, text.color)
            Code.Mic -> drawMic(c, cx, cy, text.color)
            Code.Return -> {
                val lbl = host.returnLabel()
                if (lbl == null) drawReturn(c, cx, cy, text.color)
                else { text.textSize = dp(16f); c.drawText(lbl, cx, cy + dp(5.5f), text) }
            }
            Code.Space -> { text.textSize = dp(16f); c.drawText("boşluk", cx, cy + dp(5.5f), text) }
            Code.Page123, Code.PageAbc, Code.PageSym -> { text.textSize = dp(16f); c.drawText(k.label, cx, cy + dp(5.5f), text) }
            else -> {
                val s = display(k.label)
                text.textSize = if (page == Page.Emoji) dp(24f) else if (s.length > 1) dp(16f) else dp(22f)
                c.drawText(s, cx, cy + text.textSize * 0.36f, text)
            }
        }
    }

    private fun display(l: String) = if (page == Page.Letters && shift != Shift.Off) l.uppercase(tr) else l

    private fun drawPreview(c: Canvas, k: Key) {
        // iOS balonu: tuşun üstüne taşan, biraz daha geniş kapsül; büyük harf.
        val rr = k.rect
        val bw = rr.width() + dp(18f)
        // Üst sıranın balonu klavyenin üstüne taşabilir (üst pencere kırpmıyor).
        val top = maxOf(dp(1f), rr.top - dp(48f))
        val bub = RectF(rr.centerX() - bw / 2, top, rr.centerX() + bw / 2, rr.top + dp(6f))
        fill.color = 0x40000000
        c.drawRoundRect(bub.left, bub.top + dp(2f), bub.right, bub.bottom + dp(2f), dp(12f), dp(12f), fill)
        fill.color = colKey
        c.drawRoundRect(bub, dp(12f), dp(12f), fill)
        c.drawRoundRect(rr.left, rr.top - dp(2f), rr.right, rr.bottom, radius, radius, fill)
        text.color = colTxt; text.textSize = dp(30f)
        c.drawText(display(k.label), bub.centerX(), bub.top + dp(32f), text)
    }

    private fun drawBottom(c: Canvas) {
        if (bottomKeys.size < 2) return
        // Tek elle: tam klavye (genişleyen oklar) ve öbür yana (yan ok) düğmeleri.
        stroke.color = colSub; stroke.strokeWidth = dp(1.8f)
        val a = bottomKeys[0].rect; val ax = a.centerX(); val ay = a.centerY()
        c.drawRoundRect(ax - dp(10f), ay - dp(7f), ax + dp(10f), ay + dp(7f), dp(2f), dp(2f), stroke)
        c.drawLine(ax - dp(6f), ay, ax + dp(6f), ay, stroke)
        val b = bottomKeys[1].rect; val bx = b.centerX(); val by = b.centerY()
        val dir = if (cfg.oneHanded == 1) 1f else -1f
        c.drawLine(bx - dp(6f) * dir, by - dp(9f), bx + dp(4f) * dir, by, stroke)
        c.drawLine(bx + dp(4f) * dir, by, bx - dp(6f) * dir, by + dp(9f), stroke)
    }

    private fun drawMic(c: Canvas, mx: Float, cy: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = dp(1.6f); fill.color = col
        val my = cy + dp(1f)
        c.drawRoundRect(mx - dp(4f), my - dp(11f), mx + dp(4f), my + dp(2f), dp(4f), dp(4f), fill)
        c.drawArc(RectF(mx - dp(8f), my - dp(8f), mx + dp(8f), my + dp(5f)), 20f, 140f, false, stroke)
        c.drawLine(mx, my + dp(5f), mx, my + dp(9f), stroke)
    }

    private fun drawShift(c: Canvas, cx: Float, cy: Float, col: Int, caps: Boolean) {
        val p = Path().apply {
            moveTo(cx, cy - dp(9f)); lineTo(cx + dp(9f), cy + dp(1f)); lineTo(cx + dp(4.5f), cy + dp(1f))
            lineTo(cx + dp(4.5f), cy + dp(7f)); lineTo(cx - dp(4.5f), cy + dp(7f)); lineTo(cx - dp(4.5f), cy + dp(1f))
            lineTo(cx - dp(9f), cy + dp(1f)); close()
        }
        if (shift != Shift.Off) { fill.color = col; c.drawPath(p, fill) } else { stroke.color = col; stroke.strokeWidth = dp(1.6f); c.drawPath(p, stroke) }
        if (caps) { fill.color = col; c.drawRect(cx - dp(4.5f), cy + dp(9f), cx + dp(4.5f), cy + dp(10.6f), fill) }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = dp(1.6f)
        val p = Path().apply {
            moveTo(cx - dp(11f), cy); lineTo(cx - dp(5f), cy - dp(7f)); lineTo(cx + dp(10f), cy - dp(7f))
            lineTo(cx + dp(10f), cy + dp(7f)); lineTo(cx - dp(5f), cy + dp(7f)); close()
        }
        c.drawPath(p, stroke)
        c.drawLine(cx - dp(1f), cy - dp(3f), cx + dp(5f), cy + dp(3f), stroke)
        c.drawLine(cx - dp(1f), cy + dp(3f), cx + dp(5f), cy - dp(3f), stroke)
    }

    private fun drawSmile(c: Canvas, cx: Float, cy: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = dp(1.6f)
        c.drawCircle(cx, cy, dp(10f), stroke)
        fill.color = col
        c.drawCircle(cx - dp(3.5f), cy - dp(2.5f), dp(1.4f), fill); c.drawCircle(cx + dp(3.5f), cy - dp(2.5f), dp(1.4f), fill)
        c.drawArc(RectF(cx - dp(5.5f), cy - dp(3f), cx + dp(5.5f), cy + dp(5.5f)), 20f, 140f, false, stroke)
    }

    private fun drawReturn(c: Canvas, cx: Float, cy: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = dp(1.7f)
        val p = Path().apply { moveTo(cx + dp(8f), cy - dp(7f)); lineTo(cx + dp(8f), cy + dp(2f)); lineTo(cx - dp(8f), cy + dp(2f)) }
        c.drawPath(p, stroke)
        c.drawLine(cx - dp(8f), cy + dp(2f), cx - dp(4f), cy - dp(2f), stroke)
        c.drawLine(cx - dp(8f), cy + dp(2f), cx - dp(4f), cy + dp(6f), stroke)
    }

    // ---------- Dokunma ----------
    private fun keyAt(x: Float, y: Float): Key? {
        // Tuşlar arasındaki boşluğa dokunulursa en yakın tuş (iOS'ta da öyle).
        var best: Key? = null; var bd = Float.MAX_VALUE
        (rows.flatten() + bottomKeys).forEach { k ->
            val r = k.rect
            val dx = maxOf(r.left - x, 0f, x - r.right); val dy = maxOf(r.top - y, 0f, y - r.bottom)
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = k }
        }
        val slop = dp(cfg.touchSlopDp.toFloat())
        return if (bd < slop * slop) best else null
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            if (!repeating) return
            host.deleteBack(); tick(1)
            handler.postDelayed(this, cfg.repeatMs.toLong())
        }
    }

    private val longGlobe = Runnable { pressed?.takeIf { it.code == Code.Globe }?.let { host.switchKeyboard(true); pressed = null; invalidate() } }

    private fun tick(kind: Int = 0) {
        if (host.haptics()) performHapticFeedback(when (cfg.hapticStrength) {
            0 -> HapticFeedbackConstants.CLOCK_TICK
            2 -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        })
        host.click(kind)
    }

    private fun kindOf(k: Key) = when (k.code) { Code.Delete -> 1; Code.Space -> 2; Code.Return -> 3; else -> 0 }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pasteClose.contains(e.x, e.y)) { host.dismissPaste(); refreshBar(); return true }
                if (pasteRect.contains(e.x, e.y)) { tick(); host.pasteNow(); refreshBar(); return true }
                val k = keyAt(e.x, e.y) ?: return true
                pressed = k; tick(kindOf(k)); invalidate()
                when (k.code) {
                    Code.Delete -> { host.deleteBack(); repeating = true; handler.postDelayed(repeatDelete, cfg.longPressMs.toLong()) }
                    Code.Globe -> handler.postDelayed(longGlobe, cfg.longPressMs.toLong())
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val k = pressed ?: return true
                if (k.code == Code.Char && page != Page.Emoji) {
                    // Parmak kayınca balon yeni tuşa geçer (iOS gibi; bırakınca o yazılır).
                    val n = keyAt(e.x, e.y)
                    if (n != null && n !== k && n.code == Code.Char) { pressed = n; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                val k = pressed
                repeating = false; handler.removeCallbacks(repeatDelete); handler.removeCallbacks(longGlobe)
                pressed = null
                if (k != null) release(k)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                repeating = false; handler.removeCallbacks(repeatDelete); handler.removeCallbacks(longGlobe); pressed = null; invalidate()
            }
        }
        return true
    }

    private fun release(k: Key) {
        when (k.code) {
            Code.Char -> {
                host.commit(display(k.label))
                if (page == Page.Letters && shift == Shift.On) { shift = Shift.Off; build() }
                // iOS: 123 sayfasında kesme işareti/boşluk sonrası harflere dönülür.
                if (page == Page.Numbers && k.label == "'") { page = Page.Letters; build() }
                updateAutoCaps()
            }
            Code.Shift -> {
                val now = System.currentTimeMillis()
                shift = when {
                    cfg.capsLock && now - lastShiftTap < 320 && shift == Shift.On -> Shift.Caps
                    shift == Shift.Off -> Shift.On
                    else -> Shift.Off
                }
                lastShiftTap = now; build()
            }
            Code.Delete -> updateAutoCaps()
            Code.Space -> {
                host.commit(" ")
                if (page == Page.Numbers || page == Page.Symbols) { page = Page.Letters; build() }
                updateAutoCaps()
            }
            Code.Return -> { host.action(); updateAutoCaps() }
            Code.Page123 -> { page = Page.Numbers; build() }
            Code.PageSym -> { page = Page.Symbols; build() }
            Code.PageAbc -> if (emojiBar) host.showEmoji(false) else { page = Page.Letters; build(); updateAutoCaps() }
            Code.PageEmoji -> host.showEmoji(true)
            Code.Globe -> host.switchKeyboard(false)
            Code.Mic -> host.voice()
            Code.OneHandOff -> host.setOneHanded(0)
            Code.OneHandSwap -> host.setOneHanded(if (cfg.oneHanded == 1) 2 else 1)
        }
    }
}
