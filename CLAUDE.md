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

### Colors
- Always use semantic color tokens (`@color/accent`, `@color/text_mute`, `@color/surface`, …) — no hardcoded hex in layouts or code
- Two text levels: `text` for primary, `text_mute` for secondary/hints
- `accent_soft` for subtle tinted fills (chips, soft badges) — not full `accent`
- Define both light and `values-night` variants for any new color token

### Surfaces and elevation
- Prefer floating cards (`@drawable/card` with `elevation="1–2dp"`) over flat colored strips
- Use `elevation` to separate layers; avoid heavy outlines or thick dividers
- `stateListAnimator="@null"` on elevated views that shouldn't animate on press

### Press feedback
- **No ripple on buttons, icons, or nav** — never use `?selectableItemBackground` or `?selectableItemBackgroundBorderless`
- **List rows use `@drawable/row_bg`** — flat `@color/surface` base with `accent_soft` ripple; that is the ONLY place a ripple is correct
- Use a `ColorStateList` with `state_pressed` → `accent` for icon/text colour feedback on nav and toolbar buttons

### List rows
- Full-width, no side margins, no elevation — rows are separated by a 1dp `@color/line` divider, not by gaps or cards
- Hide the divider on the last item (set `View.GONE` in `onBindViewHolder` when `position == itemCount - 1`)
- Use `@drawable/row_bg` (surface + accent ripple) as the row background

### Icon tinting
- Always tint icons explicitly via `android:tint` in XML or `imageTintList` in code — never rely on a vector's own `android:tint` for theme-sensitive surfaces (it won't respond to theme switches correctly)
- Icons on chrome/dark surfaces: accent for primary actions, `text_mute` for secondary/inactive
- Marked state (bookmark, toggle): drive tint from code in `sayXxx()` functions, not from the vector file

### Touch targets
- Group a related icon + label into one `LinearLayout` as a single touch target (e.g. back button = icon + label in one container with one click listener)
- Don't wire separate click listeners on icon and label that do the same thing

### Background actions
- Tapping Play on a list row starts audio in the background — never navigate to the reader automatically
- Navigation to the reader happens only when the user taps the surah row itself (open), not play

### Icons
- Use outlined icons for the default/inactive state; filled when selected or active
- Nav icons: outlined at rest, filled on the active tab (swap via `setImageResource` in code)
- Icon size in nav: 22dp; in row actions: 20–22dp
- Tint icons via `imageTintList` in code so a single drawable works in all themes

### Bottom navigation
- No visible pill or background on the active tab — icon + label color alone mark selection
- No ripple on nav items; `duplicateParentState="true"` propagates press color to children
- Three tabs max; label font size 11sp
- Selected tab label is **bold**; unselected is normal weight — toggled via `setTypeface(null, Typeface.BOLD/NORMAL)` in code

### Search bar
- Pill shape (`corners 14dp`), surface fill, `elevation="3dp"`, no stroke
- Search icon leading (start edge in RTL), 17dp, `text_mute` tint
- Input text 14sp, hint `text_mute`, no underline (`android:background="@null"`)

### Spacing
- Screen-edge margin: 12dp for cards and containers
- Row padding (top/bottom): 10–12dp for list items, 11dp for compact cards
- Icon-to-text gap inside a row: 12dp