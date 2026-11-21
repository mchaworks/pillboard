# Glimkey

An open-source Android keyboard with a rounded, Gboard-style layout:
**Material 3 Expressive** shape-morphing keys + **iOS-flavoured** looks and behaviour.

## Features
- Rounded keys that morph into pills on press (spring overshoot on release)
- iOS-style light/dark key colours; Material You accent for the action key
- Shift / double-tap caps lock, auto-capitalisation
- Double-space inserts ". "
- Hold backspace to repeat, haptic feedback
- No globe key: language switching stays with Android's own navigation-bar switcher
- Action key adapts (go / search / send / done / next) and shows a matching icon
- 123 symbols layer
- Strip button features (hold for the capsule, swipe to switch): Translate, Clipboard, Text editing, Settings. Clipboard and Text editing replace the whole keyboard with Blur Replace and go back the same way reversed
- Suggestions that learn: word bar, next-word prediction and typo-aware auto-correct, with Indonesian and English active at once. The language is inferred from what you type, never from system locales or a setting; learned words stay on the device (Settings > Typing / Advanced)

## Build
APKs are built by GitHub Actions: open the **Actions** tab, pick the latest run, download `pillboard-debug-apk`.

Locally (JDK 17 + Android SDK + Gradle 8.9+):
```
gradle assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Enable
Install the APK, open Glimkey, tap **Enable keyboard**, switch it on, then **Switch keyboard**.

## Roadmap
More languages: drop a `<code>.txt` into `app/src/main/assets/dict/` (format described at the top of `en.txt`); it is picked up automatically. PRs welcome.

## License
MIT
