# Xiaomi Mi TV (MiTV-AXSO0) setup

Tested on a **Xiaomi MiTV-AXSO0** (sold as Mi TV 4A Pro / 4C Pro), Android TV 9, Amlogic, 1 GB RAM. Other Xiaomi Android TV 9 sets with PatchWall are likely similar. Check your model under Settings > Device Preferences > About.

## 1. Turn on ADB on the TV

1. Settings > Device Preferences > About: press OK on **Build** 7 times.
2. Settings > Device Preferences > Developer options:
   - **Revoke USB debugging authorizations** (clears stale approvals)
   - **USB debugging**: on
   - **MiTV ADB debugging**: on. **The TV asks to reboot. Reboot**, or the "Allow USB debugging?" prompt never appears.
3. Note the TV's IP: Settings > Network & Internet > your Wi-Fi.

**Make sure you connect to the right device.** If your home has a Fire TV Stick or another Android box, it also listens on port 5555. If you never get a prompt on the Mi TV, check that the IP really belongs to the TV. Its Chromecast name shows at `http://<ip>:8008/setup/eureka_info`.

## 2. Windows (Git Bash) notes

- Install ADB: `winget install Google.PlatformTools`
- **Always run `export MSYS_NO_PATHCONV=1` first.** Without it, Git Bash rewrites TV paths like `/sdcard/...` and `/data/app/...` into Windows paths. The backup then silently skips the APK copies, and the theme push lands in the wrong place.
- Git Bash cannot make symlinks, so `backups/original` and `backups/latest` are copies. Check that `backups/original` is your first, complete backup.
- To build the launcher: JDK 17 (`winget install EclipseAdoptium.Temurin.17.JDK`) and the Android SDK command-line tools with `platforms;android-34` and `build-tools;34.0.0`. Set `JAVA_HOME` and `ANDROID_HOME`.

## 3. Install

```bash
export MSYS_NO_PATHCONV=1
export TV=<tv-ip>:5555
# edit themes/india/config.json first: greeting_name, weather_lat, weather_lon, dock_apps
DEVICE=xiaomi-mitv-axso0-android9 ./install.sh
```

The debloat list keeps Xiaomi TV+, PatchWall, HDMI and tuner services, Chromecast, Miracast, the Assistant, firmware updates and the location service (disabling that one boot-loops some sets). Read it and comment out anything you use.

## 4. Mi-specific fixes after install

**Key services.** On Android 9, `install.sh` cannot tell that the accessibility service bound, so it switches it off again. Turn both on:
```bash
adb -s $TV shell settings put secure enabled_accessibility_services cv.sarthakgoel.tvhome/.HomeKeyService:cv.sarthakgoel.tvhome/.InputKeyService
adb -s $TV shell settings put secure accessibility_enabled 1
```
Check with `adb shell dumpsys accessibility | grep label=`. You should see "Home button" and "TV Glowup Home". **Any reinstall or force-stop of the launcher unbinds them, so run these again after one.**

**Holding volume, and why key filtering is off.** On this TV, any accessibility service that filters keys stops a held button repeating, so volume moves one step per press. The volume key never even reaches the service, so it cannot repeat the key itself. Turn filtering off in `themes/india/config.json`:
```json
"key_filter": false
```
This is cleanyourtv's `key_filter`. Without it, `app_keys` does nothing.

**The Mi button.** It sends keycode 189, which Xiaomi's partnercustomizer catches to open PatchWall. Two ways to deal with it without key filtering:
- **Open the mood deck (recommended):** disable PatchWall (`pm disable-user --user 0 com.mitv.tvhome.atv`), set `"mi_key_opens_deck": true` and run `pm grant cv.sarthakgoel.tvhome android.permission.READ_LOGS`. The launcher watches the system log for the firmware's line about the button. No flicker, and it costs about 2.5 MB. A firmware update could change that line. The round button stays Home. Xiaomi TV+ kept working on my TV with PatchWall disabled, so check yours.
- **Jump back to Home:** `"home_redirect_package": "com.mitv.tvhome.atv"` keeps PatchWall enabled and sends you straight back. It flickers, and repeated presses can leave you stuck on PatchWall, so I do not recommend it.

**Boot to the new home, not PatchWall:**
```bash
adb -s $TV shell settings put global set_patchwall_default 0
```
PatchWall itself stays installed, because Xiaomi TV+ may lean on it.

**Screensaver.** `install.sh` sets the Family Photos screensaver. To keep Google's Backdrop landscapes instead:
```bash
adb -s $TV shell pm enable com.google.android.backdrop
adb -s $TV shell settings put secure screensaver_components com.google.android.backdrop/.Backdrop
```
**Do not disable `com.google.android.tvrecommendations`.** Backdrop reads its photos through it. That app logs a harmless background crash each time the screensaver starts, because Google's TV home is disabled. You never see it.

**The black background behind Settings is expected.** Android's main Settings window shows the *system wallpaper*, not the app behind it (the Wi-Fi panel does not). Set a system wallpaper if you want something other than black.

## 5. Finish

Turn **USB debugging** and **MiTV ADB debugging** off again. Everything keeps working, and the TV stops accepting ADB from your Wi-Fi. To undo or change things later, switch them back on and run `./restore.sh`.
