# My Forthing – Firebase and Google Cloud setup

What the owner registers so that the optional app account (Firebase) and the Google Drive backup work for package
`me.ri3d.dashcam`. Consolidated from [`features/accounts.md`](features/accounts.md) and
[`features/drive.md`](features/drive.md); those files keep the feature details. Without any of this the app runs as a
guest: accounts show "Konto-Dienst ist in dieser Installation nicht eingerichtet.", Drive ends with
"Google-Cloud-Konfiguration fehlt (Statuscode 10)". Nothing else depends on it.

## 0. One project for everything

Use **one** Google Cloud project for Firebase, the Drive API and, later, the web app (a Firebase project *is* a
Google Cloud project; reuse "my-forthing" or create a new one). With the scope `drive.file` Drive grants access per
file to the Cloud project whose OAuth client created the file, and `appProperties` are private to that project: a
web app in another project would see none of the backups (`DRIVE_FORMAT.md` §1). The app still keeps the two
features independent: Drive never calls Firebase, and the Drive account may differ from the app account.

Registrations made for an earlier package name (the working name "Axolotl Cam", or the package id used until
2026-10-02) do not apply; add `me.ri3d.dashcam` anew. The Firebase app and Android OAuth clients of an earlier package
name are unused and can be deleted.

## 1. Signing certificates to register

Every certificate that signs an installed build needs its fingerprints in Firebase (SHA-1 **and** SHA-256) and one
Android OAuth client in Google Cloud (SHA-1). Fingerprints are public values.

| Certificate | SHA-1 | SHA-256 |
| --- | --- | --- |
| **release** – `release.jks`, alias `myforthing` (`RELEASE.md`) | `50:BD:A2:FD:D2:EA:C5:C4:07:17:EA:ED:B5:34:6C:A4:9C:40:8B:76` | `82:A1:F7:29:C2:F8:8B:DB:FB:5F:02:BF:23:B7:63:3B:A1:4B:1F:C4:AC:7C:E2:7E:5D:D7:59:18:F9:E7:35:DC` |
| **debug** – this development machine (`%USERPROFILE%\.android\debug.keystore`) | `F3:D9:F2:BA:FB:0B:D9:F2:90:63:03:27:A5:BE:BE:30:55:51:B0:87` | `A2:99:D9:7A:16:96:B5:2C:51:61:28:97:CC:02:E3:C2:5E:51:53:4B:03:B5:41:89:8D:90:FC:FE:2B:36:48:21` |

Print them yourself (the debug key differs on every machine):

```bash
./gradlew :app:signingReport                      # variants debug and release (release only if local.properties has release.*)
keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
apksigner verify --print-certs dist/MyForthing-1.0.1-2-arm64-v8a.apk    # from a finished APK
```

If the app is ever distributed through Google Play with Play App Signing, also register the *app signing key*
certificate from Play Console → Test and release → App integrity.

## 2. Firebase (optional app account)

1. **Project**: <https://console.firebase.google.com> → *Add project* (or open the existing one). Analytics is not used.
2. **Android app**: *Project settings → General → Your apps → Add app → Android*: package `me.ri3d.dashcam`, nickname
   "My Forthing". Skip the "add SDK" steps; the build reads the JSON itself (no google-services plugin).
3. **Fingerprints**: *Your apps → me.ri3d.dashcam → Add fingerprint*: SHA-1 and SHA-256 of both certificates in section 1.
   Missing fingerprints make Google sign-in fail with a developer error.
4. **Sign-in providers** (*Authentication → Get started → Sign-in method*):
   - **Email/Password** on ("Email link (passwordless sign-in)" off);
   - **Google** on, project support e-mail set. This creates the Web OAuth client (type 3) that the app hands to
     Credential Manager;
   - *Authentication → Settings → User actions*: keep **Email enumeration protection** on;
   - optional: *Authentication → Templates* sender name (the app asks for German mails).
5. **Firestore**: *Firestore Database → Create database*, production mode, location e.g. `eur3` / `europe-west3`.
6. **Storage** (profile pictures, optional): *Storage → Get started*; new default buckets need the **Blaze** plan.
   Without Storage everything works; the picture just stays on the phone.
7. **Rules** (section 4).
8. **Download** `google-services.json` **after** step 3 (otherwise `oauth_client` lacks entries) and save it as
   `app/google-services.json` (git-ignored). Rebuild; `app/build/generated/source/buildConfig/<variant>/me/ri3d/dashcam/BuildConfig.java`
   must show non-empty `FIREBASE_*` values. Changing the file always needs a rebuild; an APK built without it has
   accounts disabled (as the 1.0.0 APKs in `dist/` built on 2026-10-02).

## 3. Google Cloud (Drive backup)

Same project, <https://console.cloud.google.com>. No client id, API key or JSON goes into the app for Drive: Google
matches the Android OAuth client by package name + signing certificate.

1. **Drive API**: *APIs & Services → Library → Google Drive API → Enable*. Missing → connecting works, every Drive
   call fails with HTTP 403 `accessNotConfigured`, shown as "Die Drive API ist im Google-Cloud-Projekt der App nicht
   aktiviert".
2. **OAuth consent screen** (Google Auth Platform, <https://console.cloud.google.com/auth/branding>): app name
   "My Forthing", support e-mail, audience **External**. *Data access → Add scope*
   `https://www.googleapis.com/auth/drive.file` (non-sensitive). While the status is **Testing**, add every Google
   account that should sign in or connect Drive under *Audience → Test users* (max. 100). In Testing, Google expires
   test users' Drive grants after **7 days** (the app then shows "Erneut verbinden"); publishing (brand verification;
   `drive.file` needs no security assessment) removes that.
3. **Android OAuth clients** (*Clients → Create client → Android*), one per certificate of section 1: package
   `me.ri3d.dashcam`, its SHA-1. Firebase may already have created one per fingerprint you added in section 2 ("Android
   client for me.ri3d.dashcam (auto created by Google Service)"); a package + SHA-1 pair can exist only once per project,
   so reuse that one instead of creating a second. Check the *Clients* list: one Android client for the debug SHA-1
   of every developer machine and one for the release SHA-1.
4. **Check**: install a build signed with a registered certificate → Einstellungen → Google Drive → "Mit Google Drive
   verbinden" → Google shows the Drive-file consent → the screen shows the account e-mail and "x von y belegt · z frei".
5. **Later, web app**: *Clients → Create client → Web application* in this project with its JavaScript origins,
   scope `drive.file` (`DRIVE_FORMAT.md`).

## 4. Firestore and Storage rules

The rules live in the repository: [`firebase/firestore.rules`](../firebase/firestore.rules) (only `users/{uid}`,
owner-only, validated profile shape) and [`firebase/storage.rules`](../firebase/storage.rules) (only
`users/{uid}/avatar.jpg`, owner-only, JPEG < 1 MiB). Everything else is denied.

Console (simplest): *Firestore Database → Rules* → paste `firestore.rules` → *Publish*; *Storage → Rules* → paste
`storage.rules` → *Publish*.

CLI alternative (Firebase CLI, `npm i -g firebase-tools`, `firebase login`): the repository has no `firebase.json`,
so create one locally (not needed in git) next to the `firebase/` folder:

```json
{ "firestore": { "rules": "firebase/firestore.rules" }, "storage": { "rules": "firebase/storage.rules" } }
```

then `firebase deploy --only firestore:rules,storage --project <project-id>`. Re-deploy whenever the rule files change.

## 5. Symptoms → cause

| In the app | Cause |
| --- | --- |
| "Konto-Dienst ist in dieser Installation nicht eingerichtet." | built without (or with a placeholder) `app/google-services.json` |
| Google sign-in fails ("Das hat nicht geklappt", developer error in the log) | fingerprint of the installed build missing in Firebase, or JSON downloaded before adding it |
| "Diese Anmeldemethode ist für die App nicht freigeschaltet." | provider not enabled (section 2 step 4) |
| "Google-Cloud-Konfiguration fehlt (Statuscode 10)" on Drive connect | no Android OAuth client for package + SHA-1 of the installed build |
| "… Drive API … nicht aktiviert (HTTP 403)" | Drive API not enabled (section 3 step 1) |
| Drive asks "Erneut verbinden" every week | consent screen still in Testing (7-day grants) |
| Profile picture not synced, everything else fine | Storage not set up (optional) |
