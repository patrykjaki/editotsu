# screenshot tests

Robolectric + Roborazzi screenshots of settings, the source picker, AnimeThemes rows,
player controls and the extensions empty states. The tests live in this folder.

## where the images live

Reference images are not in git. They live in the folder named by the gradle property
`editotsu.screenshotDir`, set in `local.properties`, `~/.gradle/gradle.properties` or with `-P`:

    editotsu.screenshotDir=/Users/crash/.editotsu/screenshots

Layout inside it: `settings/`, `picker/`, `themes/`, `player/`, `extensions/`.

## verify

The normal unit test run compares against them (`roborazzi.test.verify=true` in `gradle.properties`):

    ./gradlew testGoogleDebugUnitTest testFdroidDebugUnitTest

A changed pixel count above 0.2% fails the test and writes `*_compare.png` / `*_actual.png`
under `app/build/outputs/roborazzi/`.

Without the property, or when the folder doesn't exist (a fresh checkout, CI), the tests still
open every screen and run their assertions but skip the image comparison, so the run stays green.
A missing image inside an existing folder does fail: record it.

## record

After an intended UI change (or to create the folder), record and look at the new images:

    ./gradlew testGoogleDebugUnitTest testFdroidDebugUnitTest \
        -Proborazzi.test.verify=false -Proborazzi.test.record=true

With `editotsu.screenshotDir` unset, recording writes to `app/build/outputs/roborazzi-no-references/`.

## notes

Device: 393x870dp at 440dpi (about a Nothing Phone 3), sdk 36, native graphics.
`*_font130` files use font scale 1.3. Long lists are captured page by page (`_p1`, `_p2`, ...).
Settings sources/data/about keep one image per flavor (`_google`, `_fdroid`) because the
fdroid build hides a few rows.

Test data is fictional (`Fixtures.kt`): show "Andon Yakkyoku Nisshi", made-up songs and
release groups.
