![opentagviewer_banner](https://github.com/user-attachments/assets/f26dfbc3-92d7-4af0-950f-e9446c7fb6b9)

<h1>
   <img src="./opentagviewer_icon_xs.png"/> OpenTagViewer
</h1>

[![Android build & tests](https://github.com/parawanderer/OpenTagViewer/actions/workflows/build-debug.yml/badge.svg?branch=main)](https://github.com/parawanderer/OpenTagViewer/actions/workflows/build-debug.yml)
[![Exporter tests](https://github.com/parawanderer/OpenTagViewer/actions/workflows/macos-scripts-python.yml/badge.svg?branch=main)](https://github.com/parawanderer/OpenTagViewer/actions/workflows/macos-scripts-python.yml)
[![Release](https://github.com/parawanderer/OpenTagViewer/actions/workflows/build-release.yml/badge.svg)](https://github.com/parawanderer/OpenTagViewer/actions/workflows/build-release.yml)
[![Apple's ADI libraries](https://github.com/parawanderer/OpenTagViewer/actions/workflows/check-adi-libraries.yml/badge.svg)](https://github.com/parawanderer/OpenTagViewer/actions/workflows/check-adi-libraries.yml)
[![Project Status: Inactive](https://www.repostatus.org/badges/latest/inactive.svg)](https://www.repostatus.org/#inactive)


> [!NOTE]
> This app is feature-complete as of app version `1.1.0` as far as the original author is concerned. Contributions are welcome and will be reviewed - get started through reading [CONTRIBUTING.md](./CONTRIBUTING.md) (and optionally having your agent read [AGENTS.md](./AGENTS.md)) which contain all details to get up and running.


This is an **<img src="https://github.com/user-attachments/assets/aa0531f6-6a5e-4c9f-b3c4-dfc3899c8a49" width="20"/> Android App** to allow you to view/track your **<img src="https://github.com/user-attachments/assets/fa3b912f-d204-4252-9449-465eb62f128c" height="20"/> official Apple AirTags**. It was made because Apple does not make any official app or webpage that lets you do this.
<br>

This project is a relatively polished looking Android/Java UI-wrapper around the Python [FindMy.py](https://github.com/malmeloo/FindMy.py) library, which is a derivative of the [openhaystack](https://github.com/seemoo-lab/openhaystack) project.

<br>

> [!WARNING]
> This project is not affiliated with Apple Inc. or Android/Google LLC in any capacity


|Video Demo|Demo: ☀️ Light Mode|Demo: 🌑 Dark Mode|
|----|----|----|
| <video src="https://github.com/user-attachments/assets/d3857480-4ef0-48a9-ab63-8d8c15fd5314"> |![Demo of the app while using Light Mode](./light_mode_preview.jpg)|![Demo of the app while using Dark Mode](./dark_mode_preview.jpg)|


(No, the location history in this demo isn't real)

## Features ⭐

- View current "live" location of your AirTags (or other FindMy devices) **on Android**
- Track & (automatically) save historical location history of your AirTags (a feature notably missing from the iOS FindMy apps!)
- UI customisation options


## What it works with 🏷️

**If it shows up under _Items_ in Apple's own Find My app, it works.**

| What | Works? | |
| --- | --- | --- |
| **AirTag** | ✅ | What most people are here for |
| **Third-party trackers that work with Apple Find My** (Chipolo, Pebblebee, Mili MiTag, eufy and similar) | ✅ | Should work; [reports welcome](https://github.com/parawanderer/OpenTagViewer/issues) |
| **AirPods, and other Find My accessories** | ✅ | Works where Apple stores a usable key for it. Lightly tested |
| **Self-made tags** ([OpenHaystack](https://github.com/seemoo-lab/openhaystack), [Macless Haystack](https://github.com/dchristl/macless-haystack)) | ✅ | Works; requires creating an export by [CLI](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Export-AirTags-With-The-CLI) or [Wizard](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Export-AirTags) |
| **Your own iPhone, iPad or Mac** | ⚠️ | Partial coverage (see issue [#131](https://github.com/parawanderer/OpenTagViewer/issues/131)) |
| **A tag someone shared with you** through Apple's own sharing | ❌ | Only the account that *owns* a tag can export it. Ask the owner to export it and send you the zip |
| **Tile, Samsung SmartTag, Google Find My Device trackers** | ❌ | Different networks entirely, with nothing in common with Apple's. This is an open feature request that can be contributed to the app if wanted. |

> [!NOTE]
> This app (currently) reads Apple's Find My network. Anything not on that network cannot be tracked with it, no matter how similar the device looks.


## How To Use 📖

### Requirements 🤓

1. An Android phone with [the `OpenTagViewer` app installed](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Install-App)
2. A (free) [Apple Account](https://account.apple.com/) with 2FA enabled to be via either `SMS` or `Trusted Device`
3. One or more of:
   1. **AirTags**;
   2. Any other "FindMy-compatible Accessory" or tracker that appears under *Items* in Apple's `FindMy` app
   3. [OpenHaystack](https://github.com/seemoo-lab/openhaystack)-like custom devices (see [what it works with](#what-it-works-with-))

**And then one of two routes**:
1. Read them from iCloud, in the app (requires only your Android phone)
2. Export a zip on a computer, import it (requires a computer once during export)

👉 [Which route should I use?](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Get-AirTags-into-App)


### How to view my AirTag on my Android Phone?!

See [📖 wiki](https://github.com/parawanderer/OpenTagViewer/wiki) for more details:

1. [Install the app](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Install-App) and log in to your Apple Account, and either (or both):
   1. Use the **iCloud login method** (when you are the owner of official FindMy-compatible devices that show up in the official FindMy app):
      1. Follow the steps [here](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Connect-Your-iCloud-Account) explaining how to link your account
   2. Use the **zip method**:
      1. Create an export `.zip` file by following [this wiki guide](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Export-AirTags#the-export-wizard--recommended)
      2. Import the `.zip` file in the app
2. Profit: you can now track your AirTags on your Android Phone indefinitely!

-------------

## Contributing

This started as a "hackathony" thing thrown together ASAP and made presentable for layusers, and plenty can still be improved. Contributions/MRs are more than welcome.

To get started with the repo, see:

📋 **[CONTRIBUTING.md](./CONTRIBUTING.md)**: covers getting set up (the JDK, the SDK, the Maps API key, the git hook) and how to run every test suite, as well as the CI setup.

📐 **[AGENTS.md](./AGENTS.md)**: the rules a change has to satisfy migrations, Anisette, API keys, attribution. Written for automated contributors, but contains useful information for people too (best queried via a coding assistant rather than read directly).

### Credits

- [UI Icons](https://fonts.google.com/icons?icon.query=warn&icon.set=Material+Icons) by Google
- [Material theme 3 library](https://github.com/material-components/material-components-android) + [colours](http://material-foundation.github.io?primary=%23F4FEFF&bodyFont=Nunito&displayFont=Nunito+Sans&colorMatch=false) by Google


### License: MIT

Do with it whatever you like, I don't really care :P
