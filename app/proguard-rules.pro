# TV'de app_process ile çalıştırılan tuş sunucusu: adı ve main() değişmemeli
-keep class com.serif.tvkumanda.KeyServer { public static void main(java.lang.String[]); }
-keep class com.serif.tvkumanda.KeyServer* { *; }

# ADB istemcisi (dadb) olduğu gibi kalsın
-keep class dadb.** { *; }

# Kütüphanelerin isteğe bağlı başvurduğu, uygulamada bulunmayan sınıflar için uyarıları yok say
# (geçici kontrol)
