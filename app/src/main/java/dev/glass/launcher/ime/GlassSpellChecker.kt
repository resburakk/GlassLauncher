package dev.glass.launcher.ime

import android.service.textservice.SpellCheckerService
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

/**
 * Yazım denetimi (sistem geneli): Ayarlar > Dil ve giriş > Yazım denetleyicisi'nden "Glass Klavye" seçilince
 * uygulamalardaki yazı alanlarında yanlış kelimelerin altı kırmızı çizilir ve düzeltme önerileri çıkar.
 */
class GlassSpellChecker : SpellCheckerService() {
    override fun createSession(): Session = object : Session() {
        override fun onCreate() { TrEngine.load(applicationContext) }

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo {
            val w = textInfo.text.orEmpty().trim()
            if (w.length < 2 || !w.all { it.isLetter() } || TrEngine.known(w) || !TrEngine.ready) {
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray())
            }
            val c = TrEngine.corrections(w, suggestionsLimit.coerceIn(1, 5)).map { TrEngine.matchCase(w, it.word) }
            val attr = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or (if (c.isNotEmpty()) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0)
            return SuggestionsInfo(attr, c.toTypedArray())
        }
    }
}
