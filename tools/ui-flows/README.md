# emulator ui flows

Shell scripts that drive a debug build on an Android emulator with adb + `uiautomator dump`.
They live outside the `app` module, so none of this ends up in any apk.

They only ever talk to an `emulator-*` serial. `lib.sh` refuses anything else, so a phone
plugged in over adb is never touched.

## setup (once)

    sdk=~/Library/Android/sdk
    $sdk/cmdline-tools/latest/bin/avdmanager create avd -n editotsu_uiflows \
        -k "system-images;android-36.1;google_apis;arm64-v8a" -d pixel_7
    # then in ~/.android/avd/editotsu_uiflows.avd/config.ini:
    #   hw.lcd.density=440  hw.lcd.height=2392  hw.keyboard=yes

A `google_apis` image is needed (not `google_apis_playstore`): the flows use `adb root` to
start non-exported activities and to read the app's settings files.

## run

    $sdk/emulator/emulator -avd editotsu_uiflows -no-window -no-audio -no-boot-anim -no-snapshot-save &
    ./gradlew assembleGoogleDebug
    adb -s emulator-5554 install -r app/build/outputs/apk/google/debug/app-google-arm64-v8a-debug.apk
    tools/ui-flows/run_all.sh            # or one script, e.g. tools/ui-flows/04_backup_restore_roundtrip.sh

`SERIAL=emulator-5556` picks another emulator, `PKG=` another application id (default
`ani.editotsu.beta`), and `OUT=` where screenshots and dumps go (default `$TMPDIR/editotsu-ui-flows`).
Every failure saves a screenshot there.

## flows

| script | checks |
|---|---|
| `01_settings_screens_open.sh` | every settings screen opens from the settings home, shows rows, no crash |
| `02_settings_search.sh` | typing `backup` in settings search lists results without a crash; tapping the first opens Data and Storage with that row on screen |
| `03_settings_advanced_toggle.sh` | on each screen with advanced rows: show adds rows and keeps the basic ones, hide restores the list (`./03_... Player` runs one screen) |
| `04_backup_restore_roundtrip.sh` | change settings, Back up now through the system file picker, change them again, Restore Backup, and compare the app's settings files to the backed-up state |
| `05_themes_favourites.sh` | anilist.co link opens a show, Themes tab, heart OP 1, favourite saved and listed on the Themes screen, removing it there clears it (needs network) |

Not covered here: the source picker filter sheet needs an installed source extension and a
live torrent search, so it is covered by the JVM screenshot tests
(`SourcePickerScreenshotTest`) instead.

Screenshots these flows save go to `$OUT`, not into the repo. The JVM screenshot tests keep their
reference images outside git too, in the folder named by `editotsu.screenshotDir`
(see `app/src/test/java/ani/dantotsu/screenshots/README.md` for where it is and how to record).

## known failure

`04` fails at 02611d11: restore drops the reading settings (`ani.dantotsu.reader.xml` comes back
empty). The JVM test `backup_then_restore_brings_reading_settings_back` is @Ignore'd for the same
bug; enable it with the fix.

## notes

- `05` taps the media page by position (see the script header), because that page never goes
  idle for `uiautomator dump`. The positions are for the AVD above.
- The app shows a few prompts on its own (link-handling question, donation prompt).
  `dismiss_prompts` in `lib.sh` answers "no" to them.
- `quiet_animations` turns off system animations and the app's banner animation on the emulator.
- Run the scripts with bash (`bash script.sh` or `./script.sh`), not by sourcing them in zsh.
- `show_screen` in `lib.sh` prints the labelled nodes on screen, for writing new flows.
