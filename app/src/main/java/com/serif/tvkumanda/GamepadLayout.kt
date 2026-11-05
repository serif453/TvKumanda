package com.serif.tvkumanda

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Oyun kolu: tuş tanımları, kullanıcının düzeni (yer, boyut, görünürlük) ve TV'ye giden sanal kol satırları.
 * Android'e bağlı değil; birim testleri bu dosyayı doğrudan dener.
 */
enum class PadKind { BUTTON, STICK, DPAD }

/**
 * Bir tuşun sabit tanımı. [w]/[h] dp cinsinden %100 boyut. [bit]: ADB'deki sanal kolda düğme biti (0 = yok,
 * o tuş her iki bağlantıda da [key] olarak gider). [key]: Standart bağlantıda (ya da sanal kol yokken) gönderilen tuş kodu.
 */
data class PadDef(
    val id: String, val label: String, val kind: PadKind,
    val w: Int, val h: Int, val round: Boolean,
    val bit: Int = 0, val key: Int = 0,
    val x: Float, val y: Float
)

/** Kullanıcının bir tuş için kaydettiği düzen: merkez (ekranın oranı, 0..1), boyut çarpanı ve görünürlük. */
data class PadItem(val id: String, val x: Float, val y: Float, val scale: Float = 1f, val visible: Boolean = true)

object GamepadLayout {
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 1.6f
    const val STEP = 0.1f

    // HID düğme sırası Linux'ta BTN_A, B, C, X, Y, Z, TL, TR, TL2, TR2, SELECT, START, MODE, THUMBL, THUMBR'a karşılık gelir
    private fun btn(n: Int) = 1 shl (n - 1)

    /** Varsayılan düzen yatay telefon ekranına göre (örnek: 800 × 380 dp). */
    val defs: List<PadDef> = listOf(
        PadDef("L2", "L2", PadKind.BUTTON, 84, 38, false, btn(9), 104, 0.08f, 0.11f),
        PadDef("L1", "L1", PadKind.BUTTON, 84, 38, false, btn(7), 102, 0.21f, 0.11f),
        PadDef("R1", "R1", PadKind.BUTTON, 84, 38, false, btn(8), 103, 0.79f, 0.11f),
        PadDef("R2", "R2", PadKind.BUTTON, 84, 38, false, btn(10), 105, 0.92f, 0.11f),
        PadDef("LS", "Sol çubuk", PadKind.STICK, 130, 130, true, x = 0.14f, y = 0.45f),
        PadDef("DPAD", "Yön tuşları", PadKind.DPAD, 132, 132, true, x = 0.31f, y = 0.77f),
        PadDef("SELECT", "Select", PadKind.BUTTON, 58, 34, false, btn(11), 109, 0.39f, 0.52f),
        PadDef("HOME", "Ana Sayfa", PadKind.BUTTON, 50, 50, true, 0, 3, 0.5f, 0.52f),
        PadDef("START", "Start", PadKind.BUTTON, 58, 34, false, btn(12), 108, 0.61f, 0.52f),
        PadDef("Y", "Y", PadKind.BUTTON, 60, 60, true, btn(5), 100, 0.85f, 0.31f),
        PadDef("X", "X", PadKind.BUTTON, 60, 60, true, btn(4), 99, 0.77f, 0.49f),
        PadDef("B", "B", PadKind.BUTTON, 60, 60, true, btn(2), 97, 0.93f, 0.49f),
        PadDef("A", "A", PadKind.BUTTON, 60, 60, true, btn(1), 96, 0.85f, 0.67f),
        PadDef("RS", "Sağ çubuk", PadKind.STICK, 112, 112, true, x = 0.67f, y = 0.79f),
    )

    private val byId = defs.associateBy { it.id }
    fun def(id: String): PadDef? = byId[id]

    fun defaults(): List<PadItem> = defs.map { PadItem(it.id, it.x, it.y) }

    /** "id,x,y,ölçek,görünür;…" — ondalık ayırıcı her zaman nokta. */
    fun encode(items: List<PadItem>): String =
        items.joinToString(";") { "${it.id},${it.x},${it.y},${it.scale},${if (it.visible) 1 else 0}" }

    /** Bozuk ya da eski kayıt varsayılanla tamamlanır; bilinmeyen tuşlar atılır, sıra tanımlarınkidir. */
    fun decode(raw: String?): List<PadItem> {
        val saved = HashMap<String, PadItem>()
        raw.orEmpty().split(';').forEach { part ->
            val p = part.split(',')
            if (p.size != 5 || def(p[0]) == null) return@forEach
            val x = p[1].toFloatOrNull() ?: return@forEach
            val y = p[2].toFloatOrNull() ?: return@forEach
            val s = p[3].toFloatOrNull() ?: return@forEach
            saved[p[0]] = PadItem(p[0], x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), clampScale(s), p[4] != "0")
        }
        return defs.map { saved[it.id] ?: PadItem(it.id, it.x, it.y) }
    }

    fun clampScale(s: Float): Float = ((s.coerceIn(MIN_SCALE, MAX_SCALE) * 10f).roundToInt() / 10f)
}

/** Yön tuşu / çubuk yönleri bit olarak. */
object PadDir {
    const val UP = 1; const val RIGHT = 2; const val DOWN = 4; const val LEFT = 8

    /**
     * Merkeze göre (x, y: −1..1, y aşağı doğru artar) 8 yönden biri; ölü bölgenin içindeyse 0.
     */
    fun of(x: Float, y: Float, dead: Float): Int {
        if (hypot(x, y) < dead) return 0
        val sector = Math.floorMod((atan2(y, x) / (Math.PI / 4)).roundToInt(), 8)
        return when (sector) {
            0 -> RIGHT; 1 -> RIGHT or DOWN; 2 -> DOWN; 3 -> DOWN or LEFT
            4 -> LEFT; 5 -> LEFT or UP; 6 -> UP; else -> UP or RIGHT
        }
    }

    /** HID şapka değeri: 0 = yukarı, saat yönünde 7'ye kadar; 8 = basılı değil. */
    fun hat(mask: Int): Int = when (mask) {
        UP -> 0; UP or RIGHT -> 1; RIGHT -> 2; RIGHT or DOWN -> 3
        DOWN -> 4; DOWN or LEFT -> 5; LEFT -> 6; LEFT or UP -> 7
        else -> 8
    }

    /** Tuş olarak gönderirken tek yön: çaprazda baskın eksen (yatay öncelikli değil, büyük olan). */
    fun key(x: Float, y: Float, dead: Float): Int? {
        if (hypot(x, y) < dead) return null
        return if (abs(x) > abs(y)) { if (x > 0) 22 else 21 } else { if (y > 0) 20 else 19 }
    }

    /** Yön tuşu bitlerinden tek tuş kodu (çaprazda yukarı/aşağı önce). */
    fun keyOf(mask: Int): Int? = when {
        mask and UP != 0 -> 19
        mask and DOWN != 0 -> 20
        mask and LEFT != 0 -> 21
        mask and RIGHT != 0 -> 22
        else -> null
    }
}

/** ADB'deki sanal oyun kolunun anlık durumu; [line] TV'deki tuş sunucusuna giden satırdır. */
data class PadHidState(
    val buttons: Int = 0, val dpad: Int = 0,
    val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f
) {
    fun line(): String = "g $buttons ${PadDir.hat(dpad)} ${axis(lx)} ${axis(ly)} ${axis(rx)} ${axis(ry)}"

    companion object {
        const val RELEASE_ALL = "g 0 8 0 0 0 0"
        fun axis(v: Float): Int = (v * 127f).roundToInt().coerceIn(-127, 127)
    }
}
