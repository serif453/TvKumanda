package com.serif.tvkumanda;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
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
}
