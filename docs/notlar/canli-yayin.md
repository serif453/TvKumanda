# Canlı yayın — ayrıntılar

Ekran yansıtma ve telefonu TV hoparlörü yapma üzerinde çalışırken okunur.

- Canlı yayın (ekran yansıtma + telefonu TV hoparlörü yap): ikisi aynı motoru kullanır, ADB gerekmez.
  - `LiveService.kt`: `mediaProjection` türünde ön plan hizmeti. Ekran → `MediaCodec` H.264 (VirtualDisplay yüzeyi, uzun kenar
    en çok 1280, 16'nın katı, 4 Mbps, 1 sn anahtar kare, durağan ekranda `repeat-previous-frame-after`); ses →
    `AudioPlaybackCaptureConfiguration` (Android 10+, RECORD_AUDIO ister) → AAC-LC 128 kbps. Durum `LiveStream` nesnesinde.
  - `LiveTs.kt`: saf MPEG-TS paketleyici (`TsMuxer`; PAT/PMT her anahtar karede, PCR görüntü PID'inde, PTS = zaman + 1 sn pay),
    `Adts` başlığı, `LiveSize`. ffmpeg ile doğrulandı (hatasız çözülür, yayının ortasından katılan oynatıcı da çalışır).
  - `LiveServer.kt`: bitmeyen HTTP yanıtı (`/live.ts` video/mp2t, `/live.aac` audio/aac). Görüntüde yeni izleyici ilk
    anahtar kareden başlar; yetişemeyen izleyicinin kuyruğu atılır, sonraki anahtar kareden devam eder.
  - TV'ye gönderme (`LiveSend`): önce DLNA (bağlı TV'nin IP'sine uyan oynatıcı), olmazsa ADB'de `am start VIEW -t <tür>`,
    Standart'ta kumandanın "bağlantı aç" isteği. Hiçbiri açmazsa kullanıcı adresi kopyalayıp TV'deki oynatıcıya (VLC) yapıştırır.
  - **1 Kasım 2026, Serif'in Philips'inde:** DLNA alıcı uygulaması canlı yayını kendisi oynatamıyor, "hangi uygulamayla
    açayım" diye soruyor; VLC'ye de kendi oynatıcısına da devredince açılmadı, alıcı kapandı. Adres kopyalanıp **VLC'ye
    elle yapıştırılınca çalıştı**, gecikme 2-3 sn. Bu yüzden `LiveSend` ADB'de önce `TvTools.openInVlc` (VLC kuruluysa
    `am start VIEW -t <tür> -p org.videolan.vlc`, olmazsa `-n …/.StartActivity`), sonra DLNA, sonra TV'nin oynatıcısı.
    Gecikmeyi azaltma (TS'teki 1 sn PTS payını kısaltmak; Serif'e VLC → Gelişmiş → Ağ önbelleği 300 ms önerildi) Serif
    "şimdilik" deyip ertelendi. Yarım saniyenin altı ancak TV'ye ayrı alıcı uygulamayla olur (sorulacak).
    Telefon sesini kısma seçeneği yok (yakalamanın ses seviyesinden etkilenip etkilenmediği belirsiz).
