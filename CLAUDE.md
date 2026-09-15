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

### Dimensions
- Hardcoded dp/sp values that appear in more than one layout belong in `res/values/dimens.xml`.
- Use `@dimen/name` in XML. Key tokens already defined: `screen_margin`, `row_pad_v`, `card_pad_v`, `icon_nav`, `icon_row`, `chrome_row_height`, `icon_label_gap`, `nav_bar_height`, elevation levels.
- Add a new token to `dimens.xml` before copy-pasting a raw value into a second layout.

## Modern
- Follow modern APIs in design and code — do not use deprecated APIs.
- `android:tint` on `<ImageView>` is valid for this project (minSdk / target ≥ API 31). The AppCompat lint warning about `app:tint` only applies to API < 21 compatibility which this project does not need — ignore it.