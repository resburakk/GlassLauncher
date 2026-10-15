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

half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = coord - c;
    float r = min(radius, min(c.x, c.y));
    float d = sdRoundRect(p, c, r);
    // 0: camın derin içi, 1: tam kenar.
    float t = clamp(1.0 + d / max(edge, 1.0), 0.0, 1.0);
    float k = t * t * t;
    float2 e = float2(1.0, 0.0);
    float2 g = float2(
        sdRoundRect(p + e.xy, c, r) - sdRoundRect(p - e.xy, c, r),
        sdRoundRect(p + e.yx, c, r) - sdRoundRect(p - e.yx, c, r));
    float2 n = g / max(length(g), 0.0001);
    // Kenarda içeriden örnekle (mercek), tüm yüzeyde çok hafif büyütme.
    float2 src = coord - n * k * strength;
    src = c + (src - c) * 0.985;
    half4 col = sampleAt(origin + src);
    col.rgb = min(col.rgb + half3(k * glow), half3(1.0));
    return col;
}
"""

object GlassLens {
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
    ) {
        val m = minOf(w, h)
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("origin", ox, oy)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("edge", m * 0.30f)
        s.setFloatUniform("strength", m * 0.30f * amount)
        s.setFloatUniform("glow", 0.07f * amount)
        s.setFloatUniform("soft", soft)
    }
}
