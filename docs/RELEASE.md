# Releasing a new version

## 1. Set the version

In `app/build.gradle.kts`, change only this line near the top:

```kotlin
val appVersion = "1.0.4"
```

- Always go up: `1.0.4` → `1.0.5` → … → `1.0.10` → `1.1.0` → `2.0.0`.
- Three numbers, each 0–99.
- Play's hidden version code follows it automatically: `1.2.3` → `10203`.

Then click **Sync Now** in the yellow bar.

## 2. Build the signed bundle

**Android Studio (clicks):**

1. **View → Tool Windows → Build Variants** → set **:app** to **release** (only once).
2. **Build → Generate App Bundles or APKs → Generate Bundles**
   (older versions: **Build → Build Bundle(s) / APK(s) → Build Bundle(s)**).
3. When it finishes, click **locate** in the popup at the bottom right.

The file is `app/build/outputs/bundle/release/app-release.aab`.

No password prompt: the upload key is read from `keystore.properties` (see *Upload key* below).
Set Build Variants back to **debug** before running the app from Android Studio again.

**Terminal (same result):**

```bash
./gradlew bundleRelease
```

## 3. Upload to Play Console

1. **Test and release → Testing → Internal testing → Create new release.**
2. Upload `app-release.aab`. It should show the new version, e.g. **10005 (1.0.5)**.
3. Release notes, one block per language:
   ```
   <ar>
   …
   </ar>
   <en-US>
   …
   </en-US>
   ```
4. **Next** (the "no deobfuscation file" warning is expected: shrinking is off) → **Save and publish**.
5. Update the app on your phone from Play and check it.
6. When it's good, add the same build to **Closed testing** (or **Production**):
   **Create new release → Add from library → pick the version**, then
   **Publishing overview → Send changes for review**.

## Testing on the phone before a release

Debug builds install as a separate app, `com.readqurantoday.quran.dev`, next to the Play version,
so the Play install is never touched. Run them from Android Studio with Build Variants on **debug**.

## Upload key

- Keystore: `C:\Users\ahmad\AndroidKeys\readqurantoday-upload.jks`, with copies in personal cloud storage
- Alias: `upload`
- SHA-256: `74:16:17:19:3C:03:E8:86:19:E4:CD:7A:13:AC:0C:A3:D4:9E:77:2C:69:13:5F:93:45:7F:91:B5:93:AA:E5:55`
- Password: in the saved password file and in the gitignored `keystore.properties` at the project root. Never commit either.

`keystore.properties` (project root, not in git):

```
storeFile=C:/Users/ahmad/AndroidKeys/readqurantoday-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

On a new computer, copy the `.jks` file and recreate `keystore.properties`; without it the release build is unsigned and Play rejects it.

Google keeps the real app signing key (Play App Signing). If the upload key is ever lost, Play support can reset it and the app stays safe.
