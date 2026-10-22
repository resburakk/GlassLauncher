package dev.glass.launcher.ui

import android.graphics.RuntimeShader

/**
 * Liquid Glass kırılması (AGSL, ekran kartında çalışır; Android 13+).
 *
 * Camın içinde arkadaki görüntü olduğu gibi görünür; kenara yaklaştıkça örnekleme noktası içeri doğru
 * kayar, böylece kenar bandında görüntü mercekteki gibi bükülüp sıkışır (iOS 26/27 "lensing").
 * Kenar bandında hafif bir aydınlanma da eklenir. Şekil, köşe yarıçapı [radius] olan yuvarlak dikdörtgendir.
 *
 * `content` girdisi: RenderEffect ile kullanıldığında katmanın kendisi, fırça olarak kullanıldığında
 * bulanık duvar kâğıdı. [origin], yerel koordinatı girdinin koordinat sistemine taşır.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 origin;
uniform float radius;
uniform float edge;
uniform float strength;
uniform float glow;
uniform float soft;
// 0: eski ince bant; 1: iOS 26 kalın kenar (dışarıdan bükme, kavisli eğim, yönlü parlama).
uniform float mode;
// Camın düğüm içindeki konumu (panel kayıtları kenar bandı kadar geniş tutulur; dışarıdan bükme için).
uniform float2 pos;
// Işık yönü (birim vektör, dışa bakan normal ile karşılaştırılır) ve karşı kenar yansıması.
uniform float2 light;
uniform float back;

// Şeffaf camda net görüntü çok hafif yumuşatılır (9 örnek); buzlu camda girdi zaten bulanıktır.
half4 sampleAt(float2 p) {
    if (soft < 0.5) return content.eval(p);
    float d = soft * 0.7;
    half4 s = content.eval(p) * 0.2;
    s += (content.eval(p + float2(soft, 0.0)) + content.eval(p - float2(soft, 0.0)) +
          content.eval(p + float2(0.0, soft)) + content.eval(p - float2(0.0, soft))) * 0.12;
    s += (content.eval(p + float2(d, d)) + content.eval(p - float2(d, d)) +
          content.eval(p + float2(d, -d)) + content.eval(p + float2(-d, d))) * 0.08;
    return s;
}

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord0) {
    // Geometri camın kendi koordinatında; örnekleme düğüm koordinatında (coord0).
    float2 coord = coord0 - pos;
    float2 c = size * 0.5;
    float2 p = coord - c;
    float r = min(radius, min(c.x, c.y));
    // Bant hesabı daha yuvarlak bir köşeyle yapılır: köşe yarıçapı bant genişliğinden küçükken bandın iç
    // sınırı kare köşeli bir dikdörtgen oluyor ve köşede bükülme yönü çapraz bir çizgide aniden dönüyordu
    // (camın içinde silik köşeli çerçeve). Böylece iç sınır da yuvarlak, köşedeki bükülme yönü yumuşak döner.
    float rb = min(r + edge * 0.85, min(c.x, c.y));
    float d = sdRoundRect(p, c, rb);
    // 0: camın derin içi, 1: tam kenar.
    float t = clamp(1.0 + d / max(edge, 1.0), 0.0, 1.0);
    float k = t * t * t;
    // Köşelerde iki kenarın etkisi üst üste biner: köşe yayında bükülmeyi ve parlamayı kademeli yumuşat.
    float2 q = abs(p) - (c - rb);
    float corner = 1.0 - 0.45 * smoothstep(0.0, max(rb * 0.7, 1.0), min(q.x, q.y));
    k *= corner;
    float2 e = float2(1.0, 0.0);
    float2 g = float2(
        sdRoundRect(p + e.xy, c, rb) - sdRoundRect(p - e.xy, c, rb),
        sdRoundRect(p + e.yx, c, rb) - sdRoundRect(p - e.yx, c, rb));
    float2 n = g / max(length(g), 0.0001);
    if (mode > 0.5) {
        // iOS 26: kenar, yuvarlatılmış kalın bir cam gibi. Eğim (kavisli profil) kenara yaklaştıkça hızla dikleşir;
        // görüntü kenar bandında DIŞARIDAN içeri bükülür (arkadaki çizgiler kenar boyunca kıvrılır), ortası bozulmaz.
        // Bandın yeri camın GERÇEK şekline göre (daha yuvarlak yardımcı şekil içeride ikinci bir hat gibi
        // görünüyordu); yardımcı şekil yalnızca bükülme yönü için (köşede yön yumuşak dönsün, kırışma olmasın).
        float dr = sdRoundRect(p, c, r);
        if (dr > 0.0) return sampleAt(origin + coord0);
        float ti = clamp(1.0 + dr / max(edge, 1.0), 0.0, 1.0);
        float tt = clamp(ti, 0.0, 0.999);
        float slope = 1.0 - sqrt(1.0 - tt * tt);
        slope *= corner;
        float2 src2 = coord0 + n * slope * strength;
        half4 c2 = sampleAt(origin + src2);
        // Yönlü parlama: ışık sol üstten; eğimin en dik yerinde ince bir çizgi (sol üst ve sağ alt kenar).
        float ld = dot(n, light);
        float lit = pow(max(ld, 0.0), 1.6) + back * pow(max(-ld, 0.0), 1.6);
        float rim = pow(slope, 3.0) * lit;
        c2.rgb = min(c2.rgb + half3(rim * glow * 6.0), half3(1.0));
        return c2;
    }
    // Kenarda içeriden örnekle (mercek), tüm yüzeyde çok hafif büyütme.
    float2 src = coord0 - n * k * strength;
    src = (pos + c) + (src - (pos + c)) * 0.985;
    half4 col = sampleAt(origin + src);
    col.rgb = min(col.rgb + half3(k * glow * corner), half3(1.0));
    return col;
}
"""

object GlassLens {
    /** Ekran yoğunluğu (dp → px); uygulama açılışında ayarlanır. */
    @Volatile var density: Float = 3f
    /** iOS kalın kenar: bant genişliği, kısa kenarın oranı (Ayarlar > Liquid Glass > Kenar kalınlığı). */
    @Volatile var edgeFrac: Float = 0.6f
    /** Işık yönü (derece, 0 üst, saat yönünde) ve karşı kenar yansıması (Ayarlar > Liquid Glass). */
    @Volatile var lightAngle: Float = 315f
    @Volatile var lightBack: Float = 0.3f
    /** Alt temas gölgesi (simgeler bunu okur; diğer camlar ayarı doğrudan okur). */
    @Volatile var contactShadow: Float = 0.5f
    /** Panel kayıtlarına eklenecek kenar payı (px): dışarıdan bükme için. */
    fun padPx(): Int = (48f * density).toInt()

    /** Her cam yüzeyi kendi örneğini kullanır (uniform'lar örneğe özeldir). */
    fun newShader(): RuntimeShader = RuntimeShader(LENS_AGSL)

    /**
     * Uniform'ları ayarlar. [amount] 0..1 kırılma ayarı: 0 = kırılma yok. Kenar bandı ve en büyük kayma
     * yüzeyin kısa kenarına oranlanır; küçük simgede de büyük panelde de aynı görünür.
     */
    fun configure(
        s: RuntimeShader, w: Float, h: Float, radius: Float, amount: Float, ox: Float = 0f, oy: Float = 0f,
        /** Şeffaf camda net görüntüye uygulanan hafif yumuşatma (px); 0 = yok. */
        soft: Float = 0f,
        /** iOS 26 kalın kenar profili. */
        ios: Boolean = false,
        /** Camın düğüm içindeki konumu (panel kaydının kenar payı). */
        px: Float = 0f, py: Float = 0f,
    ) {
        val m = minOf(w, h)
        s.setFloatUniform("mode", if (ios) 1f else 0f)
        s.setFloatUniform("pos", px, py)
        val rad = Math.toRadians(lightAngle.toDouble())
        s.setFloatUniform("light", kotlin.math.sin(rad).toFloat(), -kotlin.math.cos(rad).toFloat())
        s.setFloatUniform("back", lightBack)
        if (ios) {
            // Kalın kenar: bant kısa kenarın ayarlanan oranı (en çok 44 dp), bükülme bandın ~%90'ı kadar dışarıdan.
            val edgeI = minOf(m * edgeFrac, 44f * density)
            val a = amount.coerceIn(0.2f, 1.4f)
            s.setFloatUniform("size", w, h)
            s.setFloatUniform("origin", ox, oy)
            s.setFloatUniform("radius", radius)
            s.setFloatUniform("edge", edgeI)
            s.setFloatUniform("strength", edgeI * 0.9f * a)
            s.setFloatUniform("glow", 0.05f * a)
            s.setFloatUniform("soft", soft)
            return
        }
        // iOS'taki gibi kırılma yalnızca ince bir kenar şeridinde: büyük panellerde bant ve bükülme sınırlı.
        val edge = minOf(m * 0.30f, 34f * density)
        val strength = minOf(m * 0.30f * amount, 16f * density * amount.coerceAtMost(1.2f))
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("origin", ox, oy)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("edge", edge)
        s.setFloatUniform("strength", strength)
        s.setFloatUniform("glow", 0.05f * amount)
        s.setFloatUniform("soft", soft)
    }
}
