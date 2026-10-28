package com.serif.tvkumanda;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * TV üzerinde (adb shell kullanıcısıyla, app_process ile) çalışan küçük tuş sunucusu.
 * Telefondan satır satır komut alır ve tuşları doğrudan sisteme enjekte eder.
 * Böylece her tuş için yeni "input" işlemi başlatılmaz; gecikme birkaç milisaniyeye iner.
 *
 * Komutlar:
 *   k <kod>   -> tuşa bas-bırak
 *   t <metin> -> metin yaz
 *   m dx dy   -> fare imlecini göreceli taşı
 *   c         -> sol tık
 *   w n       -> fare tekerleği
 *   a         -> TV uygulamalarını gerçek adları ve simgeleriyle listele
 *                (her satır: APP\tpaket\tetkinlik\tad\tbase64png, en sonda END)
 *
 * Bu sınıf telefonda çalışmaz; uygulamanın APK'sı TV'ye kopyalanıp orada çalıştırılır.
 */
public final class KeyServer {

    private static Object inputManager;
    private static Method injectMethod;
    private static final int INJECT_ASYNC = 0;

    public static void main(String[] args) throws Exception {
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
        }
        initInjector();

        System.out.println("READY");
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        String line;
        while ((line = in.readLine()) != null) {
            try {
                if (line.startsWith("k ")) {
                    pressKey(Integer.parseInt(line.substring(2).trim()));
                } else if (line.startsWith("t ")) {
                    typeText(charMap, line.substring(2));
                } else if (line.startsWith("m ")) {
                    String[] p = line.split(" ");
                    mouseMove(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                } else if (line.equals("c")) {
                    mouseClick();
                } else if (line.startsWith("w ")) {
                    mouseWheel(Integer.parseInt(line.substring(2).trim()));
                } else if (line.equals("a")) {
                    listApps();
                }
            } catch (Throwable ignored) {
            }
        }
        // Telefon bağlantıyı kapatınca stdin biter ve sunucu kendiliğinden kapanır.
    }

    private static void initInjector() throws Exception {
        // Android 14+ : InputManagerGlobal, daha eskisi: InputManager
        try {
            Class<?> cls = Class.forName("android.hardware.input.InputManagerGlobal");
            inputManager = cls.getMethod("getInstance").invoke(null);
            injectMethod = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
            return;
        } catch (Throwable ignored) {
        }
        Class<?> cls = Class.forName("android.hardware.input.InputManager");
        inputManager = cls.getDeclaredMethod("getInstance").invoke(null);
        injectMethod = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static void inject(KeyEvent event) throws Exception {
        injectMethod.invoke(inputManager, event, INJECT_ASYNC);
    }

    private static void pressKey(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        inject(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void typeText(KeyCharacterMap map, String text) throws Exception {
        for (char c : text.toCharArray()) {
            KeyEvent[] events = map.getEvents(new char[]{c});
            if (events == null) continue; // klavyede karşılığı olmayan karakter
            for (KeyEvent e : events) {
                inject(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
            }
        }
    }

    // ---------------- Sanal fare (/dev/uhid) ----------------
    // Çekirdeğin UHID arayüzüyle gerçek bir USB fare gibi görünen sanal cihaz oluşturulur.
    // Android bunu takılı fare sanır ve TV'de imleci gösterir.

    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static FileOutputStream uhid;

    // 3 düğmeli, X/Y/tekerlek göreceli fare
    private static final byte[] MOUSE_DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, 0x09, 0x01, (byte) 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
            (byte) 0xC0, (byte) 0xC0
    };

    private static void ensureMouse() throws Exception {
        if (uhid != null) return;
        FileOutputStream out = new FileOutputStream("/dev/uhid");
        ByteBuffer b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_CREATE2);
        b.put("TvKumanda Mouse".getBytes("UTF-8"));
        b.position(4 + 128 + 64 + 64);           // name, phys, uniq
        b.putShort((short) MOUSE_DESCRIPTOR.length);
        b.putShort((short) 0x03);                // BUS_USB
        b.putInt(0x18D1);                        // vendor
        b.putInt(0x7A01);                        // product
        b.putInt(1);                             // version
        b.putInt(0);                             // country
        b.put(MOUSE_DESCRIPTOR);
        out.write(b.array());
        out.flush();
        uhid = out;
        Thread.sleep(150); // cihazın kaydolması için kısa bekleme
    }

    private static void mouseReport(int buttons, int dx, int dy, int wheel) throws Exception {
        ensureMouse();
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_INPUT2);
        b.putShort((short) 4);
        b.put((byte) buttons);
        b.put((byte) dx);
        b.put((byte) dy);
        b.put((byte) wheel);
        uhid.write(b.array());
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private static void mouseMove(int dx, int dy) throws Exception {
        // Büyük hareketleri 127'lik parçalara böl
        while (dx != 0 || dy != 0) {
            int sx = clamp(dx), sy = clamp(dy);
            mouseReport(0, sx, sy, 0);
            dx -= sx;
            dy -= sy;
        }
    }

    private static void mouseClick() throws Exception {
        mouseReport(1, 0, 0, 0);
        Thread.sleep(25);
        mouseReport(0, 0, 0, 0);
    }

    private static void mouseWheel(int n) throws Exception {
        mouseReport(0, 0, 0, clamp(n));
    }

    // ---------------- Uygulama listesi (ad + simge) ----------------
    // app_process içinde normal bir Context yoktur; sistem bağlamı ActivityThread üzerinden
    // oluşturulur (scrcpy'nin de kullandığı yöntem). Böylece PackageManager ile TV'nin
    // kendi gösterdiği uygulama adları ve simgeleri okunabilir.

    private static Context context;

    private static Context context() throws Exception {
        if (context != null) return context;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        Method sys = at.getDeclaredMethod("getSystemContext");
        sys.setAccessible(true);
        context = (Context) sys.invoke(thread);
        // XML simgeler (uyarlanabilir simge, vektör) çizilirken bazı TV yazılımları
        // ActivityThread.currentApplication().getResources() çağırıyor; app_process'te
        // Application olmadığı için NullPointerException oluşuyordu. Sistem bağlamına
        // bağlı boş bir Application tanımlanır.
        try {
            android.app.Application application = new android.app.Application();
            Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(application, context);
            Field init = at.getDeclaredField("mInitialApplication");
            init.setAccessible(true);
            init.set(thread, application);
        } catch (Throwable ignored) {
        }
        return context;
    }

    private static String clean(CharSequence s) {
        return s == null ? "" : s.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * Drawable'ı PNG'ye çevirir. Geniş görseller (afiş) oranı korunarak, telefonda
     * kutucuğu doldurmak üzere kırpılabilecek kadar yüksek çözünürlükte çizilir.
     * Hata olursa ya da görsel tamamen boşsa "" döner (sıradaki kaynak denenir).
     */
    private static String lastErr = "";

    private static String iconPng(Drawable d) {
        lastErr = "";
        if (d == null) return "";
        try {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            // Görselin kendi en-boy oranı korunur (kare simge kare, geniş afiş geniş kalır)
            int w = 160, h = 160;
            if (iw > 0 && ih > 0 && iw != ih) w = Math.max(80, Math.min(400, Math.round(160f * iw / ih)));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            if (isBlank(bmp)) { bmp.recycle(); lastErr = "(boş)"; return ""; }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            lastErr = "(" + t.getClass().getSimpleName() + ")";
            return "";
        }
    }

    /** Tamamen saydam (hiçbir şey çizilmemiş) görseli yakalar. */
    private static boolean isBlank(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        for (int y = 0; y < h; y += 4)
            for (int x = 0; x < w; x += 4)
                if ((b.getPixel(x, y) >>> 24) != 0) return false;
        return true;
    }

    /**
     * Simgeyi uygulamanın kendi teması uygulanmış bağlamından yükler. Bazı uygulamaların
     * (ör. Stremio) simgesi temadaki renklere başvurur; tema olmadan çizilemez.
     */
    private static Drawable themed(ActivityInfo ai, int resId, int density) throws Throwable {
        if (resId == 0) return null;
        Context app = context().createPackageContext(ai.packageName, Context.CONTEXT_IGNORE_SECURITY);
        int theme = ai.getThemeResource();
        if (theme == 0 && ai.applicationInfo != null) theme = ai.applicationInfo.theme;
        if (theme != 0) app.setTheme(theme);
        return density > 0
                ? app.getResources().getDrawableForDensity(resId, density, app.getTheme())
                : app.getResources().getDrawable(resId, app.getTheme());
    }

    /**
     * Simgeyi sistemin hazır kaynak yükleyicisini atlayıp doğrudan uygulamanın APK
     * dosyalarından (ana APK + parçalı "split" APK'lar) okur. Bazı uygulamalarda
     * (Play'den parça parça yüklenenler) simge yalnızca yoğunluk parçasındadır ve
     * sistem bağlamı onu bulamaz (NotFoundException). Ekran yoğunluğu da açıkça verilir.
     */
    private static Drawable fromApk(ActivityInfo ai, int resId) throws Throwable {
        if (resId == 0 || ai.applicationInfo == null) return null;
        android.content.pm.ApplicationInfo app = ai.applicationInfo;
        android.content.res.Resources r = apkResources.get(app.packageName);
        if (r == null) {
            android.content.res.AssetManager am = android.content.res.AssetManager.class
                    .getDeclaredConstructor().newInstance();
            Method add = android.content.res.AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            add.setAccessible(true);
            lastCookies = "c=" + add.invoke(am, app.sourceDir);
            if (app.splitSourceDirs != null) for (String sp : app.splitSourceDirs) lastCookies += "," + add.invoke(am, sp);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            dm.setToDefaults();
            dm.densityDpi = 320; dm.density = 2f; dm.scaledDensity = 2f;
            dm.widthPixels = 1920; dm.heightPixels = 1080;
            android.content.res.Configuration cfg = new android.content.res.Configuration();
            cfg.setToDefaults();
            cfg.densityDpi = 320;
            cfg.screenWidthDp = 960; cfg.screenHeightDp = 540; cfg.smallestScreenWidthDp = 540;
            cfg.uiMode = android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                    | android.content.res.Configuration.UI_MODE_NIGHT_YES;
            r = new android.content.res.Resources(am, dm, cfg);
            apkResources.put(app.packageName, r);
        }
        android.content.res.Resources.Theme t = r.newTheme();
        int theme = ai.getThemeResource();
        if (theme == 0) theme = app.theme;
        if (theme != 0) try { t.applyStyle(theme, true); } catch (Throwable ignored) { }
        try {
            return r.getDrawableForDensity(resId, 320, t);
        } catch (Throwable e) {
            return r.getDrawable(resId, t);
        }
    }

    private static String lastCookies = "";

    /** Hata ve nedenlerinin kısa metni. */
    private static String describe(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (int depth = 0; t != null && depth < 3; depth++, t = t.getCause()) {
            if (depth > 0) b.append(" <- ");
            String m = t.getMessage();
            b.append(t.getClass().getSimpleName()).append(": ").append(m == null ? "" : m.length() > 140 ? m.substring(0, 140) : m);
        }
        return b.toString();
    }

    /** APK dosyalarının yeri ve okunabilirliği (teşhis). */
    private static String fileInfo(android.content.pm.ApplicationInfo app) {
        if (app == null) return "appInfo yok";
        StringBuilder b = new StringBuilder();
        java.util.List<String> files = new java.util.ArrayList<>();
        files.add(app.sourceDir);
        if (app.splitSourceDirs != null) java.util.Collections.addAll(files, app.splitSourceDirs);
        for (String f : files) {
            java.io.File file = new java.io.File(f == null ? "" : f);
            b.append(f).append(" okunur=").append(file.canRead()).append(" boyut=").append(file.length()).append("; ");
        }
        b.append(lastCookies).append(" flags=").append(Integer.toHexString(app.flags));
        return b.toString();
    }

    private static final java.util.HashMap<String, android.content.res.Resources> apkResources = new java.util.HashMap<>();

    private interface IconSource { Drawable load() throws Throwable; }

    /**
     * Simgeyi sırayla birkaç kaynaktan dener: etkinlik simgesi, uygulama simgesi,
     * TV afişi (banner), logo; en son sistemin varsayılan simgesi. İlk başarıyla
     * çizilen kullanılır. Bazı TV uygulamalarında kare simge yoktur, yalnızca afiş vardır.
     */
    /**
     * Dönüş: {png, hataÖzeti}. png boşsa hataÖzeti her denemenin neden başarısız
     * olduğunu kısaca anlatır (teşhis için telefona gönderilir).
     */
    private static String[] bestIcon(final PackageManager pm, final ResolveInfo ri) {
        final ActivityInfo ai = ri.activityInfo;
        final android.content.pm.ApplicationInfo app = ai.applicationInfo;
        final boolean hasOwnIcon = ai.getIconResource() != 0 || (app != null && app.icon != 0);
        final android.content.ComponentName cn = new android.content.ComponentName(ai.packageName, ai.name);
        final String[] names = {"ri", "app", "banner", "appBanner", "logo", "pmApp", "pmAct", "pmBanner",
                "tIcon", "tAppIcon", "tBanner", "tAppBanner", "dIcon", "dAppIcon",
                "apkIcon", "apkAppIcon", "apkBanner", "apkAppBanner", "default"};
        IconSource[] sources = new IconSource[] {
                () -> hasOwnIcon ? ri.loadIcon(pm) : null,
                () -> app != null && app.icon != 0 ? app.loadIcon(pm) : null,
                () -> ai.loadBanner(pm),
                () -> app != null ? app.loadBanner(pm) : null,
                () -> ai.loadLogo(pm),
                () -> pm.getApplicationIcon(ai.packageName),
                () -> pm.getActivityIcon(cn),
                () -> pm.getActivityBanner(cn),
                () -> themed(ai, ai.getIconResource(), 0),
                () -> themed(ai, app != null ? app.icon : 0, 0),
                () -> themed(ai, ai.getBannerResource(), 0),
                () -> themed(ai, app != null ? app.banner : 0, 0),
                () -> themed(ai, ai.getIconResource(), 480),
                () -> themed(ai, app != null ? app.icon : 0, 480),
                () -> fromApk(ai, ai.getIconResource()),
                () -> fromApk(ai, app != null ? app.icon : 0),
                () -> fromApk(ai, ai.getBannerResource()),
                () -> fromApk(ai, app != null ? app.banner : 0),
                () -> ri.loadIcon(pm)
        };
        StringBuilder why = new StringBuilder("res=").append(ai.getIconResource())
                .append('/').append(app != null ? app.icon : -1)
                .append(" b=").append(ai.getBannerResource()).append('/').append(app != null ? app.banner : -1);
        for (int i = 0; i < sources.length; i++) {
            try {
                Drawable d = sources[i].load();
                if (d == null) { why.append(' ').append(names[i]).append(":null"); continue; }
                String png = iconPng(d);
                if (!png.isEmpty()) return new String[]{png, ""};
                why.append(' ').append(names[i]).append(":").append(d.getClass().getSimpleName())
                   .append(d.getIntrinsicWidth()).append('x').append(d.getIntrinsicHeight()).append(lastErr);
            } catch (Throwable t) {
                why.append(' ').append(names[i]).append(":").append(t.getClass().getSimpleName());
                // Ayrıntılı neden yalnızca birkaç temsilci deneme için (metin kısa kalsın)
                if (i == 0 || names[i].equals("apkIcon")) why.append(" {").append(describe(t)).append('}');
            }
        }
        why.append(" | ").append(fileInfo(app));
        return new String[]{"", clean(why)};
    }

    private static void listApps() {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = context().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER");
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String act = ri.activityInfo.name;
                String label = clean(ri.loadLabel(pm));
                String[] icon = bestIcon(pm, ri);
                sb.append("APP\t").append(pkg).append('\t').append(act).append('\t')
                  .append(label).append('\t').append(icon[0]).append('\t').append(icon[1]).append('\n');
            }
        } catch (Throwable ignored) {
            // Liste alınamazsa telefon eski yönteme geri döner
        }
        sb.append("END\n");
        System.out.print(sb);
        System.out.flush();
    }
}
