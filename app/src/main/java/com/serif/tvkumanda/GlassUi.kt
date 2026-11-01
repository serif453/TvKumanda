package com.serif.tvkumanda

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kumanda sekmesinin cam görünümü (Serif'in 1 Kasım 2026'da gönderdiği tasarım).
 * Arka plan koyu lacivert-mor, üstünde mor ışık geçişleri; kartlar ve tuşlar yarı saydam "cam",
 * kenarlarında üstte daha parlak ince çizgi. Gerçek bulanıklık yok: arka plan düz bir geçiş olduğu için
 * yarı saydam katman aynı görünümü verir ve her Android sürümünde çalışır.
 */
object Glass {
    val BgTop = Color(0xFF1B1A3C)
    val BgMid = Color(0xFF15162F)
    val BgBottom = Color(0xFF111226)
    val Glow = Color(0xFF6A4FE0)
    val Text = Color(0xFFF3F3FB)
    val Sub = Color(0xFFAEB0CC)
    val Lavender = Color(0xFFB9A2FF)
    val Purple = Color(0xFF8C6CF7)
    val Blue = Color(0xFF6F7CFF)
    val Red = Color(0xFFFF4D5E)

    /** Yazı tipi: Inter (SIL OFL 1.1, yalnızca Latin + Türkçe harfler; lisans assets/fonts/INTER-LICENSE.txt). */
    val Inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold)
    )

    val OkRing = Brush.linearGradient(listOf(Color(0xFFD2C2FF), Color(0xFF8D6CF8), Color(0xFF6F7CFF)))
    val PlayRing = Brush.linearGradient(listOf(Color(0xFFB9A2FF), Color(0xFF6F8BFF)))
    val VolumeFill = Brush.horizontalGradient(listOf(Color(0xFF8C6CF7), Color(0xFFB59BFF)))
    val Mic = Brush.verticalGradient(listOf(Color(0xFF6B6CF6), Color(0xFF4A47D9)))
}

/** Ekranın arka planı: koyu geçiş + sağ üstte ve aşağıda mor ışık şeritleri. */
fun Modifier.glassBackdrop(): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Glass.BgTop, Glass.BgMid, Glass.BgBottom)))
    // Üstte, sağa doğru yükselen yumuşak mor şerit
    val top = Path().apply {
        moveTo(w * 0.30f, 0f)
        cubicTo(w * 0.45f, h * 0.05f, w * 0.62f, h * 0.035f, w * 0.80f, 0f)
        close()
    }
    drawPath(top, Brush.radialGradient(listOf(Glass.Glow.copy(alpha = 0.55f), Color.Transparent), Offset(w * 0.6f, 0f), w * 0.55f))
    drawCircle(Brush.radialGradient(listOf(Glass.Glow.copy(alpha = 0.22f), Color.Transparent), Offset(w * 0.62f, -h * 0.02f), w * 0.6f), w * 0.6f, Offset(w * 0.62f, -h * 0.02f))
    // Altta, oynatıcının altındaki boşlukta soldan sağa kıvrılan ışık
    val low = Path().apply {
        moveTo(w * 0.15f, h * 0.87f)
        cubicTo(w * 0.40f, h * 0.86f, w * 0.65f, h * 0.83f, w * 0.85f, h * 0.795f)
        cubicTo(w * 0.62f, h * 0.845f, w * 0.38f, h * 0.875f, w * 0.15f, h * 0.87f)
        close()
    }
    drawPath(low, Brush.horizontalGradient(listOf(Color.Transparent, Glass.Glow.copy(alpha = 0.50f), Glass.Glow.copy(alpha = 0.15f)), w * 0.15f, w * 0.85f))
    drawCircle(Brush.radialGradient(listOf(Glass.Glow.copy(alpha = 0.16f), Color.Transparent), Offset(w * 0.55f, h * 0.84f), w * 0.5f), w * 0.5f, Offset(w * 0.55f, h * 0.84f))
}

/**
 * Cam yüzey: yarı saydam beyaz geçiş + üstte parlak, altta sönük kenar çizgisi.
 * [strength] kart için düşük, tuş için yüksek.
 */
fun Modifier.glass(shape: Shape, pressed: Boolean = false, strength: Float = 1f, elevation: Dp = 0.dp): Modifier {
    val top = (0.11f * strength + if (pressed) 0.07f else 0f)
    val bottom = (0.04f * strength + if (pressed) 0.05f else 0f)
    return this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom))))
        .border(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f * strength.coerceAtMost(1f) + 0.04f), Color.White.copy(alpha = 0.05f))),
            shape
        )
}

/** Parlayan halka (OK ve oynat düğmeleri): dışta renkli yumuşak ışık. */
fun Modifier.glow(color: Color, radius: Dp, shape: Shape = CircleShape): Modifier =
    shadow(radius, shape, ambientColor = color, spotColor = color)

/** Tasarımdaki özel simgeler (Material'de karşılığı yok). */
object GlassIcons {
    private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
            .build()

    /** Hoparlör + çarpı (sessiz). */
    val Mute = icon(
        "Mute",
        "M3,9v6h4l5,5V4L7,9H3zM10,8.83v6.34L7.83,13H5v-2h2.83L10,8.83z" +
            "M16.41,9L15,10.41 16.59,12 15,13.59 16.41,15 18,13.41 19.59,15 21,13.59 19.41,12 21,10.41 19.59,9 18,10.59z"
    )

    /** Rehber: antenli küçük TV + sağında satırlar. */
    val Guide = icon(
        "Guide",
        "M3,8.5h8.5c0.83,0 1.5,0.67 1.5,1.5v7.5c0,0.83 -0.67,1.5 -1.5,1.5H3c-0.83,0 -1.5,-0.67 -1.5,-1.5V10c0,-0.83 0.67,-1.5 1.5,-1.5z" +
            "M3.3,10.3v7h7.9v-7z" +
            "M4.6,4.4l1.2,-0.7 1.9,3.3 1.9,-3.3 1.2,0.7 -2.4,4.1h-1.4z" +
            "M15,8.5h7.5v1.8H15z" +
            "M15,11.4h5.5v1.8H15z" +
            "M15,14.3h7.5v1.8H15z" +
            "M15,17.2h5.5v1.8H15z",
        evenOdd = true
    )
}
