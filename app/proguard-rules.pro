# TV'de app_process ile çalıştırılan tuş sunucusu: adı ve main() değişmemeli
-keep class com.serif.tvkumanda.KeyServer { public static void main(java.lang.String[]); }
-keep class com.serif.tvkumanda.KeyServer* { *; }

# TV'de arka planda çalışan nöbetçi (uyku zamanlayıcısı, ses ve ekran süresi sınırı): adı ve main() değişmemeli
-keep class com.serif.tvkumanda.Watcher { public static void main(java.lang.String[]); }

# ADB istemcisi (dadb) olduğu gibi kalsın
-keep class dadb.** { *; }

# Not: Uyarıları toptan susturan kural (-dontwarn **) yok. Kontrol derlemesinde hiç
# eksik sınıf uyarısı çıkmadı; ileride bir uyarı çıkarsa derleme durur ve görülür.
