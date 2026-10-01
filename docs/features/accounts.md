# Accounts (feature/accounts)

Optional Firebase account on top of the guest profile: sign-in with Google or e-mail/password, verification and
password-reset mails, profile + app-preference sync, guest → account migration. Package `to.axolotl.cam.account`.
Guest mode never needs Firebase, a configuration file or internet.

## Configuration (no google-services plugin)

`app/build.gradle.kts` reads `app/google-services.json` (git-ignored) if it exists and exposes:

| BuildConfig field | Source in `google-services.json` |
| --- | --- |
| `FIREBASE_PROJECT_ID` | `project_info.project_id` |
| `FIREBASE_STORAGE_BUCKET` | `project_info.storage_bucket` |
| `FIREBASE_APP_ID` | `client[package_name == to.axolotl.cam].client_info.mobilesdk_app_id` |
| `FIREBASE_API_KEY` | same client, `api_key[0].current_key` |
| `FIREBASE_WEB_CLIENT_ID` | same client, `oauth_client[client_type == 3].client_id` (falls back to `services.appinvite_service.other_platform_oauth_client`) |

All fields are `""` when the file is missing, has no client for `to.axolotl.cam`, or still contains the `PLACEHOLDER`
values of `app/google-services.example.json`. Project id, app id and API key are required; without them
`FirebaseHandles` has no options and every account action returns `Result.failure(AccountNotConfigured)`; the sign-in
screens show "Konto-Dienst ist in dieser Installation nicht eingerichtet." and disable their buttons. The web client id
is only needed for Google sign-in, the storage bucket only for profile pictures.

`FirebaseApp` is initialised lazily with `FirebaseOptions` (default app name): on the first account action, or at app
start when this phone's profile is linked to an account (session restore + sync). A guest who never touches an account
button never initialises Firebase. The library's `FirebaseInitProvider` still runs at process start and logs
"FirebaseApp initialization unsuccessful" (no resources, because the plugin is not applied); that is expected and harmless.

Changing `google-services.json` needs a rebuild (it is a configuration-cache input).

## Owner setup (Firebase console)

1. **Project**: <https://console.firebase.google.com> → *Add project* (or reuse "my-forthing"). Analytics is not used.
2. **Android app**: *Project settings → General → Your apps → Add app → Android*. Package name `to.axolotl.cam`,
   nickname "Axolotl Cam". Skip the "add SDK" steps (the build reads the JSON itself).
3. **Fingerprints** (Google sign-in fails with "developer error" without them): *Project settings → Your apps →
   to.axolotl.cam → Add fingerprint*, add **SHA-1 and SHA-256** for every certificate that signs the app:
   - debug: `./gradlew :app:signingReport` (variant `debug`), or
     `keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android`
   - release: `keytool -list -v -keystore <upload-keystore.jks> -alias <alias>`
   - if the app is ever distributed through Google Play with Play App Signing, also the *app signing key* certificate
     from Play Console → *Test and release → App integrity*.
4. **Sign-in providers**: *Authentication → Get started → Sign-in method*:
   - enable **Email/Password** (leave "Email link (passwordless sign-in)" off);
   - enable **Google**, choose the project support e-mail, save. This creates the Web OAuth client (type 3) whose id
     the app passes to Credential Manager.
   - *Authentication → Settings → User actions*: keep **Email enumeration protection** on (the app shows the same text
     for "wrong password" and "unknown user", and the reset screen says "Falls es ein Konto für … gibt").
   - Optional: *Authentication → Templates* to set the sender name; the app requests German mails (`setLanguageCode("de")`).
5. **Google Cloud OAuth consent screen** (same project, <https://console.cloud.google.com/auth/branding>): app name, support
   e-mail; while the publishing status is *Testing*, add the test users (also needed by the Drive feature, which lives in
   the same project).
6. **Firestore**: *Firestore Database → Create database* → production mode, location e.g. `eur3`/`europe-west3`.
   *Rules* → paste [`firebase/firestore.rules`](../../firebase/firestore.rules) → *Publish*.
7. **Storage** (profile pictures; optional): *Storage → Get started*. New default buckets require the **Blaze**
   (pay-as-you-go) plan. Paste [`firebase/storage.rules`](../../firebase/storage.rules) → *Publish*. Without Storage
   everything works, the picture just stays on the phone (upload/download failures are logged, never shown).
8. **Download** `google-services.json` (*Project settings → Your apps*), **after** adding the fingerprints (otherwise
   `oauth_client` lacks entries), and save it as `app/google-services.json`. Rebuild; check that
   `app/build/generated/source/buildConfig/debug/to/axolotl/cam/BuildConfig.java` has non-empty `FIREBASE_*` values.

## Data model

Firestore `users/{uid}`:

```json
{ "displayName": "Mein Auto", "photoPath": "users/<uid>/avatar.jpg", "preferences": { "theme": "BLACK", "exportQuality": "Q1440" },
  "createdAt": <server timestamp, first write only>, "updatedAt": <server timestamp, every write> }
```

Storage `users/{uid}/avatar.jpg`: JPEG, longest side ≤ 512 px, EXIF rotation applied. The phone keeps the same
downscaled copy under `filesDir/profile/avatar-<uuid>.jpg` once the profile is linked (the guest copy is the original
picture until then). Firestore runs with the persistent cache, so a signed-in user without network keeps working from
`LocalProfile` + the cache; writes are queued and sent later.

### What syncs

| `AppPreferences` field | Synced | Why |
| --- | --- | --- |
| `theme` | yes | personal taste, hardware-independent |
| `exportQuality` | yes | personal taste |
| `platesLive`, `platesClips`, `liveUpscale` | no | depend on this phone's performance |
| `backupMode`, `backupOnMobileData`, `backupRequireInternetWifi`, `backupIncludePlateMetadata` | no | belong to the Google Drive feature, which is separate from the account |

Recorder settings are a different type and are never touched. Unknown keys/values written by a newer app version are
kept in the document and ignored locally.

While signed in (`ProfileSync.run`): a remote change (another phone) is applied to the phone; a local change of the
profile name (Konto screen) or a synced preference (Darstellung) is written to Firestore. Remote wins if both change at
the same moment.

## Linking and migration (`linkGuestProfile`)

Every successful sign-in links before leaving the sign-in screen; if linking fails (offline, error) the app signs out
again so no half-linked session remains. The merge decision reads `users/{uid}` from the **server** only (a stale
cache must not decide an overwrite).

| Phone profile | Account document | Strategy | Result |
| --- | --- | --- | --- |
| none (fresh install) | empty | – | profile created from the Google name / e-mail prefix, uploaded |
| none | has data | – | account profile downloaded |
| guest | empty | – | guest profile, picture (downscaled) and synced preferences uploaded |
| guest | has data | ASK | chooser: "Dieses Handy" (name, picture, created) vs "Konto" (name, picture, last changed) |
| guest | has data | KEEP_LOCAL / KEEP_REMOTE | the chosen side replaces the other |
| linked to the same account | has data | any | account copy applied (it is newer: changes made while signed in were written through) |
| linked to the same account | empty | any | phone profile uploaded again |
| linked to **another** account | any | ASK / KEEP_LOCAL | dialog "Anderes Konto" explains; cancel signs out |
| linked to another account | has data / empty | KEEP_REMOTE ("Wechseln") | account profile applied / phone profile moves into the account |

Nothing is overwritten without the user's decision when both sides hold a profile. The guest picture stays on the
phone unless the profile is linked. Sign-out keeps the profile on the phone (still marked as linked) and stops syncing;
Settings then shows "Abgemeldet · Anmelden".

`AccountState.SignedIn` is reported only when the Firebase user is the account this phone's profile is linked to.

## Screens and navigation

`accountGraph(navController)` registers SignIn, CreateAccount (step 1/2), VerifyEmail (step 2/2), ForgotPassword,
ResetSent, Upgrade and Account (new route `Account`). Settings → App → "Konto" (`AccountSettingsRow`) opens Upgrade
(guest), SignIn (signed out) or Account (signed in). Deviations from the artboards: no Apple sign-in, no e-mail code;
verification by link ("Mail-App öffnen", "Ich habe bestätigt", "Erneut senden" with 60 s cooldown, automatic check
when returning to the app and every 5 s while visible, "Später bestätigen"); the artboard's "Wrong email? Change it" is
not offered (sign out on the Konto screen instead). The Upgrade screen also offers e-mail/password and the terms
checkbox, and says that dashcam settings stay in the dashcam (the artboard claimed they move).

Google sign-in: Credential Manager with `GetGoogleIdOption` (web client id, random nonce, all accounts), then
`GoogleAuthProvider.getCredential(idToken)`. Sign-out clears the Credential Manager state.

## Tests

`app/src/test/java/to/axolotl/cam/account/`: `LinkDecisionTest` (matrix above), `ProfileSyncTest` (linking with fakes,
avatar downscale, live sync without echo), `SyncedPreferencesTest`, `AccountErrorsTest`, `NotConfiguredTest`
(no Firebase app is ever created), `AuthViewModelTest`, `VerifyEmailViewModelTest`. No test talks to Firebase.

## Limitations / open items

- **Real sign-in is untested** until `app/google-services.json` exists; only the not-configured path ran on the emulator.
- Account deletion is not offered in the app. Google Play requires an in-app deletion path for apps that create
  accounts; needed before a Play release (delete `users/{uid}`, the avatar and the Firebase user).
- Terms and privacy links point to placeholder pages under `Branding.supportUrl`.
- A picture replaced on another phone arrives only with the next sign-in (a missing one is fetched immediately).
- Changes made while signed out are replaced by the account copy at the next sign-in with the same account.
- Writes queued offline are lost if the user signs out before they reach the server.
- Unverified e-mail accounts can sync (the rules do not require `email_verified`).
- Release builds are not minified; enabling R8 later needs the Credential Manager keep rules.
- Dependencies add the permissions `INTERNET`, `ACCESS_NETWORK_STATE` (Firebase), `USE_BIOMETRIC`/`USE_FINGERPRINT`
  (androidx.credentials) and `READ_GSERVICES` (Play services) to the merged manifest.
