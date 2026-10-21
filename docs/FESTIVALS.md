# Editing the festival list

The launcher picks the day's festival from a table in
[`launcher/app/src/main/java/cv/sarthakgoel/tvhome/FestivalCalendar.java`](../launcher/app/src/main/java/cv/sarthakgoel/tvhome/FestivalCalendar.java).
The theme only switches the calendar on (`"festival": "auto"`). Dates are as observed in Delhi and currently run to **January 2028**.

## One line per festival

```java
new Entry("diwali", "2026-11-08", "2026-11-08", "Happy Diwali", GOLD, "diwali", true, PETALS),
//        id        first day     last day      greeting        accent wallpaper  (ignored) (ignored)
```

| Field | Meaning |
|---|---|
| id | A short name. Entries that share an id share a festival (the Diwali week). |
| first day, last day | `YYYY-MM-DD`. Use the same date twice for a single day. **This fork shows each festival on its own day only.** |
| greeting | Shown as "Happy Diwali, *greeting_name*" |
| accent | Greeting and focus-glow colour: `GOLD`, `SAFFRON`, `RED`, `GREEN`, `PINK`, `CHAMPAGNE`, `BLUE`, `PLUM` |
| wallpaper | A picture from `themes/india/festivals/<name>.jpg`, or `""` for none (keeps the weather background). Available: `christmas`, `diwali`, `dussehra`, `ganesh`, `holi`, `janmashtami`, `lohri`, `newyear`, `rakhi`, `tricolour` |
| last two | Diya and Home-press animation. **Ignored in this fork:** festival days show the greeting and artwork only. |

Rules:
- Keep entries in date order. **The first entry that covers a day wins.**
- A day that no entry covers is an ordinary day.
- To add a new picture, put a 1920×1080 JPEG in `themes/india/festivals/` (or generate one with `tools/wallgen/make_walls.py`) and use its file name without `.jpg`.

## Preview before it goes live

Set this in `themes/india/config.json`, push it, and restart Home. The TV shows that date's festival today, without touching the TV's clock:

```json
"festival": "preview:2026-11-08"
```

Set it back to `"auto"` afterwards.

## Apply a change

The calendar is compiled into the launcher, so a change means rebuild and reinstall. Needs JDK 17 and the Android SDK (see [MI-TV.md](MI-TV.md)), ADB on the TV, and **the same `~/.android/debug.keystore` that signed the installed launcher.** With a different key, `install -r` fails and you would have to uninstall, which loses the launcher's on-TV settings.

```bash
export MSYS_NO_PATHCONV=1 TV=<tv-ip>:5555
cd launcher && ./gradlew testReleaseUnitTest assembleRelease && cd ..
adb -s $TV install -r launcher/app/build/outputs/apk/release/app-release.apk
# an update unbinds the key services: turn them back on
adb -s $TV shell settings put secure enabled_accessibility_services cv.sarthakgoel.tvhome/.HomeKeyService:cv.sarthakgoel.tvhome/.InputKeyService
adb -s $TV shell settings put secure accessibility_enabled 1
```

`FestivalTest` in `launcher/app/src/test` checks the table, so run the tests before installing.
