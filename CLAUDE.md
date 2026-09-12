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
