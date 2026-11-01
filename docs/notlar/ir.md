# IR kumanda — ayrıntılar

Telefonun kızılötesi vericisiyle kumanda ve IR kod veritabanları üzerinde çalışırken okunur.

- **IR kumanda** (telefonun kızılötesi vericisi, `ConsumerIrManager`; IR yoksa bölüm hiç görünmez):
  - `IrCodec.kt` (saf): kod → süre dizisi. NEC, NECext, Samsung32, RC5, RC5X, RC6, SIRC(12/15/20), Kaseikyo, RCA, Pioneer
    ve ham (W). Adres/komut anlamı Flipper Zero kodlayıcılarıyla birebir (kodlar o cihazla kaydedildi). RC5/RC6 değişim biti
    her yeni basışta değişir, basılı tutunca değişmez. `IrCodecTest` bilinen kodlarla karşılaştırır (LG 20DF10EF, Samsung
    E0E040BF, Sony A90, Panasonic) ve gerçek veritabanının her kodunu kodlar.
  - Veritabanı `app/src/main/assets/ir/tv.txt`: `tools/ir_db_build.py` ile Flipper-IRDB `TVs/` klasöründen (CC0) üretilir.
    Tuş adları ortak ada çevrilir (vol_up, ch_up, ok…); aynı markanın kumandaları güç+ses koduna göre kod setlerine
    birleşir, çok kumandadan gelen set önce denenir. Vestel markası Flipper-IRDB'de yok; aile kodlarıyla gelir (aşağıda).
  - `IrRemote.kt`: TV seçimi penceresinde "IR KUMANDALAR" bölümü, ekleme (marka → kod seti deneme: sesi aç, gerekirse güç →
    ad), tam ekran IR kumanda. Kayıtlar `ayarlar` → `ir_kumandalar` (marka adı + kod seti sırası). Gönderim tek iş
    parçacığında; desteklenmeyen taşıyıcı frekansı en yakın desteklenene çekilir. Serif'in Honor telefonunda IR var;
    **1 Kasım 2026: Serif'in Philips TV'sinde çalıştı** (v0.117). Tarama ve çevrimiçi arama ayrıca denenmedi.
  - **Marka aileleri** (`IrDb.FAMILIES`, `withFamilies`): Vestel ve Arçelik grupları. Seçilen markanın önce kendi setleri, sonra
    ailenin setleri (aynı güç+ses imzası bir kez). Veritabanında olmayan aile markaları (Vestel, Regal, Arçelik, Beko, Altus…)
    yalnızca aile kodlarıyla listeye eklenir. Aile üyeliği genel bilgiye dayanır, gerçek TV'lerde doğrulanmadı.
  - **Güç taraması** (`IrDb.powerScan`, `PowerScan`): tüm farklı güç kodları (≈91) 1,5 sn arayla; durunca Önceki/Sonraki ile
    elle doğrulama (kapalı TV'yi açan kod doğrudur). Bulunan kodun setinin tüm tuşları kaydedilir.
  - **Dosyadan ekle** (`IrDb.parseFlipper`): Flipper .ir dosyası; tanınmayan tuş adları `x:` önekiyle "Diğer tuşlar"a.
  - Yeni kayıtlar tuş kodlarını kaydın içinde tutar (`IrSaved.keys`); eski kayıtlar marka + set sırasıyla bakılır. Bu yüzden
    bir markaya yeni kaynaktan set eklenirse **kendi setlerinin sonuna** eklenmeli (eski kayıtların sırası bozulmasın).
  - **SmartIR** (MIT): `tools/ir_smartir_add.py`, `ir_db_build.py`'den **sonra** çalışır (smartHomeHub/SmartIR + litinoveweedle/SmartIR
    klasörleri). Yalnızca TV dosyaları (elle liste), Broadlink/ESPHome/Pronto → süre; NEC/Samsung32/RC5/RC6 çözülebilenler P,
    kalanlar W (38 kHz). Setler markanın sonuna, yeni markalar dosya sonuna. "off" = power, farklıysa "on" → `x:Aç`; kaynaklar
    `x:HDMI…`. 14 set, 6 yeni marka (Xiaomi, Kivi, Noblex, OKI, TurboX, Andersson). Lisans metni `assets/ir/SMARTIR-LICENSE.txt`.
  - **irdb çevrimiçi** (`IrdbCodes` saf, `IrOnline.kt`: `IrdbOnline` + `IrdbPicker`): irdb **APK'ya gömülmez** (lisansı GPL ile
    çelişen ek şart koyuyor: ürün kopyası verme; ağdan erişime izin veriyor). Kullanıcı isteyince raw.githubusercontent.com'dan
    `index` (≈3200 dosya, 648 marka) ve markanın CSV'leri (en fazla 40 cihaz, TV adlılar önce, 4'ü aynı anda) iner. Aynı cihazın
    dosyaları birleşir. Biçimler: NEC1/NEC2/NEC1-f16, NECx, RC5, RC6, Sony12/15/20, Pioneer, Panasonic, JVC, Sharp, Denon,
    Mitsubishi (son beşi ham sinyal olarak kurulur; Panasonic Flipper'ın Kaseikyo'suyla birebir test edildi). Kalanlar (MCE, Aiwa,
    kablo/uydu biçimleri) atlanır. Lisans şartları: Serif 1 Kasım 2026'da irdb'de issue açtı; `IrdbCodes.NOTICE` listede ve
    README'de. **irdb verisi repoya da konmaz** (testler uydurma satırlarla).
- **Kaldırma (1 Kasım 2026, Serif'in isteği):** TV seçimindeki IR kumanda kartına basılı tutunca menü: Yeniden adlandır · Kaldır (onay sorar). Kumanda sayfasındaki ⋮ menüsünde de "Unut" yerine "Kaldır".
