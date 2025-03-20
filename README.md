# OpenTagViewer

Apparently, the first Android App to allow you to view/track your AirTags (that's why I wrote this: because I couldn't find any app or webpage that lets me do this).

This project is a relatively polished looking Android/Java UI-wrapper around the Python [FindMy.py](https://github.com/malmeloo/FindMy.py) library.
(It still calls the Python library under the hood).

## Features ⭐

- View current "live" location of your AirTags on Android
- Track historical locations of your AirTags (a feature notably missing from the iOS FindMy apps!)
- UI customisation options


## How To Use 📖

### Requirements 🤓

1. An Android phone with the `OpenTagViewer` app installed
2. A (free) [Apple Account](https://account.apple.com/) with 2FA enabled to be via either `SMS` or `Trusted Device`
3. One or more **AirTags**, which need to be already registered to some Apple account via the `FindMy` app and shared with your account
4. A Mac or a [MacOS Virtual Machine](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags#prerequisites) of MacOS version `Sonoma (14)` or lower (only needed once/initially)

### How to view my AirTag on my Android Phone?!

See [📖 wiki](https://github.com/parawanderer/OpenTagViewer/wiki) for more details:

1. Install the app and log in to your Apple Account
2. Create an export `.zip` file by following [this wiki guide](https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Export-AirTags-From-Mac#opentagviewer-macos-export-app--recommended)
3. Import the `.zip` file in the app
4. Profit: you can now track your AirTags on your Android Phone indefinitely!


### Credits

UI Icons by Google: https://fonts.google.com/icons?icon.query=warn&icon.set=Material+Icons

Material theme colors by Google: http://material-foundation.github.io?primary=%23F4FEFF&bodyFont=Nunito&displayFont=Nunito+Sans&colorMatch=false


### License: MIT