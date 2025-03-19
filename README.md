# OpenTagViewer

WIP write new introduction

The apparently first Android App to allow you to view/track your AirTags

## Features ⭐

- View current "live" location of your AirTags on Android
- Track historical locations of your AirTags (a feature notably missing from the iOS FindMy apps!)


## How To Use 📖

### Prerequisites 🤓

1. An Android phone with the `OpenTagViewer` app installed
2. A (free) [Apple Account](https://account.apple.com/) with 2FA enabled to be via either `SMS` or `Trusted Device`
3. One or more **AirTags**, which need to be already registered to some Apple account via the `FindMy` app and shared with your account
4. A Mac or a [MacOS Virtual Machine](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags#prerequisites) of MacOS version `Sonoma (14)` or lower (only needed once/initially)

### How to view my AirTag on my Phone?!

See [📖 wiki](https://github.com/parawanderer/OpenTagViewer/wiki) for more details:

1. Install the app and log in to your Apple Account
2. Create an export `.zip` file by following [this wiki guide](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags)
3. Import the `.zip` file in the app
4. Profit: you can now track your AirTags on your Android Phone indefinitely!


-----------

TODO: wip clean it up



My **Heavily WIP** attempt at making AirTag tracking available via a Map-based UI (like that of iOS FindMy or Samsung SmartThings) on Android via this great project: **[FindMy.py](https://github.com/malmeloo/FindMy.py)**

Android (Java) + Python via Chaquopy

Currently this is not really usable yet due to lack of features & polish (it only shows the icons on the map and refreshes them in specific scenarios or on the manual refresh button click).

In the future it might become practically usable, in which case I will do my best to make it easily available/installable.

### Sneak-peak current state:

![Sneakpeak 11 March 2025](./docs/11_03_2025_sneakpeak_2.png)


### Credits

UI Icons by Google: https://fonts.google.com/icons?icon.query=warn&icon.set=Material+Icons

Material theme colors by Google: http://material-foundation.github.io?primary=%23F4FEFF&bodyFont=Nunito&displayFont=Nunito+Sans&colorMatch=false

-----------------------

## Sections to be added/moved/etc...:


#### Notes

It would be nice if `fetch_last_reports` in `FindMy.py` could support fetching reports in smaller time ranges than hours. For example, I can cache historical results, but I would be interested in getting recent data only.
