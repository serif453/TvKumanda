package com.serif.tvkumanda

import java.util.ArrayDeque

/**
 * Fare komutlarının TV'ye gidiş sırası (saf kod; `MouseQueueTest`).
 *
 * Neden (7 Kasım 2026, Serif: "Homatics'te senkron hatası, Philips'te de ara ara takılıyor"):
 * ADB hızlı kanalında her mesajdan sonra TV'nin "aldım" (OKAY) demesi beklenir. Parmak saniyede 100'den fazla
 * küçük hareket üretir; TV ya da Wi-Fi biraz geç cevap verince mesajlar telefonda sıraya giriyor, imleç
 * geride kalıyor ve parmak kalktıktan sonra da yürümeye devam ediyordu.
 *
 * Çözüm: sıranın sonundaki hareket henüz gönderilmediyse yeni hareket ona eklenir ("m 3 1" + "m 2 0" = "m 5 1").
 * Aynısı tekerlek için ("w"). Böylece TV hızlıysa her hareket ayrı gider (Philips'te değişen bir şey yok),
 * yavaşsa birikenler tek mesajda gider: kuyruk oluşmaz, toplam mesafe kaybolmaz. Tık, basılı tut, bırak
 * gibi komutlar birleştirilmez ve sırası hiç değişmez (önceki hareket her zaman önce gider).
 */
class MouseQueue {
    private sealed class Item {
        class Move(var dx: Int, var dy: Int) : Item()
        class Wheel(var n: Int) : Item()
        class Line(val text: String) : Item()
    }

    private val items = ArrayDeque<Item>()
    private val lock = Object()

    /** Komutu sıraya koyar; beklemez (arayüz iş parçacığından çağrılabilir). */
    fun offer(line: String) {
        val item = parse(line) ?: return
        synchronized(lock) {
            val tail = items.peekLast()
            when {
                item is Item.Move && tail is Item.Move -> {
                    tail.dx += item.dx; tail.dy += item.dy
                    if (tail.dx == 0 && tail.dy == 0) items.pollLast()
                }
                item is Item.Wheel && tail is Item.Wheel -> {
                    tail.n += item.n
                    if (tail.n == 0) items.pollLast()
                }
                else -> items.addLast(item)
            }
            lock.notifyAll()
        }
    }

    /** Sıradaki komutu döndürür; boşsa gelene kadar bekler. */
    @Throws(InterruptedException::class)
    fun take(): String = synchronized(lock) {
        while (items.isEmpty()) lock.wait()
        format(items.pollFirst()!!)
    }

    /** Sıradaki komut (yoksa null, beklemez). */
    fun poll(): String? = synchronized(lock) { items.pollFirst()?.let(::format) }

    /** Bağlantı kapanınca bekleyenler atılır (yeni TV'ye eski hareket gitmesin). */
    fun clear() = synchronized(lock) { items.clear() }

    val size: Int get() = synchronized(lock) { items.size }

    private fun parse(line: String): Item? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("m ")) {
            val p = t.split(' ')
            val dx = p.getOrNull(1)?.toIntOrNull()
            val dy = p.getOrNull(2)?.toIntOrNull()
            if (dx == null || dy == null) return null
            if (dx == 0 && dy == 0) return null
            return Item.Move(dx, dy)
        }
        if (t.startsWith("w ")) {
            val n = t.substring(2).trim().toIntOrNull() ?: return null
            if (n == 0) return null
            return Item.Wheel(n)
        }
        return Item.Line(t)
    }

    private fun format(i: Item): String = when (i) {
        is Item.Move -> "m ${i.dx} ${i.dy}"
        is Item.Wheel -> "w ${i.n}"
        is Item.Line -> i.text
    }
}
