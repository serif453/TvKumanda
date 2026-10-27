package com.serif.tvkumanda;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
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
                }
            } catch (Throwable t) {
                System.out.println("ERR " + t);
                System.out.flush();
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
}
