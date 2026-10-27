# TV Kumanda (ADB)

Telefondan Android TV'yi Wi-Fi üzerinden ADB ile kontrol eden kumanda uygulaması.

## Özellikler
- Yön tuşları + OK, Geri, Ana ekran, Menü, Güç
- Ses −/+/Sessiz, medya (önceki / oynat-duraklat / sonraki)
- Telefonun ses tuşları TV'nin sesini açar/kısar
- TV'ye metin yazma (Türkçe harfler sadeleştirilir: ş→s, ğ→g…)
- YouTube, Netflix, Ayarlar kısayolları
- IP adresini hatırlar, açılışta otomatik bağlanır

## 1. TV'yi hazırla
1. Ayarlar → Sistem (veya Cihaz Tercihleri) → Hakkında → **Android TV OS derlemesi**ne 7 kez bas.
2. Geliştirici seçenekleri → **USB hata ayıklama**yı aç. Menüde varsa **Ağ üzerinden hata ayıklama / ADB over network**ü de aç.
3. Ayarlar → Ağ'dan TV'nin IP adresini öğren.
4. Telefon ve TV aynı Wi-Fi'da olmalı.

## 2. Uygulamayı derle
1. Bilgisayara Android Studio kur.
2. Bu klasörü aç (File → Open → TvKumanda). Gradle senkronizasyonunu bekle.
   Sürüm güncelleme önerirse kabul edebilirsin.
3. Telefonda geliştirici modu + USB hata ayıklamayı aç, USB ile bağla.
4. ▶ Run'a bas. Uygulama telefona yüklenir.

## 3. Kullan
1. IP adresini yaz → **Bağlan**.
2. TV'de "USB hata ayıklamaya izin verilsin mi?" çıkar → **Her zaman izin ver** → Tamam.
3. İlk denemede zaman aşımı olursa onayladıktan sonra tekrar **Bağlan**'a bas.

## Sorun giderme
- **Bağlanamadı / Connection refused:** TV 5555 portunu dinlemiyor. Bilgisayardan bir kez
  `adb connect <ip>` ya da USB ile `adb tcpip 5555` çalıştırmak gerekebilir.
- **Yalnızca "Kablosuz hata ayıklama" (eşleştirme kodlu) varsa:** bu uygulama eşleştirme kodunu
  desteklemez; bilgisayardan `adb pair` + `adb tcpip 5555` ile 5555'i bir kez aç.
- TV yeniden başlayınca 5555 bazı modellerde kapanır; yukarıdaki adımı tekrarla.
- Kısayol uygulaması açılmıyorsa o uygulama TV'de yüklü değildir veya paket adı farklıdır
  (`MainActivity.kt` içindeki paket adlarını değiştirebilirsin).
