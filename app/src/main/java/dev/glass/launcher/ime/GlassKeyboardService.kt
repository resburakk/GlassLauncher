package dev.glass.launcher.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Glass Klavye: iOS 27 görünümlü, launcher'ın içindeki klavye (Ayarlar > Klavye'den etkinleştirilir).
 * İlk sürüm: Türkçe Q, 123/#+=, emoji, büyük harf/Caps, sil tekrarı, eyleme göre dönüş tuşu, hızlı yapıştır.
 */
class GlassKeyboardService : InputMethodService(), KeyboardView.Host {

    private var view: KeyboardView? = null
    private var emojiPanel: android.widget.LinearLayout? = null
    private var emojiBarView: KeyboardView? = null
    private var picker: androidx.emoji2.emojipicker.EmojiPickerView? = null
    private var paste: String? = null
    private var dismissedClip: String? = null

    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var cfg = KbConfig()
    private var lastSpaceAt = 0L
    // Tahmin/düzeltme durumu
    private var sugg: List<String> = emptyList()
    private var bold = -1
    private var typedWord = ""
    private var lastCorr: Pair<String, String>? = null // (yazılan, düzeltilen) — hemen silinirse geri alınır
    private var lastSwipe: String? = null
    private var swipeAlts: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Thread { TrEngine.load(applicationContext) }.start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Thread { TrEngine.save() }.start()
    }
    private val audio by lazy { getSystemService(android.media.AudioManager::class.java) }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        v.dark = isDark()
        view = v
        // Emoji paneli: Android'in tam emoji seçicisi (bütün emojiler, kategoriler, son kullanılanlar, ten rengi).
        val themed = android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight)
        val d = resources.displayMetrics.density
        val pk = androidx.emoji2.emojipicker.EmojiPickerView(themed).apply {
            emojiGridColumns = 8
            setOnEmojiPickedListener { commit(it.emoji) }
        }
        picker = pk
        val bar = KeyboardView(this, this, emojiBar = true).also { it.dark = isDark() }
        emojiBarView = bar
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
            addView(pk, android.widget.LinearLayout.LayoutParams(-1, (214 * d).toInt()))
            addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        emojiPanel = panel
        styleEmojiPanel()
        val root = android.widget.FrameLayout(this).apply {
            addView(v, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
            addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            // Alt pay: uygulamanın bildirdiği gezinme çubuğu bazı uygulamalarda (ör. Claude) 0 geliyordu ve Honor'un
            // "Klavyeyi yükselt" çubuğu tuşların üstüne biniyordu. Sistemin klavye altı çubuk yüksekliği de hesaba katılır.
            val rep = ins.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val nb = if (rep > 0) rep else maxOf(
                ins.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom,
                sysDimen("navigation_bar_frame_height"),
            )
            v.navInset = nb; bar.navInset = nb
            ins
        }
        return root
    }

    private fun sysDimen(name: String): Int = runCatching {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id != 0) resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    private fun styleEmojiPanel() {
        val panel = emojiPanel ?: return
        val d = resources.displayMetrics.density
        val bg = view?.colBg ?: 0xFFE4E6EA.toInt()
        panel.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadii = floatArrayOf(30 * d, 30 * d, 30 * d, 30 * d, 0f, 0f, 0f, 0f)
        }
        panel.setPadding(0, (8 * d).toInt(), 0, 0)
    }

    override fun showEmoji(show: Boolean) {
        emojiPanel?.visibility = if (show) View.VISIBLE else View.GONE
        view?.visibility = if (show) View.INVISIBLE else View.VISIBLE
        if (!show) view?.updateAutoCaps()
    }

    /**
     * 120 Hz: klavye penceresi ekranın en yüksek yenileme hızını ister (tuş basışı, balon ve emoji kaydırma akıcı).
     * Android 15+ uyarlanabilir yenileme hızı (≈60 Hz'e düşürme) bu pencere için kapatılır; görünümler de yüksek
     * hız kategorisi ister. Not: MagicOS'ta ekran yenileme hızı "Yüksek"/"Dinamik" değilse sistem sınırlayabilir.
     */
    private fun requestHighRefresh() {
        val w = window?.window ?: return
        runCatching {
            val d = w.decorView.display ?: (getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)) ?: return@runCatching
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return@runCatching
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId; preferredRefreshRate = best.refreshRate }
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) runCatching {
            w.isFrameRatePowerSavingsBalanced = false
            listOfNotNull(view, emojiBarView, picker).forEach { it.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH }
        }
    }

    /**
     * Cam zemin: klavye penceresinin arkası gerçekten bulanıklaştırılır (Android 12+ pencere arkası bulanıklığı;
     * ana ekrandaki duvar kâğıdı bulanıklığıyla aynı sistem özelliği). Cihaz/ayar desteklemiyorsa opak zemin.
     */
    private fun applyGlass() {
        val w = window?.window ?: return
        val wm = getSystemService(android.view.WindowManager::class.java)
        val ok = cfg.glass && android.os.Build.VERSION.SDK_INT >= 31 && runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        val d = resources.displayMetrics.density
        runCatching {
            if (ok) {
                w.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 30 * d; setColor(android.graphics.Color.TRANSPARENT)
                })
                w.setBackgroundBlurRadius((36 * d).toInt())
            } else if (android.os.Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(0)
        }
        view?.glass = ok; emojiBarView?.glass = ok
        emojiPanel?.background?.let { (it as? android.graphics.drawable.GradientDrawable)?.setColor(if (ok) (if (isDark()) 0xB01B1B1E.toInt() else 0xBDE4E6EA.toInt()) else (view?.colBg ?: 0)) }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        requestHighRefresh()
        applyGlass()
        // Üst sıradaki harf balonu klavyenin üstüne taşabilsin: üst kapsayıcılar kırpmasın.
        var p = view?.parent
        while (p is android.view.ViewGroup) { p.clipChildren = false; p.clipToPadding = false; p = p.parent }
    }

    private fun isDark() = when (cfg.theme) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cfg = KbConfig.load(this)
        readClipboard()
        val v = view ?: return
        v.cfg = cfg; emojiBarView?.cfg = cfg
        v.dark = isDark(); emojiBarView?.dark = isDark(); styleEmojiPanel(); showEmoji(false)
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        lastCorr = null; lastSwipe = null; swipeAlts = emptyList()
        v.reset(if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) KeyboardView.Page.Numbers else KeyboardView.Page.Letters)
        updateSuggestions()
        v.refreshBar()
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        view?.updateAutoCaps()
        updateSuggestions()
    }

    /** iOS 27 hızlı yapıştır: son 3 dakikada kopyalanan yazı şeritte gösterilir. */
    private fun readClipboard() {
        paste = runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip ?: return@runCatching null
            val age = System.currentTimeMillis() - clip.description.timestamp
            if (age > 3 * 60_000L) return@runCatching null
            clip.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() && it != dismissedClip }
        }.getOrNull()
    }

    // ---------- Host ----------
    override fun commit(text: String) {
        val ic = currentInputConnection ?: return
        var t = text
        val plain = plainField()
        if (t != " ") lastCorr = null
        lastSwipe = null
        if (plain && (t == " " || t == "\n" || t in listOf(".", ",", "!", "?"))) {
            if (!applyReplacement()) autoCorrectWord(t)
            learnLastWord()
        }
        if (t == " " && plain && cfg.periodShortcut) {
            // "." kısayolu: kelimeden sonra iki kez boşluk = nokta + boşluk.
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            val now = SystemClock.uptimeMillis()
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit() && now - lastSpaceAt < 600) {
                ic.deleteSurroundingText(1, 0); t = ". "; lastSpaceAt = 0L
                ic.commitText(t, 1); return
            }
            lastSpaceAt = now
        }
        if (plain && cfg.smartPunct && (t == "\"" || t == "'")) {
            // Akıllı noktalama: boşluktan/başlangıçtan sonra açılış, aksi halde kapanış tırnağı.
            val prev = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            val opening = prev.isEmpty() || prev[0].isWhitespace() || prev[0] in "([{“‘"
            t = if (t == "\"") (if (opening) "“" else "”") else (if (opening) "‘" else "’")
        }
        ic.commitText(t, 1)
    }

    /** Metin değiştirme: imleçten önceki kelime listede varsa karşılığıyla değiştirilir (ör. "slm" → "Selam"). */
    private fun applyReplacement(): Boolean {
        if (cfg.replacements.isEmpty()) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return false
        val word = before.takeLastWhile { !it.isWhitespace() && it !in ".,!?" }
        if (word.isEmpty()) return false
        val hit = cfg.replacements.entries.firstOrNull { it.key.equals(word, ignoreCase = true) } ?: return false
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(hit.value, 1)
        return true
    }

    private fun currentWord(): String =
        currentInputConnection?.getTextBeforeCursor(32, 0)?.toString()?.takeLastWhile { it.isLetter() }.orEmpty()

    /** Otomatik düzeltme: boşluk/noktalama gelince bilinmeyen kelime en iyi adayla değiştirilir. */
    private fun autoCorrectWord(trigger: String) {
        if (!cfg.autocorrect || !TrEngine.ready) return
        val ic = currentInputConnection ?: return
        val w = currentWord()
        if (w.length < 2) return
        val fixed = TrEngine.autocorrect(w) ?: return
        if (fixed == w) return
        ic.deleteSurroundingText(w.length, 0)
        ic.commitText(fixed, 1)
        if (trigger == " ") lastCorr = w to fixed
    }

    private fun learnLastWord() {
        if (!TrEngine.ready) return
        val w = currentWord()
        if (w.isNotEmpty()) TrEngine.learn(w, prevWordBefore(w))
    }

    private fun prevWordBefore(w: String): String? {
        val b = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return null
        val rest = b.dropLast(w.length).trimEnd()
        if (rest.isEmpty() || rest.last() in ".!?") return null
        return rest.takeLastWhile { it.isLetter() }.takeIf { it.isNotEmpty() }
    }

    /** Öneri şeridini günceller (yazılan kelimeye göre tamamlama/düzeltme, boşluktan sonra sonraki kelime). */
    private fun updateSuggestions() {
        if (!cfg.predictive || !TrEngine.ready || !plainField()) { sugg = emptyList(); bold = -1; view?.invalidate(); return }
        if (lastSwipe != null && swipeAlts.isNotEmpty()) {
            sugg = listOf(swipeAlts.getOrElse(1) { "" }, lastSwipe!!, swipeAlts.getOrElse(2) { "" }); bold = -1
            view?.invalidate(); return
        }
        val w = currentWord()
        typedWord = w
        if (w.isEmpty()) {
            val b = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val prev = if (b.endsWith(" ")) b.trimEnd().takeLastWhile { it.isLetter() }.takeIf { it.isNotEmpty() && !b.trimEnd().endsWith(".") } else null
            val cap = view?.let { autoCaps() } == true
            sugg = TrEngine.nextWords(prev, 3).map { if (cap) it.replaceFirstChar { c -> c.titlecase(java.util.Locale.forLanguageTag("tr")) } else it }
            bold = -1
        } else {
            val comps = TrEngine.completions(w, 4).map { TrEngine.matchCase(w, it) }.filter { it != w }
            val auto = if (cfg.autocorrect) TrEngine.autocorrect(w) else null
            val corr = if (!TrEngine.known(w)) TrEngine.corrections(w, 3).map { TrEngine.matchCase(w, it.word) } else emptyList()
            val middle = auto ?: comps.firstOrNull() ?: corr.firstOrNull() ?: ""
            val right = (comps + corr).firstOrNull { it != middle && it != w } ?: ""
            sugg = listOf("“$w”", middle, right)
            bold = if (auto != null) 1 else -1
        }
        view?.invalidate()
    }

    override fun suggestions(): List<String> = sugg
    override fun boldSuggestion(): Int = bold

    override fun pickSuggestion(i: Int) {
        val ic = currentInputConnection ?: return
        var t = sugg.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return
        if (lastSwipe != null) {
            // Kaydırmayla yazılan kelimenin yerine seçilen alternatif.
            val b = ic.getTextBeforeCursor(lastSwipe!!.length, 0)?.toString()
            if (b == lastSwipe) ic.deleteSurroundingText(lastSwipe!!.length, 0)
            ic.commitText(t, 1); lastSwipe = t; swipeAlts = emptyList(); updateSuggestions(); return
        }
        val literal = t.startsWith("“") && t.endsWith("”")
        if (literal) { t = t.substring(1, t.length - 1); TrEngine.accept(t) }
        val w = currentWord()
        if (w.isNotEmpty()) ic.deleteSurroundingText(w.length, 0)
        ic.commitText(t, 1)
        TrEngine.learn(t, prevWordBefore(t))
        ic.commitText(" ", 1)
        lastCorr = null
        view?.updateAutoCaps(); updateSuggestions()
    }

    override fun swipeWord(path: String, capital: Boolean) {
        val ic = currentInputConnection ?: return
        if (!TrEngine.ready) return
        val c = TrEngine.swipe(path, 3)
        if (c.isEmpty()) return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{“‘\"'") ic.commitText(" ", 1)
        val tr = java.util.Locale.forLanguageTag("tr")
        val words = c.map { if (capital) it.replaceFirstChar { ch -> ch.titlecase(tr) } else it }
        ic.commitText(words[0], 1)
        TrEngine.learn(words[0], prevWordBefore(words[0]))
        lastSwipe = words[0]; swipeAlts = words; lastCorr = null
        updateSuggestions()
    }

    /** Şifre, e-posta, adres gibi alanlarda düzeltme/noktalama yapılmaz. */
    private fun plainField(): Boolean {
        val it = currentInputEditorInfo?.inputType ?: return false
        if (it and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val v = it and InputType.TYPE_MASK_VARIATION
        return v != InputType.TYPE_TEXT_VARIATION_PASSWORD && v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD && v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS && v != InputType.TYPE_TEXT_VARIATION_URI
    }

    override fun click(kind: Int) {
        if (!cfg.sound) return
        runCatching {
            audio.playSoundEffect(when (kind) {
                1 -> android.media.AudioManager.FX_KEYPRESS_DELETE
                2 -> android.media.AudioManager.FX_KEYPRESS_SPACEBAR
                3 -> android.media.AudioManager.FX_KEYPRESS_RETURN
                else -> android.media.AudioManager.FX_KEYPRESS_STANDARD
            }, 0.6f)
        }
    }

    override fun setOneHanded(mode: Int) {
        cfg = cfg.copy(oneHanded = mode); KbConfig.save(this, cfg)
        view?.cfg = cfg
    }

    override fun deleteBack() {
        val ic = currentInputConnection ?: return
        lastCorr?.let { (orig, fixed) ->
            val tail = "$fixed "
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                // iOS gibi: düzeltmeden hemen sonra sil = yazdığına geri dön (ve kelimeyi öğren).
                ic.deleteSurroundingText(tail.length, 0); ic.commitText(orig, 1); TrEngine.accept(orig)
                lastCorr = null; updateSuggestions(); return
            }
        }
        lastCorr = null
        lastSwipe?.let { sw ->
            if (cfg.swipeDeleteWord && ic.getTextBeforeCursor(sw.length, 0)?.toString() == sw) {
                ic.deleteSurroundingText(sw.length, 0); lastSwipe = null; swipeAlts = emptyList(); updateSuggestions(); return
            }
        }
        lastSwipe = null
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else {
            // Emoji gibi iki karakterlik (vekil çift) simgeler tek seferde silinsin.
            val before = ic.getTextBeforeCursor(2, 0)
            val n = if (before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
            if (before.isNullOrEmpty()) sendKey(KeyEvent.KEYCODE_DEL) else ic.deleteSurroundingText(n, 0)
        }
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun imeAction(): Int {
        val o = currentInputEditorInfo?.imeOptions ?: 0
        if (o and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
        return o and EditorInfo.IME_MASK_ACTION
    }

    private fun multiLine() = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    override fun action() {
        val a = imeAction()
        if (a == EditorInfo.IME_ACTION_NONE || a == EditorInfo.IME_ACTION_UNSPECIFIED || multiLine() && a == EditorInfo.IME_ACTION_DONE) {
            commit("\n")
        } else {
            currentInputConnection?.performEditorAction(a)
        }
    }

    override fun returnLabel(): String? = when (imeAction()) {
        EditorInfo.IME_ACTION_SEND -> "gönder"
        EditorInfo.IME_ACTION_SEARCH -> "ara"
        EditorInfo.IME_ACTION_GO -> "git"
        EditorInfo.IME_ACTION_NEXT -> "ileri"
        EditorInfo.IME_ACTION_DONE -> if (multiLine()) null else "bitti"
        else -> null
    }

    override fun returnIsAction(): Boolean = when (imeAction()) {
        EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> true
        else -> false
    }

    override fun switchKeyboard(longPress: Boolean) {
        if (longPress || !switchToNextInputMethod(false)) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    override fun voice() {
        // Sesle yazma: telefondaki sesli giriş klavyesine (ör. Google sesle yazma) geçilir; yoksa klavye seçici.
        val imm = getSystemService(InputMethodManager::class.java)
        val voiceIme = imm.enabledInputMethodList.firstOrNull { im ->
            (0 until im.subtypeCount).any { im.getSubtypeAt(it).mode == "voice" }
        }
        if (voiceIme != null) {
            val st = (0 until voiceIme.subtypeCount).map { voiceIme.getSubtypeAt(it) }.first { it.mode == "voice" }
            switchInputMethod(voiceIme.id, st)
        } else imm.showInputMethodPicker()
    }

    override fun pasteChip(): String? = paste
    override fun pasteNow() { paste?.let { currentInputConnection?.commitText(it, 1); dismissedClip = it }; paste = null }
    override fun dismissPaste() { dismissedClip = paste; paste = null }

    override fun autoCaps(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (!cfg.autoCaps) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(info.inputType) != 0
    }

    override fun haptics(): Boolean = cfg.haptics

    companion object {
        const val PREFS = "glass_keyboard"
        const val KEY_HAPTICS = "haptics"
        const val KEY_AUTOCAPS = "autocaps"

        /** Bu klavye sistemde etkin mi / seçili mi (ayarlar sayfası için). */
        fun isEnabled(ctx: Context): Boolean =
            ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == ctx.packageName }

        fun isSelected(ctx: Context): Boolean {
            val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
            return cur.startsWith(ctx.packageName + "/")
        }
    }
}
