package dev.glass.launcher.ime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

/**
 * Türkçe yazma motoru: tahmini metin (kelime tamamlama + sonraki kelime), otomatik düzeltme, yazım denetimi ve
 * kaydırarak yazma çözümü. Sözlük: assets/tr_words.txt ("kelime sıklık" satırları). Kullanıcının yazdığı kelimeler
 * ve kelime çiftleri öğrenilir (filesDir/kb_learn.json).
 */
object TrEngine {
    private val tr = Locale.forLanguageTag("tr")
    @Volatile var ready = false; private set

    private val freq = HashMap<String, Int>(70_000)
    private var sorted: Array<String> = emptyArray()
    /** İlk harf + uzunluk kovaları (düzeltme adayları hızlı bulunur). */
    private val buckets = HashMap<String, MutableList<String>>()
    /** Aksansız biçim → en sık kelime ("gunaydin" → "günaydın"). */
    private val plain = HashMap<String, String>()
    private val firstLast = HashMap<String, MutableList<String>>()

    private val userFreq = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var learnFile: File? = null
    private var dirty = false

    fun lower(s: String) = s.lowercase(tr)

    fun load(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            runCatching {
                ctx.assets.open("tr_words.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val sp = line.indexOf(' ').takeIf { it > 0 } ?: line.indexOf('\t').takeIf { it > 0 } ?: return@forEach
                        val w = lower(line.substring(0, sp).trim())
                        if (w.length > 24 || w.isEmpty() || !w.all { it.isLetter() }) return@forEach
                        val f = line.substring(sp + 1).trim().toIntOrNull() ?: 1
                        if (freq.putIfAbsent(w, f) == null) {
                            buckets.getOrPut(w[0] + ":" + w.length) { ArrayList() }.add(w)
                            val p = deaccent(w)
                            val cur = plain[p]
                            if (cur == null || (freq[cur] ?: 0) < f) plain[p] = w
                            if (w.length >= 2) firstLast.getOrPut("" + w[0] + w[w.length - 1]) { ArrayList() }.add(w)
                        }
                    }
                }
            }
            sorted = freq.keys.toTypedArray().also { it.sort() }
            loadLearned(ctx)
            ready = true
        }
    }

    fun deaccent(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) b.append(when (c) { 'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'; 'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'; else -> c })
        return b.toString()
    }

    private fun score(w: String): Double = ln(1.0 + (freq[w] ?: 0)) + 2.5 * ln(1.0 + (userFreq[w] ?: 0))

    fun known(word: String): Boolean {
        val w = lower(word)
        return freq.containsKey(w) || (userFreq[w] ?: 0) >= 2
    }

    // ---------- Tahmin ----------
    /** Yazılmakta olan kelimenin tamamlamaları (sıklığa göre). */
    fun completions(prefix: String, n: Int): List<String> {
        val p = lower(prefix)
        if (p.isEmpty() || sorted.isEmpty()) return emptyList()
        var lo = java.util.Arrays.binarySearch(sorted, p).let { if (it < 0) -it - 1 else it }
        val out = ArrayList<String>()
        var scanned = 0
        while (lo < sorted.size && sorted[lo].startsWith(p) && scanned < 4000) { out.add(sorted[lo]); lo++; scanned++ }
        userFreq.keys.filter { it.startsWith(p) && it !in out }.let { out.addAll(it) }
        return out.sortedByDescending { score(it) + if (it == p) 0.5 else 0.0 }.take(n)
    }

    /** Boşluktan sonra: öğrenilen kelime çiftleri, yoksa en sık başlangıç kelimeleri. */
    fun nextWords(prev: String?, n: Int): List<String> {
        val m = prev?.let { bigrams[lower(it)] }
        val learned = m?.entries?.sortedByDescending { it.value }?.map { it.key }?.take(n).orEmpty()
        if (learned.size >= n) return learned
        val common = listOf("ve", "bir", "bu", "ne", "çok", "ben", "sen", "ama", "için", "de")
        return (learned + common.filter { it !in learned }).take(n)
    }

    // ---------- Düzeltme ----------
    private val rowsQ = listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç")
    private val pos = HashMap<Char, Pair<Int, Int>>().apply { rowsQ.forEachIndexed { r, s -> s.forEachIndexed { c, ch -> put(ch, r to c) } } }
    private fun near(a: Char, b: Char): Boolean {
        val pa = pos[a] ?: return false; val pb = pos[b] ?: return false
        return kotlin.math.abs(pa.first - pb.first) <= 1 && kotlin.math.abs(pa.second - pb.second) <= 1
    }
    private fun subCost(a: Char, b: Char): Double = when {
        a == b -> 0.0
        deaccent(a.toString()) == deaccent(b.toString()) -> 0.25 // ç/c, ş/s, ğ/g, ı/i, ö/o, ü/u
        near(a, b) -> 0.6
        else -> 1.0
    }

    /** Ağırlıklı Damerau–Levenshtein (komşu tuş ve Türkçe harf farkı ucuz). */
    private fun dist(a: String, b: String, cap: Double): Double {
        val n = a.length; val m = b.length
        if (kotlin.math.abs(n - m) > 2) return 99.0
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) d[i][0] = i.toDouble()
        for (j in 0..m) d[0][j] = j.toDouble()
        for (i in 1..n) {
            var rowMin = 99.0
            for (j in 1..m) {
                var v = min(min(d[i - 1][j] + 1.0, d[i][j - 1] + 1.0), d[i - 1][j - 1] + subCost(a[i - 1], b[j - 1]))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 0.7)
                d[i][j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > cap) return 99.0
        }
        return d[n][m]
    }

    data class Cand(val word: String, val d: Double, val s: Double)

    /** Yanlış yazılmış bir kelime için düzeltme adayları (en iyisi başta). */
    fun corrections(word: String, n: Int): List<Cand> {
        val w = lower(word)
        if (w.length < 2 || !ready) return emptyList()
        val cap = if (w.length <= 4) 1.3 else if (w.length <= 7) 1.8 else 2.3
        val out = HashMap<String, Cand>()
        plain[deaccent(w)]?.let { if (it != w) out[it] = Cand(it, 0.2, score(it)) }
        val firsts = buildSet { add(w[0]); rowsQ.joinToString("").forEach { if (near(w[0], it) || subCost(w[0], it) < 0.5) add(it) } }
        for (f in firsts) for (len in (w.length - 2)..(w.length + 2)) {
            val list = buckets["$f:$len"] ?: continue
            for (c in list) {
                if (out.containsKey(c)) continue
                val dd = dist(w, c, cap) + if (f != w[0]) 0.4 else 0.0
                if (dd <= cap) out[c] = Cand(c, dd, score(c))
            }
        }
        return out.values.sortedBy { it.d * 2.2 - it.s * 0.35 }.take(n)
    }

    /** Otomatik düzeltme kararı: kelime bilinmiyorsa ve güvenli bir aday varsa onu döndürür. */
    fun autocorrect(word: String): String? {
        if (word.length < 2 || !word.all { it.isLetter() } || known(word)) return null
        val best = corrections(word, 2).firstOrNull() ?: return null
        val limit = if (word.length <= 3) 0.65 else if (word.length <= 6) 1.05 else 1.6
        return if (best.d <= limit) matchCase(word, best.word) else null
    }

    fun matchCase(src: String, w: String): String = when {
        src.length > 1 && src.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase(tr)
        src.firstOrNull()?.isUpperCase() == true -> w.replaceFirstChar { it.titlecase(tr) }
        else -> w
    }

    // ---------- Kaydırarak yazma ----------
    /** Parmağın geçtiği harf dizisinden (ilk ve son harf kesin) olası kelimeler. */
    fun swipe(path: String, n: Int): List<String> {
        if (path.length < 2 || !ready) return emptyList()
        val p = lower(path)
        val first = p[0]; val last = p[p.length - 1]
        val lasts = buildSet { add(last); rowsQ.joinToString("").forEach { if (near(last, it)) add(it) } }
        val cands = ArrayList<Pair<String, Double>>()
        for (l in lasts) {
            val list = firstLast["" + first + l] ?: continue
            for (w in list) {
                if (w.length > p.length + 1) continue
                val dedup = w.fold(StringBuilder()) { b, ch -> if (b.isEmpty() || b[b.length - 1] != ch) b.append(ch) else b }.toString()
                if (!isSubseq(dedup, p)) continue
                val s = score(w) - 0.35 * (p.length - dedup.length).coerceAtLeast(0) + (if (l == last) 0.6 else 0.0) + 0.25 * dedup.length
                cands.add(w to s)
            }
        }
        return cands.sortedByDescending { it.second }.map { it.first }.distinct().take(n)
    }

    private fun isSubseq(w: String, p: String): Boolean {
        var i = 0
        for (c in p) if (i < w.length && w[i] == c) i++
        return i == w.length
    }

    // ---------- Öğrenme ----------
    fun learn(word: String, prev: String?) {
        val w = lower(word)
        if (w.isEmpty() || !w.all { it.isLetter() } || w.length > 24) return
        userFreq[w] = (userFreq[w] ?: 0) + 1
        prev?.let { pv -> val p = lower(pv); if (p.all { it.isLetter() }) bigrams.getOrPut(p) { HashMap() }.merge(w, 1, Int::plus) }
        dirty = true
    }

    /** Kullanıcının geri aldığı düzeltme: kelime artık bilinen sayılır. */
    fun accept(word: String) { val w = lower(word); userFreq[w] = maxOf(2, (userFreq[w] ?: 0) + 2); dirty = true }

    private fun loadLearned(ctx: Context) {
        val f = File(ctx.filesDir, "kb_learn.json"); learnFile = f
        runCatching {
            val o = JSONObject(f.readText())
            o.optJSONObject("u")?.let { u -> u.keys().forEach { userFreq[it] = u.getInt(it) } }
            o.optJSONObject("b")?.let { b -> b.keys().forEach { k -> val m = HashMap<String, Int>(); val x = b.getJSONObject(k); x.keys().forEach { m[it] = x.getInt(it) }; bigrams[k] = m } }
        }
    }

    fun save() {
        if (!dirty) return
        val f = learnFile ?: return
        runCatching {
            // Boyut sınırı: en sık 5000 kelime, kelime başına en sık 12 devam.
            val u = JSONObject(); userFreq.entries.sortedByDescending { it.value }.take(5000).forEach { u.put(it.key, it.value) }
            val b = JSONObject(); bigrams.entries.sortedByDescending { e -> e.value.values.sum() }.take(3000).forEach { (k, m) ->
                val x = JSONObject(); m.entries.sortedByDescending { it.value }.take(12).forEach { x.put(it.key, it.value) }; b.put(k, x)
            }
            f.writeText(JSONObject().put("u", u).put("b", b).toString())
            dirty = false
        }
    }

    fun clearLearned(ctx: Context) { userFreq.clear(); bigrams.clear(); File(ctx.filesDir, "kb_learn.json").delete() }
}
