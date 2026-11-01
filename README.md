# DW Hunt

**English** | [简体中文](README.zh-CN.md)

An Android accessibility app that automatically hunts zombies in *Dark War: Survival*. It runs entirely on the phone or tablet: no PC, no root.

> **Disclaimer.** This is an unofficial project and is not affiliated with the game's publisher. Automating the game may violate its terms of service and could get your account restricted. Use it at your own risk.

## Features

- **Two targets**: giant zombies (rally, up to Lv.35) or wandering zombies (single attack, up to Lv.40). Each target has its own level setting.
- **Two ways to get stamina**:
  - *Free only*: when stamina runs out it only presses the free "Claim" buttons (the hero's free stamina and the 125 stamina supply). If none is ready it waits 10 minutes and tries again.
  - *Backpack packs*: when stamina runs out it uses stamina packs from your backpack (20 per refill), up to a cap you choose.
- Marches with **squad 1 only**, and checks the highlighted squad in the march popup before every march.
- If the target is already being rallied by someone else, it cancels and searches again.
- **Never spends diamonds.** It only taps free claims and backpack packs, never a "buy" button.
- Floating bar over the game: **▶** start, **■** stop, **⚙** change settings without leaving the game, **◂** collapse to a small square.

## Install and use

1. Install the APK. On Android 13+ a sideloaded app must first be allowed to use restricted settings: App info → top-right menu → *Allow restricted settings*.
2. System settings → Accessibility → turn on **DW 打僵尸** (the app's name).
3. Open the game, go to the **world map**, and tap **▶** on the floating bar at the top-left of the screen.
4. Tap **⚙** to choose the target, level, stamina source and number of rounds. Changes are saved at once and apply the next time you press ▶.

The app stops (instead of guessing) when the screen is not what it expects, for example when you are not on the world map.

For troubleshooting, the log is printed to logcat under the tag `DWHunt`: `adb logcat -d -s DWHunt:I`. In a debug build it is also in the app's private `files/bot.log`.

## Compatibility

- Fully tested only on a **2000×2800 tablet** with the game in **Simplified Chinese**. An early version also worked on a phone, but the current version has not been re-tested there.
- The templates are screenshots of Chinese buttons, so the game language must be Simplified Chinese.
- All coordinates are given for 2000×2800 and scaled by screen height. Scaling handles a bigger or smaller screen of the same shape, **not a different aspect ratio**, where the game lays out its UI differently. On another device you will probably need to re-capture the templates and adjust some coordinates.
- A game update that changes the UI can break the templates.

## How it works

- `Device.kt` takes screenshots with the accessibility service's `takeScreenshot`, taps with `dispatchGesture`, and uses OpenCV for template matching and colour counting.
- `Bot.kt` is the hunting loop: search a target → rally or attack → check that squad 1 is the selected squad → press march → wait for squad 1 to come home → next round. Anything unexpected stops the loop.
- `BotService.kt` is the accessibility service and the floating bar. `MainActivity.kt` is the settings screen.
- Templates live in `app/src/main/assets/templates/`. Each file name is the name used in `Bot.kt` (`find`, `findFast`, `pollFor`).

## Build

You need JDK 17 and the Android SDK (compileSdk 34, build-tools 35). Create `local.properties` in the project root:

```
sdk.dir=/path/to/Android/Sdk
```

Then:

```
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

For a signed release build, create a keystore, add a git-ignored `keystore.properties` in the project root, and run `./gradlew assembleRelease`:

```
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Dependencies: OpenCV 4.9.0 (Maven) and kotlinx-coroutines.

## Re-creating templates for another device

Each image in `templates/` is a crop of one UI element (a button, a title, a tab). On the new device take a full-screen screenshot, crop the same element, and save it under the same file name. If the aspect ratio is different, also check the reference coordinates in `Bot.kt`.

## License

The code is under the [MIT License](LICENSE). The images in `app/src/main/assets/templates/` are screenshots of the game's UI, owned by the game's publisher; they are not covered by the MIT license and are only used to recognise the screen.
