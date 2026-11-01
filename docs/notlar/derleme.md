# Derleme ve sürüm — ayrıntılar

GitHub Actions derlemesi, imza, hızlandırma ve süre raporu üzerinde çalışırken okunur. Temel kurallar CLAUDE.md'de.


- Claude oturumlarında Android SDK yok; derleme yalnızca **GitHub Actions**'ta olur (`.github/workflows/build.yml`).
  Saf (Android'siz) kod ve testleri oturumda Kotlin derleyicisiyle (GitHub Releases'tan, JetBrains/kotlin) ve
  `/opt/gradle-*/lib/junit-4.13.2.jar` ile yerelde çalıştırılabilir; `main`'e göndermeden hataları yakalar.
- `main`'e her gönderimde: birim testleri (`testReleaseUnitTest`) + imzalı, küçültülmüş (R8) release APK →
  yeni sürüm `v0.N` olarak Releases'a yüklenir. Hata veren derleme numara tüketmez.
- Sürüm numarası etiketlerden hesaplanır (son etiket + 1). Bu repo 0.96'dan başladı; 0.1–0.95 eski, gizli
  `TvKumanda-eski` reposunda.
- Sürüm notları: önceki sürümden bu yana commit'lerin ilk satırları.
- Derleme başlatmayan dosyalar: `README.md`, `LICENSE`, `CLAUDE.md`. Küçük değişiklikleri tek gönderimde toplamak
  derleme sayısını azaltır.
- İmza sırları: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` (anahtar adı `tvkumanda`).
- Dış eylemler (gradle/actions, softprops/action-gh-release) commit kimliğiyle kilitli; sürüm yükseltirken
  yeni kimlik `git ls-remote` ile alınır.
- Derleme hızlandırma: Gradle 6 GB, Kotlin 3 GB bellek (herkese açık repoda makine 16 GB), derleme önbelleği açık
  (`gradle.properties`). Lint kapatılmaz; simge kütüphanesi (material-icons-extended) yerinde kalır (Serif'in kararları).
- Süre raporu: `.github/gradle/sure-raporu.gradle` en uzun 8 Gradle adımını derleme sayfasına not olarak yazar;
  `gh api repos/serif453/TvKumanda/check-runs/<job id>/annotations` ile okunur (derleme kayıtlarına oturumdan erişilemiyor).
  Uygulama kodu değişmeyen derlemede her şey önbellekten gelir (~45 sn); gerçek ölçüm kod değişen derlemede alınır.
