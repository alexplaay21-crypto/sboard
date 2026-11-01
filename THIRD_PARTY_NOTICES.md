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
- `InputEventDispatcher.kt` and `DeleteRepeatSchedule.kt`: add progressively faster, bounded repeat timing for held character Backspace and Forward Delete; the short press and release cancellation remain handled by the upstream dispatcher.
- `AppPrefs.kt` and `assets/ime/theme/org.sboard.themes/**`: add sBoard light/dark keyboard themes adapted from upstream Apache-2.0 stylesheets and use them as new-install defaults.
- `FlorisImeService.kt`: opens the integrated keyboard settings screen.
- `res/xml/method.xml` and `res/xml/spellchecker.xml`: use sBoard branding and the integrated settings activity.
- `res/values/strings.xml` and `res/values-ru/strings.xml`: label the integrated host app as sBoard; upstream attribution remains in its About screen and this notice.
- `lib/snygg/build.gradle.kts`: decouples schema generation from consumer builds; the checked-in schema remains.
- `lib/snygg/src/test/.../TestColorSchemes.kt`: supplies the missing test-only color scheme fixture referenced by the upstream tests; it is not packaged in the application.

FlorisBoard sources and bundled assets are subject to the license notices shipped with the upstream revision. Third-party dependency licenses remain inventoried in `third_party/florisboard/app/src/main/config/licenses`; they must be included in the app's generated license screen before release.
