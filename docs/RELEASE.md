# My Forthing – Release build

How to build, sign, verify and hand out the release APKs of My Forthing (`me.ri3d.dashcam`), and how to produce the next
version. Distribution is by APK file (sideloading); there is no Play Store listing and no in-app updater in v1.
German instructions for testers: [`INSTALLATION.de.md`](INSTALLATION.de.md), [`BENUTZUNG.de.md`](BENUTZUNG.de.md).
Firebase / Google Cloud registration: [`SETUP.md`](SETUP.md).

## 1. Prerequisites

| What | Version / value |
| --- | --- |
| JDK | 21 (Android Studio's bundled JBR: `C:\Program Files\Android\Android Studio\jbr`) |
| Android SDK | platform `android-36.1` (compileSdk 36, minor API 1), build-tools 36.0.0 (for `apksigner` / `aapt2`) |
| Gradle | 9.5.0 through the wrapper (`./gradlew`, nothing to install); AGP 9.3.3 |
| Git | a clone of this repository |

Environment for a shell build (Git Bash):

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
export ANDROID_HOME="C:\Users\<you>\AppData\Local\Android\Sdk"
```

## 2. Files that are not in git

| File | Content | Without it |
| --- | --- | --- |
| `local.properties` (repository root) | `sdk.dir`, `dashcam.rsaKey`, `release.*` (below) | no build (`sdk.dir`), no recorder session, unsigned release |
| keystore, e.g. `release.jks` | the release signing key | unsigned release, no updates possible |
| `app/google-services.json` | Firebase configuration (`SETUP.md`) | accounts disabled ("Konto-Dienst ist in dieser Installation nicht eingerichtet."); everything else works |

`local.properties`:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
dashcam.rsaKey=<single-line Base64 PKCS#8 private key>
release.storeFile=release.jks   # relative to the repository root (an absolute path needs the escaped C\:/... form)
release.storePassword=<keystore password>
release.keyAlias=myforthing
release.keyPassword=<key password>
```

- `dashcam.rsaKey` is the recorder's session key from the owner's protocol evidence (the vendor app's RSA private key,
  see `docs/features/dashcam.md` → "RSA key"); it is never committed, logged or printed. Without it the app builds and
  runs but every connection ends in "Konfiguration fehlt" (-203).
- **Escape the path.** `local.properties` is a Java properties file: a backslash or the drive colon must be escaped,
  otherwise lint fails with `PropertyEscape`. Use forward slashes and `C\:` as above, or the fully escaped form
  `C\:\\Users\\Jan\\…\\release.jks`. A relative path is resolved against the repository root.
- All four `release.*` keys are required. If one is missing, Gradle prints
  `My Forthing: release APKs will be UNSIGNED - local.properties lacks …` and the release APKs are unsigned
  (Android refuses to install them). The keystore is PKCS12, so `release.keyPassword` equals `release.storePassword`.

Current release keystore: `C:\Users\Jan\Desktop\My Forthing\release.jks`, alias `myforthing`, RSA 4096,
SHA384withRSA, DN `CN=My Forthing, O=Jan Ried`, valid 2026-10-02 to 2054-02-17. Its password is only in
`local.properties` (and in the owner's backup, section 5).

Certificate fingerprints (public values; register them in Firebase / Google Cloud, `SETUP.md`):

| | SHA-1 | SHA-256 |
| --- | --- | --- |
| release (`myforthing`) | `50:BD:A2:FD:D2:EA:C5:C4:07:17:EA:ED:B5:34:6C:A4:9C:40:8B:76` | `82:A1:F7:29:C2:F8:8B:DB:FB:5F:02:BF:23:B7:63:3B:A1:4B:1F:C4:AC:7C:E2:7E:5D:D7:59:18:F9:E7:35:DC` |

## 3. Build from a clean clone

```bash
git clone <repository> my-forthing && cd my-forthing
# put local.properties (and optionally app/google-services.json) in place, see section 2
./gradlew clean :app:assembleRelease :app:assembleDebug
./gradlew :recorder:test :app:testDebugUnitTest :app:lintDebug     # must pass before anything is handed out
```

Output in `app/build/outputs/apk/release/` (signed builds only; unsigned ones keep AGP's default names):

```
MyForthing-<versionName>-<versionCode>-arm64-v8a.apk     almost every phone since ~2017
MyForthing-<versionName>-<versionCode>-armeabi-v7a.apk   old 32-bit phones only
```

and the universal debug APK in `app/build/outputs/apk/debug/app-debug.apk` (emulator / x86 tests, section 7).

Copy them to `dist/` (git-ignored) with checksums; the debug APK gets a name that cannot be mistaken for a release:

```bash
mkdir -p dist && cp app/build/outputs/apk/release/MyForthing-*.apk dist/
cp app/build/outputs/apk/debug/app-debug.apk dist/MyForthing-<versionName>-<versionCode>-debug-universal.apk
(cd dist && sha256sum MyForthing-*.apk > SHA256SUMS.txt)
```

Hand testers only the per-ABI release APKs; the debug APK is for the owner's emulator and ADB measurements
(`HARDWARE_CHECKLIST.md` H) and cannot update a release install (section 5).

**Build stability.** One clean `:app:assembleDebug` on `main` (`b153bf4`) once failed in `:app:packageDebug`
("A failure occurred while executing …PackageAndroidArtifact$IncrementalSplitterRunnable", no cause recorded) and passed
on re-run. It did not come back in eight further clean builds of the same code that reached packaging (four
`clean :app:assembleDebug`, three `clean :app:assembleRelease :app:assembleDebug`, one `clean :app:assembleRelease`);
only the universal debug output is packaged (one `packageDebug` work item), so the ABI outputs do not race for one file.
What did happen once is `Java heap space` in `:app:mergeReleaseResources` in a warm daemon that had just run lint and
the unit tests: with up to 32 parallel workers on this machine 2 GiB was too little, so `org.gradle.jvmargs` is now
`-Xmx4096m` (`gradle.properties`; four builds plus tests and lint passed with it). The splitter failure may have been
the same memory pressure; that is unproven, because its cause was not recorded. If a clean build still fails in
`packageDebug` / `packageRelease` or with `Java heap space`: run the same command again; if it fails twice,
`./gradlew --stop`, re-run with `--stacktrace` and keep the log.

Two clean builds of the same commit with the same `local.properties` and keystore on this machine give
byte-identical APKs (checked again for the final 1.0.0 release APKs; this needs `dependenciesInfo.includeInApk = false`,
because AGP otherwise adds a dependency list encrypted with a random key for Google Play). Across other JDK, SDK or
machine setups byte identity is not guaranteed; there, compare package, version and signing certificate (section 4).

## 4. Verify an APK

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/apksigner" verify --verbose --print-certs dist/MyForthing-1.0.0-1-arm64-v8a.apk   # apksigner.bat on Windows
"$BT/aapt2" dump badging dist/MyForthing-1.0.0-1-arm64-v8a.apk | grep -E "^package:|^application:|native-code"
(cd dist && sha256sum -c SHA256SUMS.txt)
```

Expected: `Verifies`, signer DN `CN=My Forthing, O=Jan Ried`, certificate SHA-256 `82a1f729…f9e735dc` (table above),
`package: name='me.ri3d.dashcam' versionCode='1' versionName='1.0.0'`, `application: label='My Forthing'`, one
`native-code` ABI per APK, no `application-debuggable`. The APK is signed with scheme v2 only (minSdk 26 does not need v1).

On Windows without Git Bash: `Get-FileHash -Algorithm SHA256 <file>` (PowerShell) or `certutil -hashfile <file> SHA256`.

## 5. Signing, updates and keystore backup

Android installs a new APK over an installed one only if **all** of these hold:

1. same package (`me.ri3d.dashcam`),
2. same signing certificate – the `myforthing` key in `release.jks`,
3. `versionCode` not lower than the installed one. Android accepts an equal versionCode as a reinstall, but two
   different builds must never share a number (nobody could tell them apart), so **every APK that leaves this
   machine gets a strictly higher `versionCode`**.

Consequences:

- **Lose the keystore or its password = no more updates.** Every user then has to uninstall, which deletes the
  app's data on the phone (profile, downloads, screenshots, enhanced files, plate history). Drive backups and the
  Firebase account survive. Keep **both** the file `release.jks` and the four `release.*` values, together:
  - one copy in the password manager (1Password item "My Forthing release keystore": the file as an attachment, the
    password, alias `myforthing`, the SHA-256 fingerprint from section 2 to recognise it),
  - one offline copy (encrypted USB stick or similar), not on the same disk as the repository.
  - Check a backup once by building with it on another checkout and comparing the certificate (section 4).
- Debug builds are signed with the machine's debug key (`%USERPROFILE%\.android\debug.keystore`); they share the
  package name, so a debug build and a release build **cannot update each other**. Switching between them needs an
  uninstall (data loss as above). Use a separate test phone or the emulator for debug builds.
- Each ABI APK carries the same versionCode; a phone only ever has one of them installed, so that is fine for
  sideloading. (Google Play would need distinct versionCodes per ABI, or an AAB; see section 9.)
- The older app `me.ri3d.myforthing` from the sibling project is a different package; both can be installed side by side.
- Builds made before 2026-10-02 used an earlier package id: such a build is a separate app too and stays installed
  next to this one (two "My Forthing" icons). Uninstall it; its data does not carry over.

## 6. Next version

1. Integrate and test on `main` (unit tests, lint, the relevant hardware checklist items).
2. Bump in `gradle.properties`:
   - `myforthing.versionCode` – always +1 for every APK handed out (also for a rebuild that only fixes packaging);
   - `myforthing.versionName` – `MAJOR.MINOR.PATCH`: PATCH for fixes, MINOR for new features, MAJOR for breaking
     changes (e.g. a new Drive format version). The name is for people; Android only compares the code.
3. Commit the bump ("release: 1.0.1 (2)"), tag it (`git tag v1.0.1`).
4. Build, verify and copy to `dist/` (sections 3–4) with the **same keystore**.
5. Hand out the APK for the tester's ABI plus `SHA256SUMS.txt`; testers install over the old version
   (`INSTALLATION.de.md`, "Aktualisieren").

`-Pmyforthing.versionCode=…` on the command line overrides `gradle.properties` for experiments; never hand out such a
build without committing the number.

## 7. Debug vs release

| | debug | release |
| --- | --- | --- |
| Signing | machine debug key | `release.jks` (`myforthing`) |
| APKs | one universal `app-debug.apk` (arm64-v8a, armeabi-v7a, x86, x86_64 – runs on the x86_64 emulator; in `dist/` as `MyForthing-<versionName>-<versionCode>-debug-universal.apk`) | one per ABI: arm64-v8a, armeabi-v7a (no x86) |
| Recorder simulator | Verbindung → "Entwickler" → "Simulator (10.0.2.2:7878)" (`BuildConfig.DEBUG`), `./gradlew :recorder:runSimulator` | not available (the switch is not shown and ignored) |
| Cleartext HTTP | `192.168.42.1` and `10.0.2.2` (simulator media server, `src/debug/res/xml/network_security_config.xml`) | `192.168.42.1` only |
| Logging | `Log.d` through `core/log/Log.kt` | no debug logs (warnings only, never tokens / keys / passwords) |
| Debuggable | yes | no |
| R8 / minify | off | off (section 8) |

ABI splits are global in the AGP DSL (no per-build-type switch, checked against the AGP 9.3.3 API): the build
enables arm64-v8a + armeabi-v7a splits with a universal APK, and `androidComponents.onVariants` keeps only the
universal output for debug (named `app-debug.apk` as before) and only the per-ABI outputs for release.

## 8. Size

| APK | Size |
| --- | --- |
| `MyForthing-1.0.0-1-arm64-v8a.apk` | 36,379,986 bytes (34.7 MiB) |
| `MyForthing-1.0.0-1-armeabi-v7a.apk` | 30,253,890 bytes (28.9 MiB) |
| `MyForthing-1.0.0-1-debug-universal.apk` (`app-debug.apk`, for comparison) | 88,372,816 bytes (84.3 MiB) |

Inside the arm64 APK: dex 17.1 MB compressed (≈ 47 MB uncompressed, five dex files), native libraries 15.6 MB
stored uncompressed (ML Kit OCR 11.1 MB, LiteRT 4.5 MB), `resources.arsc` 1.6 MB, assets (OCR and
super-resolution models, licence texts) 1.6 MB.

R8 is deliberately off (`optimization { enable = false }`): no shrinking, no obfuscation, so the dex is the largest
part. Enabling it would likely cut the dex part substantially, but first needs keep rules and a full re-test of the
release build: Credential Manager (`androidx.credentials.playservices` provider loaded by reflection) and `googleid`,
ML Kit text recognition, LiteRT (JNI), kotlinx.serialization (`@Serializable` routes and Drive JSON), Media3 RTSP,
WorkManager/Hilt workers. `app/src/main/keepRules/rules.keep` is the place for them.

## 9. Not covered

- Google Play: would need an AAB (`./gradlew :app:bundleRelease`, with `dependenciesInfo.includeInBundle` back on so
  Play can scan the dependencies), Play App Signing (register the app signing key's fingerprints too, `SETUP.md`),
  in-app account deletion and a privacy policy.
- CI: none in this repository (the sibling project builds on GitHub Actions with the keystore as a secret).
- Google has announced developer verification for apps installed outside the Play Store on certified Android
  devices (first countries from September 2026, wider rollout from 2027). Check the current rules before handing out
  APKs in a region where it applies; it may require registering the package and this signing certificate.
