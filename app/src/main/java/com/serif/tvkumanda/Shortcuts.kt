package com.serif.tvkumanda

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kısayollar: sırayla çalışan adımlardan oluşan tek dokunuşluk işler ("Uyan → 3 sn bekle → Netflix aç").
 * Bu dosya saf koddur (arayüz yok): model, kayıt (JSON), hazır şablonlar ve adım motoru. Motor TV'ye [ShortcutHost]
 * üzerinden konuşur; böylece test edilebilir.
 */
sealed class Step {
    /** [times] kez tuşa basar (ses artırma gibi). */
    data class Key(val code: Int, val times: Int = 1) : Step()
    data class Launch(val pkg: String, val activity: String, val name: String) : Step()
    data class Text(val value: String) : Step()
    data class Wait(val millis: Long) : Step()
    /** Kayıtlı TV'ye Wi-Fi ile uyandırma sinyali. */
    object Wake : Step()
    /** Sesi bu seviyeye getirir (ADB'de doğrudan, Standart'ta tuşlarla). */
    data class Volume(val level: Int) : Step()
    /** [minutes] dakika sonra TV'yi uyutan zamanlayıcıyı kurar (ayarlar için bkz. Uyku zamanlayıcısı aracı). */
    data class Sleep(val minutes: Int) : Step()
    /** Kanal numarası: rakam tuşları ve ardından OK/Enter. */
    data class Channel(val number: String) : Step()

    /** Yalnızca ADB bağlantısında çalışan adım mı? Şu an hepsi iki yöntemde de çalışır; ileride yeni adımlar için ayrıldı. */
    val needsAdb: Boolean get() = false

    /** Listede görünen kısa açıklama. */
    val label: String
        get() = when (this) {
            is Key -> (ShortcutKeys.nameOf(code)) + if (times > 1) " × $times" else ""
            is Launch -> "$name uygulamasını aç"
            is Text -> "Yaz: ${value.take(24)}"
            is Wait -> "Bekle ${millis / 1000.0} sn".replace(".0 sn", " sn")
            Wake -> "TV'yi uyandır"
            is Volume -> "Sesi $level yap"
            is Sleep -> "$minutes dk sonra uyut"
            is Channel -> "Kanal $number"
        }
}

/** Kısayol düzenleyicisinde seçilebilen tuşlar. */
object ShortcutKeys {
    val all: List<Pair<Int, String>> = listOf(
        Keys.HOME to "Ana ekran", Keys.BACK to "Geri", Keys.OK to "OK", Keys.MENU to "Menü",
        Keys.UP to "Yukarı", Keys.DOWN to "Aşağı", Keys.LEFT to "Sol", Keys.RIGHT to "Sağ",
        Keys.VOL_UP to "Ses +", Keys.VOL_DOWN to "Ses −", Keys.MUTE to "Sessiz",
        Keys.CH_UP to "Kanal +", Keys.CH_DOWN to "Kanal −", Keys.INPUT to "Giriş kaynağı",
        Keys.PLAY_PAUSE to "Oynat/Duraklat", Keys.POWER to "Güç"
    )

    fun nameOf(code: Int) = all.firstOrNull { it.first == code }?.second ?: "Tuş $code"
}

data class Shortcut(val id: String, val name: String, val icon: String, val steps: List<Step>) {
    /** Kısa alt başlık: adım sayısı. */
    val detail: String get() = if (steps.size == 1) steps[0].label else "${steps.size} adım"
}

data class FavChannel(val number: String, val name: String)

/** Kısayolun düzenleyicide seçilebilen simgeleri (ad → arayüzdeki simge ShortcutsScreen'de). */
object ShortcutIcons {
    val names = listOf("power", "movie", "volume", "bed", "search", "home", "play", "star", "tv", "music")
}

object ShortcutCodec {
    fun encodeStep(s: Step): JSONObject = JSONObject().apply {
        when (s) {
            is Step.Key -> put("t", "key").put("code", s.code).put("n", s.times)
            is Step.Launch -> put("t", "launch").put("pkg", s.pkg).put("act", s.activity).put("name", s.name)
            is Step.Text -> put("t", "text").put("v", s.value)
            is Step.Wait -> put("t", "wait").put("ms", s.millis)
            Step.Wake -> put("t", "wake")
            is Step.Volume -> put("t", "vol").put("v", s.level)
            is Step.Sleep -> put("t", "sleep").put("min", s.minutes)
            is Step.Channel -> put("t", "ch").put("no", s.number)
        }
    }

    /** Bozuk ya da bilinmeyen adım null döner (eski/yeni sürüm uyumu için atlanır). */
    fun decodeStep(o: JSONObject): Step? = runCatching {
        when (o.getString("t")) {
            "key" -> Step.Key(o.getInt("code"), o.optInt("n", 1).coerceIn(1, 50))
            "launch" -> Step.Launch(o.getString("pkg"), o.optString("act", ""), o.optString("name", o.getString("pkg")))
            "text" -> Step.Text(o.getString("v"))
            "wait" -> Step.Wait(o.getLong("ms").coerceIn(0, 60_000))
            "wake" -> Step.Wake
            "vol" -> Step.Volume(o.getInt("v").coerceIn(0, 100))
            "sleep" -> Step.Sleep(o.getInt("min").coerceIn(1, 600))
            "ch" -> Step.Channel(o.getString("no").filter { it.isDigit() }.take(5).ifEmpty { error("boş") })
            else -> null
        }
    }.getOrNull()

    fun encode(list: List<Shortcut>): String = JSONArray().apply {
        list.forEach { sc ->
            put(JSONObject().put("id", sc.id).put("name", sc.name).put("icon", sc.icon)
                .put("steps", JSONArray().apply { sc.steps.forEach { put(encodeStep(it)) } }))
        }
    }.toString()

    fun decode(json: String?): List<Shortcut> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i)
                    val steps = o.getJSONArray("steps")
                    Shortcut(
                        o.getString("id"), o.getString("name"), o.optString("icon", "star"),
                        (0 until steps.length()).mapNotNull { decodeStep(steps.getJSONObject(it)) }
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun encodeChannels(list: List<FavChannel>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("no", it.number).put("name", it.name)) }
    }.toString()

    fun decodeChannels(json: String?): List<FavChannel> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i)
                    val no = o.getString("no").filter { it.isDigit() }
                    if (no.isEmpty()) null else FavChannel(no.take(5), o.optString("name", ""))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}

/** Kayıtlı kısayollar ve favori kanallar (telefonda, SharedPreferences). İlk açılışta şablonlar önerilir. */
object ShortcutStore {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("kisayollar", Context.MODE_PRIVATE)

    fun load(c: Context): List<Shortcut> = ShortcutCodec.decode(prefs(c).getString("liste", null))
    fun save(c: Context, list: List<Shortcut>) { prefs(c).edit().putString("liste", ShortcutCodec.encode(list)).apply() }

    fun loadChannels(c: Context): List<FavChannel> = ShortcutCodec.decodeChannels(prefs(c).getString("kanallar", null))
    fun saveChannels(c: Context, list: List<FavChannel>) {
        prefs(c).edit().putString("kanallar", ShortcutCodec.encodeChannels(list)).apply()
    }

    /** Şablonlar bir kez önerilir; kullanıcı hepsini silse de geri gelmez (ayrıca "Şablonlar" menüsünden eklenir). */
    fun seededAlready(c: Context) = prefs(c).getBoolean("sablon_eklendi", false)
    fun markSeeded(c: Context) { prefs(c).edit().putBoolean("sablon_eklendi", true).apply() }

    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)
}

object ShortcutTemplates {
    /** Hazır şablonlar; her çağrıda yeni kimliklerle üretilir. */
    fun all(): List<Shortcut> = listOf(
        Shortcut(ShortcutStore.newId(), "TV'yi uyandır", "power", listOf(Step.Wake)),
        Shortcut(ShortcutStore.newId(), "Sesi 10'a getir", "volume", listOf(Step.Volume(10))),
        Shortcut(ShortcutStore.newId(), "Gece modu", "bed", listOf(Step.Volume(8), Step.Sleep(30))),
        Shortcut(ShortcutStore.newId(), "30 dk sonra uyut", "bed", listOf(Step.Sleep(30))),
        Shortcut(ShortcutStore.newId(), "Ana ekrana dön", "home", listOf(Step.Key(Keys.HOME)))
    )
}

/** Adım motorunun TV ile konuştuğu yüz. Hata olursa istisna fırlatır; motor orada durur. */
interface ShortcutHost {
    fun key(code: Int)
    fun launch(pkg: String, activity: String, name: String)
    fun text(value: String)
    fun wake()
    fun setVolume(level: Int)
    fun sleepAfter(minutes: Int)
}

sealed class RunResult {
    object Done : RunResult()
    /** [index] hatalı adımın sırası (0'dan). */
    data class Failed(val index: Int, val step: Step, val reason: String) : RunResult()
}

object ShortcutRunner {
    /** Tuşlar arası kısa ara: TV art arda gelen tuşları kaçırmasın. */
    const val KEY_GAP_MS = 120L

    suspend fun run(
        steps: List<Step>,
        host: ShortcutHost,
        pause: suspend (Long) -> Unit = { delay(it) },
        onStep: (Int) -> Unit = {}
    ): RunResult {
        steps.forEachIndexed { i, step ->
            onStep(i)
            try {
                when (step) {
                    is Step.Key -> repeat(step.times) { n ->
                        host.key(step.code)
                        if (n < step.times - 1) pause(KEY_GAP_MS)
                    }
                    is Step.Launch -> host.launch(step.pkg, step.activity, step.name)
                    is Step.Text -> host.text(step.value)
                    is Step.Wait -> pause(step.millis)
                    Step.Wake -> host.wake()
                    is Step.Volume -> host.setVolume(step.level)
                    is Step.Sleep -> host.sleepAfter(step.minutes)
                    is Step.Channel -> {
                        step.number.forEach { host.key(Keys.digit(it - '0')); pause(KEY_GAP_MS) }
                        host.key(Keys.OK)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return RunResult.Failed(i, step, e.message ?: e.javaClass.simpleName)
            }
            // Adımlar arası kısa ara (bekleme adımının kendisi hariç)
            if (i < steps.lastIndex && step !is Step.Wait) pause(KEY_GAP_MS)
        }
        return RunResult.Done
    }
}
