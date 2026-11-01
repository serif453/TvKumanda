# Cast sekmesi — ayrıntılar

Chromecast, DLNA ve telefonu DLNA sunucusu yapma üzerinde çalışırken okunur.

- Chromecast: `CastClient.kt` (Cast v2, Default Media Receiver `CC1AD845`), dosyalar `MediaServer.kt` (rastgele
  adresli küçük HTTP sunucu, Range destekli) üzerinden sunulur.
- DLNA oynatıcıya gönderme: `DlnaClient.kt`. Telefonu DLNA sunucusu yapma: `DlnaMediaServer.kt`, `DlnaServerService.kt`.
- `CastService.kt`: ekran kapalıyken devam, bildirim ve kilit ekranı kontrolleri.
- `Subtitles.kt`: SRT → WebVTT; geçersiz UTF-8 ise Windows-1254 okunur.
- Chromecast MKV/AVI'yi genelde oynatamaz; kullanıcı uyarılır.
- **Cihaz seçimi (1 Kasım 2026, Serif'in isteği):** Cast sekmesi açılınca **kendiliğinden arama ya da bağlantı yok**
  (`castWarmup` boş). Chromecast ve DLNA seçicileri başta "Cihaz seç"; seçicide "Ara" → bulunanlar listelenir → dokununca
  seçilir (Chromecast'te hemen bağlanır). Seçim hatırlanır, bağlanmadan geri yüklenir; ilk gönderimde bağlanılır.
  Kayıt: `cc_secim` = "tv" (bağlı TV'yi izler) ya da `ad\u0001ip`; `dlna_secim` = ad, ip, location, avControl, rcControl
  (`\u0001` ile). Eski `cc_cihaz` / `dlna_cihaz` anahtarları artık okunmuyor. Hatırlanan cihaz aramada bulunmasa da listede ✓ ile
  görünür; adı aynı cihaz yeni adresle bulunursa kayıt güncellenir. Chromecast: `CcUi.selected` başka cihaz, `useTv` bağlı TV
  (TV bağlantısı gerekir). Aramada TV'nin kendisi "bağlı TV" diye işaretlenir. Arama 20 sn sürer, sonra durur (pil). Serif'in
  ağında başka Chromecast yok; başka cihazda denenmedi.
