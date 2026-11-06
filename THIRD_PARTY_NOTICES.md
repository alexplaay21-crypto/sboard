# Third-party notices

## FlorisBoard

- Source: https://github.com/florisboard/florisboard
- Integrated source release: `v0.5.2`
- Commit: `2e82060251897226c0739b9f52d1d051b02305fb`
- License: Apache License 2.0; full text is preserved at `third_party/florisboard/LICENSE`.
- The upstream repository contains no `NOTICE` file at this revision. Its source headers and `app/src/main/config/licenses` metadata are retained.
- sBoard-specific modifications are listed below; the keyboard remains in the upstream `dev.patrickgold.florisboard` namespace.

### Modified upstream files

- `FlorisApplication.kt`: made the runtime subclassable and removed the unused native demo library call so the host app can initialize it without an NDK module.
- `FlorisApplication.kt`: seeds English (US) and Russian subtypes once on a fresh sBoard install while preserving existing subtype selections.
- `LatinLanguageProvider.kt` and `LatinWordEngine.kt`: replace upstream demo NLP stubs with deterministic offline English prefix suggestions and one-edit spelling suggestions using the bundled English frequency map.
- `LatinLanguageProvider.kt` and `RankedWordList.kt`: add Russian offline prefix suggestions and one-edit spelling suggestions using the first 10,000 ranked forms from [hingston/russian](https://github.com/hingston/russian), pinned at `b144235f90fc720ef310f304e31acc3825d62a1b`. The list is under [CC BY 2.5](https://creativecommons.org/licenses/by/2.5/); the retained license statement is `third_party/florisboard/app/src/main/config/licenses/russian-words-license.txt`. The bundled list is filtered to alphabetic words of 2–40 letters and receives rank-derived scores.
- `InputEventDispatcher.kt` and `DeleteRepeatSchedule.kt`: add progressively faster, bounded repeat timing for held character Backspace and Forward Delete; the short press and release cancellation remain handled by the upstream dispatcher.
- `AppPrefs.kt` and `assets/ime/theme/org.sboard.themes/**`: add sBoard light/dark keyboard themes adapted from upstream Apache-2.0 stylesheets and use them as new-install defaults.
- `FlorisImeService.kt`: opens the integrated keyboard settings screen.
- `res/xml/method.xml` and `res/xml/spellchecker.xml`: use sBoard branding and the integrated settings activity.
- `res/values/strings.xml` and `res/values-ru/strings.xml`: label the integrated host app as sBoard; upstream attribution remains in its About screen and this notice.
- `lib/snygg/build.gradle.kts`: decouples schema generation from consumer builds; the checked-in schema remains.
- `ImeUiMode.kt`, `KeyCode.kt`, `TextKeyData.kt`, `KeyboardManager.kt`, `ComputingEvaluator.kt`, `PopupUiController.kt`, `QuickAction.kt`, `QuickActionArrangement.kt`, `FlorisImeService.kt`: add the sBoard Pass Vault panel (`ImeUiMode.VAULT`, key code `-214`) as the default sticky Smart Bar action and commit values approved in the sBoard confirmation activity.
- `AppPrefs.kt`: Samsung-Keyboard-like defaults — word suggestions, swipe typing, number row, symbol hints with hint priority, no fullscreen editor in landscape, lower landscape height.
- `layouts/charactersMod|symbolsMod|symbols2Mod/default.json`, `ComputingEvaluator.kt`, `FlorisApplication.kt`: Samsung-style bottom row (`!#1`, globe, comma, space, period, enter), `!#1`/`1/2`/`2/2`/`АБВ` labels and `western_samsung` symbol pages for new and migrated subtypes.
- `ImeUiMode.kt`, `KeyCode.kt` (`-215`), `TextKeyData.kt` and related files: translator panel (`org.sboard.keyboard.TranslateInputLayout`); toolbar order modelled on Samsung Keyboard; the settings key opens the sBoard app.
- `assets/ime/theme/org.sboard.themes/**`: eleven sBoard keyboard themes (graphite, light, AMOLED, outline, colour and high-contrast variants).
- sBoard-owned keyboard code lives outside the vendored tree in `keyboard/floris/src/main/kotlin/org/sboard/keyboard/`.
- `lib/snygg/src/test/.../TestColorSchemes.kt`: supplies the missing test-only color scheme fixture referenced by the upstream tests; it is not packaged in the application.

FlorisBoard sources and bundled assets are subject to the license notices shipped with the upstream revision. Third-party dependency licenses remain inventoried in `third_party/florisboard/app/src/main/config/licenses`; they must be included in the app's generated license screen before release.

## Other components

| Component | License | Use |
|---|---|---|
| BouncyCastle `bcprov-jdk18on` | MIT | Argon2id key derivation (fallback) |
| argon2kt (libargon2) | MIT / CC0 + Apache-2.0 | Fast native Argon2id |
| ZXing core + `zxing-android-embedded` (JourneyApps) | Apache-2.0 | Offline QR scanner for 2FA codes |
| zxcvbn4j (`com.nulab-inc:zxcvbn`) | MIT | Offline password strength estimation |
| OkHttp | Apache-2.0 | Google Drive REST, Digital Asset Links, translator |
| AndroidX (Compose, Room, WorkManager, Biometric, Credentials) | Apache-2.0 | UI and platform integration |
| Google Play services Auth | Android SDK license | Google OAuth authorization |

The translator calls the public MyMemory API (https://mymemory.translated.net) only when the user
taps “Translate”; it is subject to the service's terms of use.
