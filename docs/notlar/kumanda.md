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
- **TV paneli (1 Kasım 2026, Serif'in isteği ve onayı):** TV adı kutusuna (pil) dokununca alttan pencere değil, pilin hemen
  altından aşağı açılan küçük cam panel (`TvPicker.kt`, `TvPickerPanel`). Genişlik ayarlar ve güç düğmelerinin iç kenarları
  arası (yan boşluk 68 dp), boy içeriğe göre (en fazla ekranın %55'i), arkası kararır; dışarı dokununca ya da geri tuşuyla kapanır.
  İçerik: kayıtlı TV'ler (dokununca kendi kayıtlı bağlantı türüyle geçer; kapalıysa "Uyandır"), bağlı TV'de ADB | Standart
  seçici, IR kumandalar, en altta "TV ekle / yönet" → büyük `TvSheet`. Kayıtlı TV yoksa pil doğrudan büyük pencereyi açar;
  bağlantı hatasında da büyük pencere açılır (eskisi gibi). Büyük pencere ve IR kartları da cam renklere geçti (`GlassSheet`, Inter).
- Fare: TV'deki sanal fare (`KeyServer.ensureMouse`, /dev/uhid) fare modu açılınca "M" komutuyla önceden kurulur; eskiden ilk harekette kuruluyordu ve TV tanıyana kadar 1-2 sn hareket kayboluyordu (Serif: "fare moduna ilk geçince geç bağlanıyor").
- **Geniş yüzey (1 Kasım 2026, Serif'in seçimi "B"):** Kaydırma ya da Fare modunda üstteki 8 yan tuş (Kaynak, Bilgi, Kaydırma, fn ·
  123, Rehber, Fare, Medya) ~250 ms'de solar, yüzey kartın iki kenarına kadar genişler (yükseklik = 4 yan tuşun alanı); kartın
  boyu değişmez, alt sıra (Klavye · Geri · Ana Sayfa · Mikrofon · Ayarlar) yerinde kalır. Sol üstte mod etiketi, sağ üstte ✕
  (normal kumandaya döner). Üstte "Yön · Kaydırma · Fare" anahtarı (seçenek A) Serif tarafından seçilmedi. Fare modundaki
  "ekran + el" simgesi kaldırıldı (Kaydırma tuşununkine benziyordu). Yüzeyde kenar şeridi yok (`EdgeZone = 0`).
  v0.133 düzeltmesi (Serif): yüzey alt sıranın altına giriyordu; artık Klavye tuşunun ölçülen yerinden 14 dp önce biter
  (ses çubuğu ile yüzey arası da 14 dp). Genişlik yan tuşların dış kenarlarıyla hizalı. Fare yüzeyi bilerek boş (Serif'in isteği).
- **Fare yüzeyi (1 Kasım 2026, Serif'in isteği; örnek: başka bir kumanda uygulamasının Air Mouse ekranı):**
  `TrackPad` (`RemoteUi.kt`) + `MousePad.kt`. Kenarda kaydırma şeridi (`ScrollStrip`: ▲ / çizgili sürükleme alanı / ▼ / ⇆),
  ▲▼ basılı tutunca tekrarlar, yön iki parmakla kaydırmayla aynı. Şerit varsayılan sağda, ⇆ ya da ayar penceresiyle sola alınır.
  Sol üstte "Fare ▾" → ayar penceresi: imleç hızı 5 kademe (3 = eski hız, çarpanlar 0.5/0.75/1/1.4/1.9) + şerit Sol/Sağ;
  "ayarlar" tercihlerinde `fare_hiz`, `fare_serit_sol`. Parmağın altında yarı saydam mavi halka (Glass.Blue); sürüklerken büyür.
  Basılı tut (~0.4 sn kıpırdamadan) + hareket = sürükleme/seçme: TV'ye "d" (sol tuş basılı) … "u" (bırak) gider
  (`KeyServer.mouseButtons`); hareket ve tekerlek basılı tuşla gönderilir. TV'deki ok imlecinin görünümü değiştirilmedi
  (Android'in kendi imleci).
  v0.135 (Serif'in onayladığı önizleme): şerit yüzeyin üstünden altına koyu tek parça (48 dp); en üstte ✕ (çalışan fare
  modunda sağ üstteki ayrı ✕ yok), sonra ▲, eşit aralıklı çizgiler (sayı boya göre), ▼. ⇆ kaldırıldı (Sol/Sağ yalnızca
  "Fare ▾" penceresinde; Serif üstünü çizdi). Ayraç çizgisi istenmedi. "Fare ▾" hafif kapsül; şerit soldayken sağ üste geçer.
- **Hız (1 Kasım 2026, Serif'in onayıyla):** "Şu an çalan" sorgusu tuş sırasından (`MainActivity.io`) çıkarıldı, ayrı yoldan
  (Dispatchers.IO) gider ve yalnızca Kumanda sekmesi açıkken sorulur; hızlı kanala yazma `TvRemote.writeLock` ile tek kapıdan.
  Sık değişen değerler (`LiveUi`: şu an çalan, mikrofon seviyesi) `UiState`'te değil: değişince yalnızca oynatıcı çubuğu /
  mikrofon tuşu çizilir; aynı medya ve tutarlı konum gelirse eski nesne korunur (`sameAs`). Eski tasarımdan kalan ~770 satır
  kullanılmayan kod silindi. Mor arka plan tek kat çizilir.
- `RemoteUi.kt` ikiye bölündü (1 Kasım 2026): `RemoteUi.kt` = tema, `RemoteScreen` iskeleti, eşleştirme penceresi, ortak
  yardımcılar (`pressable`, `pressScale`); `RemoteControls.kt` = yön tuşları, `TouchPad`/`TrackPad`, yazma ekranı, klavye ve
  rakam pencereleri. Davranış değişmedi.
- Sessiz okuma (ADB, 1 Kasım 2026): `dumpsys audio`nun tamamı yerine TV'de `sed` ile süzülen satırlar (`MUTE_FILTER_COMMAND`:
  ana sessiz satırı + STREAM_MUSIC bölümünden "Muted:"a kadar). Süzme sonuç vermezse bir kez fark edilir, sonra hep tam döküm.
- **Ayarlar sayfası (1 Kasım 2026, Serif'in onayıyla; tam sayfa istedi):** sol üstteki ⚙️ → `SettingsPage` (`AppSettings.kt`),
  DLNA Sunucu sayfasıyla aynı düz tarz (cila sonra). `AppSettings` "ayarlar" tercihlerinde: `titresim` (kumanda tuşlarında
  `EFFECT_CLICK`, varsayılan kapalı; telefonun dokunma titreşimi ayarından bağımsız, VIBRATE izni; telefonun ses tuşlarında
  titremez), `ses_tuslari` (varsayılan açık), `ekran_acik` (yalnızca Kumanda sekmesindeyken FLAG_KEEP_SCREEN_ON),
  `oto_baglan` (varsayılan açık), `acilis_sekme` (REMOTE/CAST/TOOLS/LAST; LAST için `son_sekme`). Fare hızı ve şerit
  `MouseSettings` ile aynı değer ("Fare ▾" penceresiyle ortak). "TV'ler ve bağlantı türü" satırı mevcut TV seçme penceresini açar.
- **Sıkça sorulan sorular (1 Kasım 2026, içerik Serif'in onayıyla):** Ayarlar → Hakkında → "Sıkça sorulan sorular" → tam sayfa
  `FaqPage` (`FaqPage.kt`), gruplu; soruya dokununca cevap açılır (aynı anda bir tane). Kurulum / Sorun giderme değişirse burası da güncellenir.
