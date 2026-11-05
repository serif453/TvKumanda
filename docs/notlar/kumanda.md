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
    **Eski oynatıcı kartı kaldırıldı (5 Kasım 2026, Serif'in isteği ve onayladığı önizleme):** Kumanda'da başlık + sarma çubuğu + medya tuşları kartı yok.
    TV'de bir şey çalarken alt sağda (Kısayollar'ın üstünde) çalan uygulamanın küçük yuvarlak simgesi (`NowPlayingChip`, rozet: çalıyor/duraklatıldı) durur;
    dokununca tam ekran `NowPlayingPage` (`NowPlayingPage.kt`): ✕ + "Şimdi oynatılıyor", büyük görsel (TV kapak vermediği için uygulama simgesi),
    başlık (+ sanatçı/alt başlık varsa), sarma çubuğu ve süreler, ⏮ ⏪ ▶⏸ ⏩ ⏭ (`MediaKeysRow`), TV ses çubuğu (`GlassVolumePill`), en altta
    **TV adı yerine çalan uygulamanın adı**. Çalan bilgisi 3 sn boyunca gelmezse sayfa kendiliğinden kapanır. Simgenin yeri (64 dp) hep ayrılır
    (çalmaya başlayınca ekran zıplamasın), arkasında `glassSwoosh`. Sağdaki 🎵 "Medya tuşları" penceresi duruyor.
- TV seçimi: `TvPicker.kt` (panel) + `TvSheet.kt` (`TvManageSections`, büyük hâlin bölümleri); bağlıyken büyüyünce ağda arama yapılmaz, "Tekrar ara" ile elle aranır (Serif'in isteği).
- **TV paneli (1 Kasım 2026, Serif'in isteği ve onayı):** TV adı kutusuna (pil) dokununca alttan pencere değil, pilin hemen
  altından aşağı açılan küçük cam panel (`TvPicker.kt`, `TvPickerPanel`). Genişlik ayarlar ve güç düğmelerinin iç kenarları
  arası (yan boşluk 68 dp), boy içeriğe göre (en fazla ekranın %55'i), arkası kararır; dışarı dokununca ya da geri tuşuyla kapanır.
  İçerik: kayıtlı TV'ler (dokununca kendi kayıtlı bağlantı türüyle geçer; kapalıysa "Uyandır"), bağlı TV'de ADB | Standart
  seçici, IR kumandalar, en altta "TV ekle / yönet". Büyük pencere ve IR kartları da cam renklere geçti (`GlassSheet`, Inter).
  **v0.147 (Serif'in isteği):** alttan açılan "TV'ler" penceresi kaldırıldı. "TV ekle / yönet" aynı paneli aşağı (alt menünün
  12 dp üstüne kadar) ve yanlara (16 dp) büyütür: TV satırlarına ⋮ (adlandır, bağlantıyı kes, unut), altında IR kumandalar,
  ağda bulunanlar, IP ile ekleme, durum, yardım. Satır "Daralt" olur; geri tuşu önce küçültür. Kayıtlı TV yokken, ilk açılışta,
  bağlantı hatasında ve Ayarlar'daki "TV'ler" satırından panel doğrudan büyük açılır. Büyükken bağlanınca panel kapanır.
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
- **Alt menü özelleştirme (2 Kasım 2026, Serif'in isteği):** Ayarlar → Alt menü'de her sekme için anahtar (`AppSettings.hiddenTabs`,
  "gizli_sekmeler"). En az biri açık (son anahtar kilitli). Tek sekme kalınca `BottomNav` çizilmez, `NavBar.visible` false ve
  `BarSpace` 16 dp'ye iner. Açılış sekmesi gizliyse açık ilk sekme; o anki sekme gizlenirse ilk açık sekmeye geçilir. Paylaşımla
  açılan gizli Cast o seferlik menüde görünür.

## Kenardan kenara ve ortak tasarım (2 Kasım 2026 gece, Serif'in isteği)
- `enableEdgeToEdge` (şeffaf şeritler, açık simgeler): arka plan saat şeridinin ve alttaki çizginin arkasına uzanır.
  Ana sütun `systemBarsPadding`, alt menü `navigationBarsPadding`; tam ekran sayfalar (Ayarlar, SSS, DLNA, IR, araç
  sayfaları, yazma ekranı, tam ekran oynatıcı) arka planı tam çizip içeriği `systemBarsPadding` ile içeride tutar.
  Yeni tam ekran sayfa eklerken aynısı yapılmalı. `themes.xml`'deki şerit renkleri artık etkisiz.
- Tüm sekmeler Kumanda'nın arka planını (`glassBackdrop`) ve üst şeridini (navy) kullanır; Cast / Araçlar / Kısayollar
  kartları `Modifier.glass`. Alt sayfalar (Ayarlar, araç sayfaları, DLNA sayfası) şimdilik düz koyu (Pal.Bg).

## Arka plan teması (2 Kasım 2026, Serif'in isteği ve onayı, seçenek A)
- Ayarlar → Uygulama → "Arka plan teması" (`AppTheme`, `AppSettings.theme`, tercih `tema`): Mor (varsayılan), Lacivert, Gece, Orman, Bordo.
- Yalnızca zeminler değişir: `Glass.BgTop/BgMid/BgBottom/Glow`, `Pal.Bg/Btn/BtnPressed/Pad`, `Navy.Bg/Card/Btn/BtnPressed`, `GlassSheet.Bg/Dialog/Menu`
  temadan okunur. Tuşlar, mor vurgular ve yazılar sabit. Alt sayfalar da temanın koyu tonunu alır (Serif'in seçimi); bu yüzden Mor'da
  eskiden gri olan alt sayfalar artık koyu mor. Açık tema yok: tasarım koyu zemine göre.
- Yeni zemin rengi yazarken sabit renk değil bu değerler kullanılmalı.

## Ayarlar eklemeleri (2 Kasım 2026, Serif'in onayıyla)
- **Çökme kaydı** (`CrashLog.kt`, `cokme.txt`): son 5 çökme tam hata metniyle, IP'ler gizli; sürüm ve cihaz başlıkta. Teşhis kaydındaki kısa çökme satırı da durur. Ayarlar → Hakkında'dan kopyalanır, kendiliğinden hiçbir yere gitmez.
- Release derlemesi kod adlarını kısaltır (R8): satır numaraları `-keepattributes` ile korunur, adlar derleme sayfasındaki artifact'te gelen `mapping.txt` ile çözülür (`retrace`). Artifact 90 gün durur.
- **Güncelleme denetimi** (`UpdateCheck.kt`): yalnızca dokununca GitHub'ın son sürüm adresine sorar; yeni sürüm varsa sürüm sayfasını tarayıcıda açar. Otomatik denetim ve uygulama içi kurulum yok.
- **Ekran kararmasın** `ekran_kararma` (OFF/REMOTE/ALL); eski `ekran_acik` açıksa REMOTE olarak taşınır.
- **Titreşim gücü** `titresim_guc` (LOW/MID/HIGH): hazır efekt yerine süre + güç (15/25/45 ms); fark her telefonda hissedilsin diye.
- **Lisans sayfası** (`LicensePage.kt`): Türkçe özet, açık kaynak listesi (README ile uyumlu tut), tam metin `assets/LICENSE.txt` (kök `LICENSE`in kopyası).
- **Tanıtım sayfası** (`AboutPage.kt`): Ayarlar → Hakkında → TV Kumanda; içerik Serif'in onayıyla, geliştiricinin ağzından. Özellik ya da gizlilik durumu değişirse metin de güncellenir.

## Yeni kumanda düzeni (5 Kasım 2026, Serif'in onayladığı önizleme; örnek: Google TV uygulaması)
- **Yön dairesi** (`FlatDPad`, `GlassRemote.kt`): düz koyu halka, ortada yazısız gri düğme, soluk oklar. Basılan yönün çeyrek
  dilimi açık renkte parlar, ortaya basınca gri düğme beyazlaşır; yön basılı tutulunca tekrarlar. Eski `DPad` (mor OK) artık
  kumandada kullanılmıyor.
- **Kaydırma / D-pad seçimi:** TV adı kutusunun sağındaki ⌄ ayrı yuvarlak tuş (yalnızca Kumanda sekmesinde) → menü: Cihaz seçin
  (TV paneli) · Bağlantıyı kes · Kaydırma kontrolü · D-pad kontrolü (seçilide ✓). Seçim `AppSettings.swipeControl`
  ("kaydirma_kontrol") ile hatırlanır. Ad kısmına dokunmak eskisi gibi TV panelini açar. Soldaki Kaydırma tuşu kaldırıldı.
- **Kaydırma yüzeyi** (`SwipePad`): dairenin yerinde, yan tuşlar görünür kalır (eski geniş yüzey yalnızca fare modunda).
  Ortadaki nokta parmağı takip eder, ~48 dp kaydırma = bir yön, kısa dokunma = OK (halka yayılır), yön gidince o kenar parlar;
  altta "Gezinmek için kaydırın / Seçmek için dokunun". Eski `TouchPad` kullanılmıyor.
- **Yan tuşlar 3 + 3:** sol Kaynak · ⋯ Daha fazla (mor) · Klavye; sağ 123 · Fare · Medya. ⋯ alttan pencere açar
  (`MoreKeysSheet`): Bilgi (165) · Rehber (172) · Fn tuşları (= Sistem kısayolları) · TV ayarları.
- **Alt sıra:** yuvarlak Geri (←) · geniş kapsül Ana Sayfa (dolu ev) · yuvarlak koyu Mikrofon (dinlerken yine kırmızı, halkalı).
- Fare modunda 6 yan tuş solar, yüzey yan tuşların alanını kaplar; alt sıra yerinde kalır. Klavye artık sol sütunda olduğu
  için fare modunda görünmez. Ses çubuğuna dokunulmadı.

## Mor tasarım düzeni (5 Kasım 2026, v0.164'ten sonra; Serif'in kendi tasarım görseli + onayladığı önizleme)
- `PurpleRemote` (`GlassRemote.kt`) `GlassPadCard`'ın yerini aldı. Yukarıdan aşağı: Klavye (mor, ışıklı) · Medya Tuşları ·
  Numaralar · Daha fazla → yön dairesi → Geri · Ana Sayfa · Sesli Arama (mor; dinlerken kırmızı, "Dinliyor…") →
  Uyg. Yöneticisi · APK Yükle · Dosya Gönder (yukarı oklu dosya, `UploadFile`) · TV Dosya Gezgini (renk geçişli simgeler) →
  ses çubuğu (değişmedi). Yan sütunlar ve eski alt sıra kalktı.
- Yön dairesi ve kaydırma yüzeyi v0.164'teki gibi (Serif: tasarım ve işlev aynı kalsın, boyut değişebilir); daire kalan
  boşluğa göre (genişliğin en çok %74'ü, 120–300 dp). Küçük ekranda (<560 dp) kutular alçalır.
- ⌄ menüsü: Kaydırma kontrolü · **Fare kontrolü** · D-pad kontrolü (`controlMode` 0/1/2). Fare yalnızca ADB, oturumluk
  (hatırlanmaz); fare yüzeyi dairenin alanını tam genişlikte kaplar. Fare tuşu kalktı.
- Kaynak tuşu "Daha fazla" penceresine taşındı (Kaynak · Bilgi · Rehber / Fn tuşları · TV ayarları).
- Araç kutuları Kumanda'dan açılır: Uygulama yöneticisi ve TV dosya gezgini tam ekran (`ToolPageHost` artık Kumanda'da da),
  APK yükle ve Dosya gönder alttan pencere (`ToolSheet`). ADB değilse "Bu araç ADB bağlantısı gerektirir".
- TV'de çalanın küçük simgesi (`NowPlayingChip`) dairenin alanının sağ alt köşesinde; eski mor şerit (`glassSwoosh`) satırı kalktı.
- **Yazısız kutular (5 Kasım 2026, v0.165 sonrası, Serif):** kumanda gövdesindeki 11 kutuda yazı yok, yalnızca büyük simge
  (kutular 38 dp, araç simgeleri 46 dp, Geri 36, Ana Sayfa 38, mikrofon 36). Adlar sesli okuma (`semantics`) için duruyor.
- **Yön dairesinin içi (5 Kasım 2026, v0.166 sonrası, Serif):** düz `0xFF1D1D21` yerine Geri / Ana Sayfa tuşlarıyla aynı cam
  (`Modifier.glass(CircleShape)`: üstte hafif açık, aşağı koyulaşan, ince parlak kenar). Orta tuş, oklar ve dilim parlaması aynı.
- **Bağlantı kararlılığı (5 Kasım 2026, Serif: "kararsız, mobil veride bağlı görünüyor, Wi-Fi'da bağlanamıyorum"):**
  - Wi-Fi'a zorlama: ev ağı adreslerine giden tüm TV soketleri (Standart, ADB/dadb, TvPing) `TvSocket.create` ile Wi-Fi ağına
    bağlanır (`WifiNet`, internetsiz Wi-Fi de sayılır). Honor "Wi-Fi+" gibi özellikler trafiği mobil veriye kaydırıyordu.
    Wi-Fi yoksa "Wi-Fi'a bağlı değilsin" (boşuna denenmez). Ev ağı dışı adres (VPN ile uzaktan) olduğu gibi kalır.
  - ADB bağlanma süresi 5 sn (`CONNECT_TIMEOUT`; eskiden sınırsızdı, "Bağlanıyor…" takılıyordu). TV'deki izin onayı süresi sınırsız kalır.
  - ADB nabzı: uygulama ekrandayken 10 sn'de bir (hızlı mod yoksa 20 sn, son 5 sn'de tuş gittiyse atlanır) `TvRemote.ping`:
    hızlı kanala "h" → TV "END" der (4 sn). Cevapsızsa `onLinkLost`: "Yeniden bağlanılıyor…", bir deneme, olmazsa hata.
    Serif yük sordu: birkaç bayt, yalnızca uygulama açıkken; Standart'ta TV zaten 5 sn'de bir soruyor.
  - Standart: TV sessizliği 12 sn'de kopuş sayılır (eskiden 20).
  - Wi-Fi gidince bağlantı anında kapanır ("Wi-Fi bağlantısı gitti"), geri gelince ya da uygulama öne gelince (`onResume`)
    kendiliğinden yeniden bağlanılır (`lostByNetwork`). Kullanıcı "Bağlantıyı kes" derse yeniden bağlanılmaz.
  - `connGen`: her bağlanma denemesinin numarası; eski denemenin geç sonucu yenisinin durumunu ezmez, kullanılmayan bağlantı kapatılır.
  - Hata mesajları sadeleşti (`connError`): yanıt vermedi / ulaşılamadı / kabul etmedi.
- **Oyun kolu (5 Kasım 2026, Serif'in isteği ve onayladığı önizleme):** "Daha fazla" penceresinde TV ayarları'nın yanında
  "Oyun kolu" kutucuğu → `GamepadPage.kt` (yatay, tam ekran, sistem çubukları gizli, ekran kararmaz). Yön dönüşü için
  `MainActivity` manifestte `configChanges` aldı (yeniden oluşturulmasın; bağlantı ve durum korunur), sayfa kapanınca dikeye döner.
  Tuş tanımları, varsayılan yerler ve kayıt `GamepadLayout.kt` (saf, `GamepadLayoutTest`); düzen `oyun_kolu` tercihlerinde `duzen`.
  ADB hızlı modda TV'de sanal oyun kolu (`KeyServer`: "G" kur, "g düğmeler şapka lx ly rx ry", "G0" çıkar; /dev/uhid,
  ürün 0x7A02): çubuklar analog, aynı anda birden çok tuş. Standart'ta tuş kodları (BUTTON_A…), sol çubuk ve yön tuşları
  basılı tutunca tekrar eder, sağ çubuk çalışmaz. Ana Sayfa tuşu her iki yolda HOME gönderir.
  Düzenleme (kalem): sürükle = taşı, dokun = seç, − / + boyut (%60–%160), Kaldır, "Tuş ekle" ile geri, Sıfırla, Bitti (kaydeder;
  geri tuşu da kaydedip çıkar). Yön tuşları ve çubuklar tek parça. Tek düzen; birden çok düzen ileride (Serif'e soruldu).
  Philips'te denenecek: sanal kolun tanınması, oyunlarda düğme eşleşmesi.
- **Air Mouse (5 Kasım 2026, Serif'in isteği):** ⌄ menüsünde "Fare kontrolü"nün altında (`controlMode` 3, oturumluk, yalnızca ADB
  hızlı mod). Bluetooth fare taklidi yerine var olan sanal fare (`KeyServer` m/c/d/u/w) seçildi (Serif: "ADB üzerinden ekle"):
  eşleştirme yok, Honor desteği riski yok. Bluetooth yolu Standart bağlantı için ileride düşünülebilir.
  `AirMousePad` (`AirMouse.kt`): büyük alana basılıyken jiroskop imleci yürütür (sensör yalnızca basılıyken açık), <220 ms
  dokunma = tık; altta "Tıkla" (basınca d, bırakınca u → telefonla birlikte sürükleme); fare modunun kaydırma şeridi ve
  "Air Mouse ▾" (fare hız ayarıyla ortak). Mod açıkken ekran kararmaz. Jiroskop yoksa uyarı.
  Hesap `AirMotion.kt` (saf, `AirMotionTest`): sağa-sola = yerçekimi yönündeki dönüş (telefon düz ya da dik tutulsa da doğru),
  yukarı-aşağı = cihaz X ekseni; 0.025 rad/sn ölü bölge, yumuşatma, hafif hızlanma, 1300 px/rad × hız ayarı.
  Philips'te denenecek: yönler, hız, titreme.
  Dar alan (v0.170'te yazı kesiliyordu, Serif): yüzey <260 dp ise Tıkla 46 dp, boşluklar azalır; hareket alanı <130 dp ise
  simge + "Basılı tut, telefonu oynat" tek satır, "Kısa dokunma = tıkla" gizli.
