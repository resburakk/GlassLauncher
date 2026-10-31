package dev.glass.launcher.ui

import androidx.compose.ui.text.TextStyle

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.glass.launcher.R

/**
 * Uygulama yazı tipi: Inter (SIL Open Font License 1.1).
 * iOS 26'nın SF Pro fontu Apple lisansı gereği Apple dışı platformlarda dağıtılamaz; Inter, SF Pro'ya
 * biçim ve ölçü olarak en yakın açık lisanslı yazı tipidir. Değişken font tek dosyadan tüm ağırlıkları verir.
 */
@OptIn(ExperimentalTextApi::class)
val AppFont: FontFamily = FontFamily(
    listOf(300, 400, 500, 600, 700).map { w ->
        Font(
            R.font.inter,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    }
)

/**
 * iOS 26 ana ekran yazılarının altındaki gölge: koyu ama dağınık ve belirsiz
 * (keskin bir kenar oluşturmaz; açık duvar kâğıtlarında okunurluğu korur).
 */
val IosTextShadow: Shadow
    get() = if (!TextShadowConfig.enabled || HomeInk.light) Shadow.None else Shadow(
        color = Color.Black.copy(alpha = TextShadowConfig.strength),
        offset = Offset(0f, 2f * TextShadowConfig.spread.coerceAtMost(1.5f)),
        blurRadius = 12f * TextShadowConfig.spread,
    )

/**
 * Duvar kâğıdı açık renkliyse (koyu yazıyı destekliyorsa) ana ekrandaki uygulama ve widget adları ile "Ara" düğmesi
 * iOS'taki gibi koyu olur, gölgesiz. Duvar kâğıdı değişince kendiliğinden güncellenir.
 */
object HomeInk {
    var light by mutableStateOf(false)
    val label = Color(0xF0242426)
    val soft = Color(0xB3242426)
}

/**
 * Duvar kâğıdı üstündeki yazı/simge rengi (ana ekran, klasör, Uygulama Arşivi, arama, widget sayfası): açık duvar
 * kâğıdında beyaz tonlar aynı saydamlıkta koyu griye çevrilir. Diğer renkler (kırmızı, mavi) değişmez.
 */
internal fun ink(c: Color): Color =
    if (HomeInk.light && c.red > 0.85f && c.green > 0.85f && c.blue > 0.85f) Color(0.141f, 0.141f, 0.149f, c.alpha) else c

/** Ana ekran (duvar kâğıdı üstü) etiket stili: açık duvar kâğıdında koyu, gölgesiz. */
internal fun TextStyle.onWallpaper(): TextStyle = if (HomeInk.light) copy(color = HomeInk.label, shadow = Shadow.None) else this

/** Yazı gölgesi ayarları (ayarlardan güncellenir; okuyan yazılar otomatik yenilenir). */
object TextShadowConfig {
    var enabled by mutableStateOf(false)
    var strength by mutableFloatStateOf(0.35f)
    var spread by mutableFloatStateOf(1f)
}

/** Ayardaki kalınlık değerini (300–700) en yakın font kalınlığına yuvarlar. */
fun fontWeightOf(w: Float): FontWeight = FontWeight(((w / 100f).roundToInt() * 100).coerceIn(100, 900))

/** Ayar ekranında kalınlık adı. */
fun fontWeightName(w: Float): String = when (((w / 100f).roundToInt() * 100)) {
    300 -> "İnce"
    400 -> "Normal"
    500 -> "Orta"
    600 -> "Yarı kalın"
    else -> "Kalın"
}
