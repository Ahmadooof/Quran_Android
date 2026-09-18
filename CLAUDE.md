# Project Instructions

## Code quality
- No code that isn't needed yet — abstract only when duplication actually exists
- Single responsibility: each function and class does one thing
- Extract to a new file when a class doesn't belong where it lives
- A file growing long is a signal to split; keep files focused
- Match surrounding code style; don't introduce a new pattern just because it's newer

## Kotlin
- Prefer top-level functions over utility/helper classes
- Use `data class` for plain data holders
- Use `object` for singletons, not a class with a companion
- Prefer `val` over `var`; make mutability explicit when it's needed
- Use named arguments when a call has more than two parameters of the same type

## Android
- No business logic in Activities — Activities handle UI events and navigation only
- Prefer `RecyclerView` over `ListView`
- Use `ViewCompat` / `WindowInsetsControllerCompat` for insets, not deprecated APIs
- Don't call `findViewById` repeatedly — resolve once in `onCreate` or a `ViewHolder`
- Resource IDs in `R.*` over hardcoded strings/colors/dimensions
- Prefer KTX extensions: `str.toUri()` over `Uri.parse(str)`, `ifEmpty` over manual empty checks
- Use `_` for unused catch parameters: `catch (_: Exception)`

## Design

### Icons
- Follow the icon style shape of icons; keep it consistent — use premade outlined-stroke icons where possible.
- All chrome/nav icons use the same style: `strokeColor`, `strokeWidth`, `fillColor="#00000000"` (no fill). Filled icon = active state only.
- Icon tint is always set from code (`imageTintList`), never hardcoded in the drawable XML.

### Shared parts
- A row or control that appears on more than one screen lives in one layout part, included by both — never copied. Copies drift: the same button ends up a different colour or size in each list.
- Type sizes that repeat are `@dimen` tokens (`surah_title`, `surah_title_row`, `row_meta`), never numbers typed into a second layout or a `const` in a second file.

### Surah names
- A surah named as a row's own title is written in the mushaf's own hand: include `@layout/part_surah_title` and fill it with `fillSurahTitle()`.
- A surah mentioned inside a quiet meta line (a juz row, a search result) is plain text in the UI font — the calligraphy is for titles, not for asides.

### Dimensions
- Hardcoded dp/sp values that appear in more than one layout belong in `res/values/dimens.xml`.
- Use `@dimen/name` in XML. Key tokens already defined: `screen_margin`, `row_pad_v`, `card_pad_v`, `icon_nav`, `icon_row`, `chrome_row_height`, `icon_label_gap`, `nav_bar_height`, elevation levels.
- Add a new token to `dimens.xml` before copy-pasting a raw value into a second layout.

## Modern
- Follow modern APIs in design and code — do not use deprecated APIs.
- `android:tint` on `<ImageView>` is what this project uses; it works from API 21 and minSdk is 24. AppCompat prefers `app:tint`, so its `UseAppTint` check is turned off in `app/build.gradle.kts` — lint should stay clean, and a lint error should mean a real problem.

## Versions and releases
- The version lives in one place: `appVersion` in `app/build.gradle.kts`. Play's `versionCode` is worked out from it.
- Release and upload steps: `docs/RELEASE.md`. Play Console answers: `docs/PLAY_STORE.md`.