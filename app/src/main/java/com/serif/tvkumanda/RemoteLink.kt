package com.serif.tvkumanda

/**
 * Kumanda ekranlarının kullandığı ortak bağlantı arayüzü.
 * İki bağımsız uygulaması var:
 *  - [TvRemote]  : Gelişmiş bağlantı (ADB)
 *  - [AtvRemote] : Standart bağlantı (Android TV Remote v2, ADB'siz)
 * Biri çalışmazsa diğerine otomatik geçilmez; yöntemi kullanıcı seçer.
 * Tüm fonksiyonlar bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
interface RemoteLink {
    /** TV'ye bağlanır ve görüntülenecek cihaz adını döndürür. */
    fun connectTo(ip: String): String
    fun key(code: Int)
    fun text(raw: String)
    fun mouse(line: String)
    fun launch(app: TvApp)
    fun listApps(): List<TvApp>
    /** (değer, en yüksek) ya da bilinmiyorsa null. */
    fun volume(): Pair<Int, Int>?
    /** ADB'de TV'deki tuş sunucusunun (fare dahil) çalıştığını belirtir. */
    val isFast: Boolean
    fun close()
}

enum class ConnMethod { STANDARD, ADB }
