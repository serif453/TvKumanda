package com.serif.tvkumanda;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TV'de (adb shell kullanıcısıyla, app_process ile) arka planda çalışan "nöbetçi".
 * Telefondan bağımsız çalışır; telefon kapansa ya da evden çıksa da işini yapar:
 *
 *   - Uyku zamanlayıcısı: belirlenen anda TV'yi uyutur (istenirse son dakikada sesi yavaşça kısar)
 *   - Ses sınırı: ses belirlenen seviyenin üstüne çıkarsa geri kısar
 *   - Ekran süresi sınırı: ekranın açık kaldığı süreyi sayar; günlük süre dolunca ya da
 *     gece saatlerinde TV açılırsa uyutur. Sayaç her gün sıfırlanır.
 *
 * Ayarları {@link #CONF} dosyasından okur (uygulama yazar), durumunu {@link #STATE} dosyasına yazar.
 * Yapacak işi kalmayınca kendiliğinden kapanır. TV tamamen yeniden başlarsa durur; uygulama TV'ye
 * ADB ile bağlanınca gerekiyorsa yeniden başlatır. Bu sınıf yalnızca standart Java kullanır.
 */
public final class Watcher {

    /** TV'de kabuk komutu çalıştırır (testte sahtesi verilir). */
    public interface Shell {
        String run(String command);
    }

    static final String DEFAULT_DIR = "/data/local/tmp";
    static final String CONF = "tvkumanda-nobetci.conf";
    static final String STATE = "tvkumanda-nobetci.state";

    static final long LOOP_MS = 3000;
    static final long SCREEN_CHECK_MS = 30_000;
    static final long STATE_WRITE_MS = 15_000;
    static final long FADE_WINDOW_MS = 60_000;
    static final long FADE_STEP_MS = 5000;
    /** Nöbetçi kapalıyken kaçırılan uyku anı bu kadar eskiyse artık uygulanmaz. */
    static final long STALE_SLEEP_MS = 10 * 60_000;

    static final int KEY_SLEEP = 223;
    static final int KEY_VOLUME_DOWN = 25;

    private static final Pattern VOLUME = Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]");

    private final File confFile;
    private final File stateFile;
    private final Shell shell;
    private final String apk;
    private final long pid;

    // Ayarlar
    long sleepAt;
    boolean fade;
    int volMax = -1;
    int limitMin;
    int nightFrom = -1;
    int nightTo = -1;
    private long confStamp = Long.MIN_VALUE;

    // Durum
    String day = "";
    long usedSec;
    long sleepDone;
    boolean awake = true;
    private long lastScreenCheck;
    private long lastStateWrite;
    private long lastFadeStep;
    private int volumeMethod = -1;

    Watcher(File dir, Shell shell, String apk, long pid) {
        this.confFile = new File(dir, CONF);
        this.stateFile = new File(dir, STATE);
        this.shell = shell;
        this.apk = apk;
        this.pid = pid;
    }

    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0] : DEFAULT_DIR);
        Watcher w = new Watcher(dir, Watcher::exec, String.valueOf(System.getenv("CLASSPATH")), selfPid());
        w.restore();
        while (true) {
            boolean more;
            try {
                more = w.tick(System.currentTimeMillis());
            } catch (Throwable t) {
                more = true;   // tek bir turdaki hata nöbetçiyi durdurmasın
            }
            if (!more) break;
            try {
                Thread.sleep(LOOP_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ---------------- Tur ----------------

    /** Bir tur; yapacak iş kalmadıysa durumu yazar ve false döner. */
    boolean tick(long now) {
        loadConfig(false);
        rollDay(now);
        if (!hasWork()) {
            writeState(now, false);
            return false;
        }

        // Ekran: açık kalınan süre, günlük sınır ve gece saatleri
        if (lastScreenCheck == 0 || now - lastScreenCheck >= SCREEN_CHECK_MS) {
            boolean wasAwake = awake;
            long since = lastScreenCheck == 0 ? 0 : now - lastScreenCheck;
            awake = isAwake();
            if (wasAwake && awake && since > 0) usedSec += Math.min(since, 2 * SCREEN_CHECK_MS) / 1000;
            lastScreenCheck = now;
            if (awake && limitMin > 0 && usedSec >= limitMin * 60L) {
                sleepTv();
            } else if (awake && nightFrom >= 0 && inNight(minuteOfDay(now), nightFrom, nightTo)) {
                sleepTv();
            }
        }

        // Uyku zamanlayıcısı
        if (sleepAt > sleepDone) {
            if (now >= sleepAt) {
                if (now - sleepAt < STALE_SLEEP_MS) sleepTv();
                sleepDone = sleepAt;
                writeState(now, true);
            } else if (fade && sleepAt - now <= FADE_WINDOW_MS && now - lastFadeStep >= FADE_STEP_MS) {
                key(KEY_VOLUME_DOWN);
                lastFadeStep = now;
            }
        }

        // Ses sınırı
        if (volMax >= 0) {
            int cur = volume();
            if (cur > volMax) setVolume(volMax);
        }

        if (now - lastStateWrite >= STATE_WRITE_MS) writeState(now, true);
        return true;
    }

    boolean hasWork() {
        return sleepAt > sleepDone || volMax >= 0 || limitMin > 0 || nightFrom >= 0;
    }

    // ---------------- Kurallar ----------------

    /** Dakika [from, to) gece aralığında mı; aralık gece yarısını aşabilir (ör. 23:00–07:00). */
    static boolean inNight(int minute, int from, int to) {
        if (from < 0 || to < 0 || from == to) return false;
        return from < to ? (minute >= from && minute < to) : (minute >= from || minute < to);
    }

    static int minuteOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static String dayOf(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private void rollDay(long now) {
        String today = dayOf(now);
        if (!today.equals(day)) {
            day = today;
            usedSec = 0;
        }
    }

    // ---------------- TV ----------------

    private void key(int code) {
        shell.run("input keyevent " + code);
    }

    private void sleepTv() {
        key(KEY_SLEEP);
        awake = false;
    }

    /** Ekran açık mı; anlaşılamazsa açık sayılır (sınırlar yine işlesin). */
    boolean isAwake() {
        String p = shell.run("dumpsys power | grep -m1 mWakefulness=");
        if (p.contains("Awake")) return true;
        if (p.contains("Asleep") || p.contains("Dozing")) return false;
        String d = shell.run("dumpsys display | grep -m1 mScreenState=");
        if (d.contains("=ON")) return true;
        if (d.contains("=OFF") || d.contains("=DOZE")) return false;
        return true;
    }

    private static final String[] VOLUME_GET = {
            "cmd media_session volume --stream 3 --get",
            "media volume --stream 3 --get"
    };
    private static final String[] VOLUME_SET = {
            "cmd media_session volume --stream 3 --set ",
            "media volume --stream 3 --set "
    };

    /** Medya ses seviyesi; bilinmiyorsa -1. Bu TV'de çalışan yöntem hatırlanır. */
    int volume() {
        if (volumeMethod >= 0) {
            int v = parseVolume(shell.run(VOLUME_GET[volumeMethod]));
            if (v >= 0) return v;
        }
        for (int i = 0; i < VOLUME_GET.length; i++) {
            int v = parseVolume(shell.run(VOLUME_GET[i]));
            if (v >= 0) {
                volumeMethod = i;
                return v;
            }
        }
        return -1;
    }

    private void setVolume(int level) {
        shell.run(VOLUME_SET[Math.max(volumeMethod, 0)] + level);
    }

    static int parseVolume(String out) {
        Matcher m = VOLUME.matcher(out == null ? "" : out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ---------------- Dosyalar ----------------

    static Map<String, String> parse(String text) {
        Map<String, String> map = new HashMap<>();
        if (text == null) return map;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return map;
    }

    private static long num(Map<String, String> m, String k, long def) {
        try {
            String v = m.get(k);
            return v == null || v.isEmpty() ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Ayar dosyası değiştiyse yeniden okur. */
    void loadConfig(boolean force) {
        long stamp = confFile.exists() ? confFile.lastModified() ^ confFile.length() : -1;
        if (!force && stamp == confStamp) return;
        confStamp = stamp;
        Map<String, String> m = parse(read(confFile));
        sleepAt = num(m, "sleep_at", 0);
        fade = num(m, "sleep_fade", 0) == 1;
        volMax = (int) num(m, "vol_max", -1);
        limitMin = (int) num(m, "limit_min", 0);
        nightFrom = (int) num(m, "night_from", -1);
        nightTo = (int) num(m, "night_to", -1);
    }

    /** Önceki çalışmadan kalanlar: bugünkü ekran süresi ve uygulanmış son uyku anı. */
    void restore() {
        Map<String, String> m = parse(read(stateFile));
        sleepDone = num(m, "sleep_done", 0);
        String d = m.get("day");
        if (d != null && d.equals(dayOf(System.currentTimeMillis()))) {
            day = d;
            usedSec = num(m, "used_sec", 0);
        }
        loadConfig(true);
    }

    void writeState(long now, boolean running) {
        lastStateWrite = now;
        String text = "pid=" + pid + "\n"
                + "apk=" + apk + "\n"
                + "beat=" + now + "\n"
                + "day=" + day + "\n"
                + "used_sec=" + usedSec + "\n"
                + "sleep_done=" + sleepDone + "\n"
                + "running=" + (running ? 1 : 0) + "\n";
        File tmp = new File(stateFile.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (Exception e) {
            return;
        }
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete();
            tmp.renameTo(stateFile);
        }
    }

    static String read(File f) {
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    // ---------------- Sistem ----------------

    private static long selfPid() {
        try {
            return Long.parseLong(new File("/proc/self").getCanonicalFile().getName());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String exec(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
