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
    private var paste: String? = null
    private var dismissedClip: String? = null

    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        v.dark = isDark()
        ViewCompat.setOnApplyWindowInsetsListener(v) { vv, ins ->
            (vv as KeyboardView).navInset = ins.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            ins
        }
        view = v
        return v
    }

    private fun isDark() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        readClipboard()
        val v = view ?: return
        v.dark = isDark()
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        v.reset(if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) KeyboardView.Page.Numbers else KeyboardView.Page.Letters)
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        view?.updateAutoCaps()
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
    override fun commit(text: String) { currentInputConnection?.commitText(text, 1) }

    override fun deleteBack() {
        val ic = currentInputConnection ?: return
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
    override fun pasteNow() { paste?.let { commit(it); dismissedClip = it }; paste = null }
    override fun dismissPaste() { dismissedClip = paste; paste = null }

    override fun autoCaps(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (!prefs.getBoolean(KEY_AUTOCAPS, true)) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(info.inputType) != 0
    }

    override fun haptics(): Boolean = prefs.getBoolean(KEY_HAPTICS, true)

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
