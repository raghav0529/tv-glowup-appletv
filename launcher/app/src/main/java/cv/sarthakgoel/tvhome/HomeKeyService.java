package cv.sarthakgoel.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services cv.sarthakgoel.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    /** home_redirect_package: its screens come straight back here. Empty: off. */
    private String homeRedirectPackage = "";
    /** mi_key_opens_deck: the logcat process and its reader, while they run. */
    private Process miLog;
    private Thread miThread;
    private long miOpenedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        homeRedirectPackage = config.homeRedirectPackage;
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        if (config.miKeyOpensDeck && miThread == null) startMiWatch();
    }

    /**
     * The Mi button never reaches a service that does not filter keys, and filtering costs held volume its repeat. The
     * firmware's own receiver logs each press ("receiving KeyEvent.KEYCODE_BUTTON_2"), so read just that tag: logd does
     * the filtering and this thread sleeps until a press. If the line ever changes, the button just does nothing.
     */
    private void startMiWatch() {
        miThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    miLog = new ProcessBuilder("logcat", "-T", "1", "-v", "brief", "-s", "GlobalKey:V")
                            .redirectErrorStream(true).start();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(miLog.getInputStream()))) {
                        for (String line; (line = in.readLine()) != null; ) {
                            if (line.contains("receiving KeyEvent.KEYCODE_BUTTON_2")) handler.post(this::openDeck);
                        }
                    }
                } catch (IOException ignored) {
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mi-key-log");
        miThread.setPriority(Thread.MIN_PRIORITY);
        miThread.start();
    }

    private void openDeck() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - miOpenedAt < 1000) return;
        miOpenedAt = now;
        try {
            startActivity(new Intent(this, DeckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (miThread != null) miThread.interrupt();
        if (miLog != null) miLog.destroy();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void openHome() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            openHome();
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (!homeRedirectPackage.isEmpty() && pkg != null && homeRedirectPackage.contentEquals(pkg)) openHome();
    }

    @Override
    public void onInterrupt() {
    }
}
