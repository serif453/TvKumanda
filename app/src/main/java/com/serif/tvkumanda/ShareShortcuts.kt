package com.serif.tvkumanda

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Paylaş menüsünün üst satırındaki iki doğrudan kısayol: "TV'de oynat" (fotoğraf, video, müzik → Cast) ve
 * "TV'ye gönder" (her dosya → TV'nin Download klasörü). Serif'in isteği, 2 Kasım 2026: Honor'un paylaşım menüsü
 * aynı uygulamanın iki seçeneğini tek simgede topluyor; bu kısayollar telefon gösterirse tek dokunuşla ayrı ayrı
 * seçilir. Telefon göstermezse hiçbir şey değişmez (normal paylaşım seçenekleri yerinde).
 * Hangi dosya türünde hangisinin çıkacağı `res/xml/share_shortcuts.xml`'de. Android 11 ve üstü.
 */
object ShareShortcuts {
    const val PLAY = "paylas_tvde_oynat"
    const val SEND = "paylas_tvye_gonder"
    private const val CAT_PLAY = "com.serif.tvkumanda.category.TV_OYNAT"
    private const val CAT_SEND = "com.serif.tvkumanda.category.TV_GONDER"

    /** Paylaşım bu kısayollardan birinden geldiyse kimliği (yoksa null). */
    fun idOf(intent: Intent): String? = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)

    fun publish(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val sm = c.getSystemService(ShortcutManager::class.java) ?: return
            fun make(id: String, label: String, category: String): ShortcutInfo {
                val b = ShortcutInfo.Builder(c, id)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher))
                    .setCategories(setOf(category))
                    .setLongLived(true)
                    .setIntent(Intent(Intent.ACTION_VIEW, null, c, MainActivity::class.java))
                // Uygulama simgesine basılı tutunca çıkan menüde görünmesin (yalnızca paylaşım menüsü için)
                if (Build.VERSION.SDK_INT >= 33) b.setExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER)
                return b.build()
            }
            val list = listOf(
                make(PLAY, "TV'de oynat", CAT_PLAY),
                make(SEND, "TV'ye gönder", CAT_SEND)
            )
            // Zaten kayıtlıysa yeniden yazmaya gerek yok (her açılışta sistemle uğraşmasın)
            val have = sm.dynamicShortcuts.map { it.id }.toSet()
            if (!have.containsAll(list.map { it.id })) sm.setDynamicShortcuts(list)
        }.onFailure { DiagLog.log("Paylaşım kısayolları kaydedilemedi: ${it.message.orEmpty()}") }
    }
}
