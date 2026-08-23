---
name: device-screenshots
description: Render app UI on the Gradle managed device and look at the result without spending a fortune in vision tokens. Use whenever a change is visual - colours, themes, drawables, layout - or when a screenshot needs comparing before and after, light and dark.
---

# Looking at the app's UI

Two halves: getting a screenshot off the device, and reading it cheaply. The second is the part
that is easy to get wrong.

## Never Read a raw screenshot

A device screenshot is ~1080px wide and mostly whitespace. Reading one costs a lot of vision
tokens for a picture whose informative part is a few hundred pixels, and reading a before/after
pair separately doubles it for a comparison that wants to be side by side anyway.

Run them through the sheet tool first:

```bash
python .claude/skills/device-screenshots/sheet.py \
    app/build/outputs/managed_device_android_test_additional_output/debug/testEmulator \
    <scratchpad>/shots
```

Then Read the sheets it prints. One subject, all its variants, one image, a few hundred pixels
wide.

Files are grouped on the part before the last hyphen; the part after is the variant label:

```
my_device_list_item-fixed.png       ->  subject my_device_list_item, variant "fixed"
my_device_list_item-wallpaper.png                                    variant "wallpaper"
```

| Flag | Default | For |
| --- | --- | --- |
| `--width` | 420 | target width per panel |
| `--max-height` | 420 | cap, so a tall image cannot produce a 2000px sheet |
| `--dark` | `dark,wallpaper_dark,night` | variants composited over black rather than white |

Needs Pillow (`python -m pip install pillow`).

## Screenshots that go somewhere public

A capture destined for the wiki or an issue is a screenshot of *a real build on a real device*,
and some of what is on it belongs to whoever took it. The AMap key dialog is the worked example:
it prints the package name and signing fingerprint, because that is exactly what AMap's console
asks you to paste — correct behaviour, and not something to publish.

```bash
py -3 .claude/skills/device-screenshots/blur.py shot.png --band 0.42 0.55
```

`--band` takes fractions of the height, so it survives a change of device resolution; repeat it
for more than one strip. Blur rather than crop — a cropped dialog looks like it has fewer fields
than it does, and the next person wonders what was removed. It pixelates before blurring, so the
characters are gone rather than merely soft.

Worth a look before publishing any capture: a signed-in email address, a real street name from
the geocoder, a `XXXX-XXXX-XXXX` bundle passcode, coordinates. The capture classes fabricate all
of those on purpose; a hand-taken screenshot does not.

## Getting the screenshots

`SystemColorsLayoutTest` is the working example. Inflate a layout — or load a drawable —
against a themed context, draw it to a `Bitmap`, and write it to the directory AGP passes as the
`additionalTestOutputDir` instrumentation argument. AGP copies that back to the host after the
run:

```
app/build/outputs/managed_device_android_test_additional_output/debug/testEmulator/
```

Run it on the managed device, which provisions and tears down its own emulator:

```bash
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew :app:testEmulatorDebugAndroidTest
```

No activity is needed for most of this. Inflating the layout against a
`ContextThemeWrapper(context, R.style.Theme_OpenTagViewer)` on the main thread is enough, and it
avoids every problem that comes with launching one.

## Two traps, both hit here already

**Transparency.** Several layouts have no background of their own. Converting them straight to
RGB renders every transparent pixel black, and the result looks like a catastrophic bug that is
not there. The tool composites over a background; do the same anywhere else.

**Images are not assertions.** A screenshot proves what one configuration looked like once.
Whatever the change is, assert it as well — a resolved colour, a contrast ratio, a measured
height. `SystemColorsLayoutTest` requires the timeline tiles to clear WCAG 3:1 against their
background, which is what actually fails the build; the images are what explain why.

And be careful what the assertion is *about*. That same test renders drawables through a themed
context, so it kept passing while the history timeline was invisible in the app — the app was
loading them with a null theme. The test proved the drawable could render, not that the app asked
for it correctly. `ThemedDrawableLoadingTest` covers the call site instead.

## Dark mode

Rendering the day theme only is the usual mistake — the app has a full `values-night` palette. A
themed context can be forced to night without changing the device:

```java
Configuration night = new Configuration(context.getResources().getConfiguration());
night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
        | Configuration.UI_MODE_NIGHT_YES;
Context darkContext = context.createConfigurationContext(night);
// then wrap in ContextThemeWrapper(darkContext, R.style.Theme_OpenTagViewer)
```

Name those outputs `-dark` so the sheet tool composites them over black.

## Related

- `AGENTS.md` rule 2 — do not claim something works if you have not run it. A rendered sheet is
  evidence; "it should look right" is not.
- `CONTRIBUTING.md` for how the managed device is configured.
