# Kumanda sekmesi — ayrıntılar

Kumanda sekmesi, cam tasarım, ses çubuğu ve TV seçimi üzerinde çalışırken okunur.

- Kumanda: `RemoteUi.kt`, `RemoteLayout.kt` (üst çubuk, uygulama satırı). Modlar: yön tuşları, kaydırma, fare (ADB).
  Yön kartının üstünde kapsül ses çubuğu (`GlassVolumePill`: Sessiz · − · çubuk · +; Serif'in örneğinden). Dokun/sürükle-bırak →
  `VolumeCtl.setTo` → `MainActivity.setVolumeTo`: ADB'de `TvRemote.setVolume` (media_session --set, geri okunarak doğrulanır),
  olmazsa ya da Standart'ta fark kadar ses tuşu. Sessiz durumu (`UiState.muted`, bilinmiyorsa null, tahmin yok): Standart'ta ses mesajının 8. alanı (volume_muted), ADB'de `dumpsys audio` STREAM_MUSIC "Muted" / master mute (`parseMuted`; Philips'te doğru yazıp yazmadığı denenecek). Sessizken tuş kırmızı parlar, çubuk griye döner. Sessiz tuşu kartın sol kenarında (dolgu 0; simge kenardan ~9 dp), çubuğun iki yanında 8.5 dp boşluk (Serif'in isteği). Sessiz tuşuna basınca anında yanar/söner, ses tuşları söndürür; ADB okuması yalnızca "sessiz" derse dikkate alınır.
  - Uygulama satırını düzenleme (`EditAppsSheet`): basılı tutup sürükleyerek sıralama (oklar kaldırıldı, Serif'in isteği); kenara gelince liste kendiliğinden kayar, sıra bırakınca kaydedilir.
  - **Cam tasarım (1 Kasım 2026, Serif'in gönderdiği görselin birebiri, onayıyla):** `GlassUi.kt` (renkler `Glass`, arka plan
    `glassBackdrop`, cam yüzey `Modifier.glass`, parlama `glow`, özel simgeler `GlassIcons`: sessiz = hoparlör+çarpı, Rehber = TV+satırlar)
    ve `GlassRemote.kt` (`GlassTopBar`, `GlassAppsEmpty`, `GlassPadCard`, büyük ses çubuğu, `GlassMediaBar`). Gerçek bulanıklık yok:
    arka plan düz geçiş, yarı saydam katman aynı görünümü veriyor. Yazı tipi Inter (OFL, `res/font`, pyftsubset ile Latin + Türkçe
    alt kümesi, ~57 KB/ağırlık) yalnızca Kumanda sekmesinde (`GlassText`). Yan sütunlar: sol Kaynak · Bilgi (165) · Kaydırma · fn ·
    Klavye; sağ 123 · Rehber (172) · Fare · Medya · Ayarlar. **fn = Araçlar'daki "Sistem kısayolları", 🎵 = "Medya tuşları"
    penceresi** (aynı `ToolSheet`). Medya çubuğu ile alt menü arasında bir oynatıcı çubuğu kadar boşluk (ekran ≥ 780 dp: 80 dp,
    ≥ 720: 40, daha kısa ya da TV'de bir şey çalarken 0). Alt menüye ve diğer sekmelere dokunulmadı (Serif'in isteği).
    v0.121 düzeltmeleri (Serif): alt sıra Geri · Ana Sayfa · Mikrofon arası 9 dp (yarıya indi); ses çubuğu −'den +'ya kadar
    uzar; TV adı kutusunda iç boşluk, ad sığmazsa "…"; üstteki mor ışık keskin kenarsız; alttaki şerit `glassSwoosh` ile
    boşluğun içine çizilir (oynatıcı çubuğuna binmesin).
    Oynatıcı çubuğunda (`NowPlayingBar`, TV'de bir şey çalarken) Önceki/Oynat/Sonraki tuşları yok: alttaki medya çubuğunda
    zaten var (Serif'in isteği, 1 Kasım 2026). v0.126: oynatıcı ayrı kart değil, medya çubuğuyla **tek cam kart**
    (`GlassMediaBar(header = NowPlayingContent, leading = NowPlayingIcon)`): üstte tek satır başlık ("…", üst boşluk kısa),
    altında en soldan sarma çubuğu + sağda "6:57 / 18:23", en altta uygulama simgesi (32 dp, ⏮'nin solunda; Serif'in isteği) +
    medya tuşları. Kanal adı yok; başlık yoksa uygulama adı. Video açıkken kumanda kartıyla arası 10 dp (kart uzar).
- TV seçimi: `TvSheet.kt`; bağlıyken açılınca ağda arama yapılmaz, "Tekrar ara" ile elle aranır (Serif'in isteği).
- Fare: TV'deki sanal fare (`KeyServer.ensureMouse`, /dev/uhid) fare modu açılınca "M" komutuyla önceden kurulur; eskiden ilk harekette kuruluyordu ve TV tanıyana kadar 1-2 sn hareket kayboluyordu (Serif: "fare moduna ilk geçince geç bağlanıyor").
