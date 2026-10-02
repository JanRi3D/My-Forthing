# Feature: Google Drive authorisation and REST client (`feature/drive-auth`)

Package `me.ri3d.cam.drive`. Format for the web app: `docs/DRIVE_FORMAT.md`. Contract: `docs/CONTRACTS.md` §10.

## What it does

- **Connect** (Settings → App → Google Drive → "Mit Google Drive verbinden"): Google Identity Services `AuthorizationClient` (`play-services-auth` 22.0.0) asks for `https://www.googleapis.com/auth/drive.file` only. Google shows its account picker / consent screen; the result comes back through the activity result registry.
- **States**: `NotConnected`, `Connected(email, scopes)`, `NeedsReconnect(reason)`. `accessToken()` re-authorises silently for the stored account; when Google needs the user again, or the API answers 401 twice (once more after clearing the cached token), the state becomes `NeedsReconnect` with a German explanation and a one-tap "Erneut verbinden".
- **Konto wechseln**: account picker forced (`Prompt.SELECT_ACCOUNT`); after switching to another account the previous account's grant is revoked.
- **Trennen** (with confirmation, files stay in Drive): `revokeAccess` at Google, then the local record is deleted.
- **Independent of Firebase**: no Firebase dependency or call in `drive/`. Guests can connect; connecting never creates an app account; a signed-in user may connect a different Google account; signing in or out of the app account does not touch Drive.
- **REST v3 over OkHttp** (`DriveRestApi`): root folder + manifest, `media/<yyyy-MM>` folders, resumable uploads (8 MiB chunks, status query `Content-Range: bytes */total` to resume, persisted session URI, expired session restarted once), multipart JSON create/update, paged `files.list`, delete, `about` quota. Error mapping: 401 → refresh once, then `NeedsReconnect`; 403 `storageQuotaExceeded` → `InsufficientStorage`; 429 / 5xx / rate-limit 403 → up to 5 attempts with exponential backoff (1–16 s + jitter); network failure → `Offline`; 403 `accessNotConfigured` is shown as "Drive API nicht aktiviert".
- UI: `DriveAccount` route (`DriveAccountScreen`), `DriveSettingsRow`, reusable `DriveStatusCard(state, quota, action)` for the Backup screen, `Throwable.driveMessage()` for German error texts.

## Google Cloud setup (owner)

Without these steps "Mit Google Drive verbinden" ends with **"Google-Cloud-Konfiguration fehlt (Statuscode 10)"** (Play services `DEVELOPER_ERROR`; 28444 is mapped the same way). No client ID, API key or JSON file goes into the app: Google matches the Android OAuth client by package name + signing certificate.

1. **Project**: open <https://console.cloud.google.com>, create a project or pick the existing Firebase project (a Firebase project is a Cloud project; sharing it is fine, Drive still does not use Firebase). The future web app must use **this same project** (see `DRIVE_FORMAT.md` §1).
2. **Enable the API**: APIs & Services → Library → "Google Drive API" → Enable. (Missing → connect works, but every Drive call fails with HTTP 403 `accessNotConfigured`, shown in the app as "Die Drive API ist im Google-Cloud-Projekt der App nicht aktiviert".)
3. **OAuth consent screen** (Google Auth Platform): app name "My Forthing", support e-mail, audience **External**. Data access → Add scope `.../auth/drive.file` ("See, edit, create and delete only the specific Google Drive files you use with this app"; a non-sensitive scope). While the app is in **Testing**, add every Google account that should connect under Audience → Test users (max. 100).
   - Testing status: Google expires the grants of test users after **7 days**; the app then shows "Erneut verbinden". Publishing the app (brand verification only, `drive.file` needs no security assessment) removes the limit.
4. **Android OAuth clients** (Clients → Create client → Android), one per signing certificate:
   - Package name `me.ri3d.cam`. Clients created for the old package name do not match; create new ones.
   - **Debug** SHA-1: `./gradlew :app:signingReport` (variant `debug`) or `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`. Each developer machine has its own debug keystore → one client per machine.
   - **Release** SHA-1: `50:BD:A2:FD:D2:EA:C5:C4:07:17:EA:ED:B5:34:6C:A4:9C:40:8B:76` (`RELEASE.md` §2, `SETUP.md` §1), or `keytool -list -v -keystore <release.jks> -alias myforthing` (keystore path and alias in `local.properties` as `release.storeFile` / `release.keyAlias`, outside git, see `RELEASE.md`). If the APK is ever distributed through Google Play with Play App Signing, add a third client with the SHA-1 from Play Console → App integrity → App signing key.
5. **Check**: install the matching build, Settings → Google Drive → connect with a test user → Google shows the `drive.file` consent → the screen shows the account e-mail and "x von y belegt · z frei".
6. Later, for the web app: Clients → Create client → **Web application** in the same project, with its JavaScript origins.

## Security notes

- Scope `drive.file` only: the app can see and change only files it created (or the user opened with it), never the rest of the Drive.
- Access tokens are never persisted by the app; every call asks Play services (which caches them). After a 401 the stale token is cleared with `clearToken`. A second 401 only flags "Erneut verbinden" if the token belongs to the current connection (a late answer from before a reconnect is ignored).
- Stored (`DriveAccountStore`): account e-mail, granted scopes, connection time, reconnect flag – AES-256-GCM with a non-exportable Android Keystore key, in the private SharedPreferences file `drive_account`. Android backup and device transfer are disabled app-wide. `EncryptedSharedPreferences` (androidx.security-crypto) is deprecated, hence the direct Keystore use. Keystore work runs on `Dispatchers.IO`. A record that cannot be decrypted (Keystore reset) is dropped together with the key, which is recreated on the next save; a failed save keeps the connection in memory for this process.
- Disconnect forgets the account locally first, then revokes at Google without being cancellable (leaving the screen does not abort it).
- Any unexpected failure during connect (Keystore, Play services runtime errors) becomes a `Result.failure` and an error note, never a crash.
- No token, e-mail or response body is logged; warnings carry no personal data.
- Drive uses its own OkHttp client (`@DriveHttp`) for googleapis.com, separate from the recorder-bound client.

## Decisions and deviations from CONTRACTS §10 (additive)

- `DriveAuth.connect(activity, chooseAccount = false)` – `chooseAccount` for "Konto wechseln".
- `DriveAuth.invalidate(token, revoked = false)` – needed for "401 → refresh once → NeedsReconnect".
- `DriveAuthState.NeedsReconnect(reason, accountEmail = null)` – the e-mail for display and the one-tap reconnect.
- `DriveApi.uploadResumable(…, sessionUri, onSessionUri = {}, onProgress)` – hands out a new session URI so the backup queue can persist it.
- `DriveApi.ensureMonthFolder(rootId, month)` – `media/<yyyy-MM>`; month from `DriveFormat.monthFolderName(recorderTimeEpochGuess, downloadedAt ?: createdAt)`.
- `DriveError` sealed class: `NotConnected`, `NeedsReconnect`, `InsufficientStorage`, `Offline`, `Cancelled`, `Authorization(statusCode)`, `ScopeNotGranted`, `Http(code, reason)`.
- Connect fails unless the account e-mail can be read from Drive `about.user`: network problems and a missing e-mail → `Offline`, HTTP errors keep their reason (403 `accessNotConfigured` → "Drive API nicht aktiviert"). The grant at Google stays, so the retry is silent.
- Format: every v1 file also carries `mf.role` (root / manifest / folder / media / sidecar) so readers can tell media from sidecars without downloading content; sidecars carry `mf.format`, `mf.role`, `mf.id`. Files without `mf.role` are not part of the format.
- `delete(id)` deletes permanently (frees quota) and treats 404 as done. The backup feature should delete the sidecar too.
- Root folder found by `mf.role=root` (preferring the current name), so a renamed root is reused.
- The account e-mail is read from Drive `about.user` because `AuthorizationResult.toGoogleSignInAccount()` is deprecated in play-services-auth 22.
- OkHttp pinned to 5.4.0: 5.5.0 (`okhttp-android`) requires compileSdk 37.
- `kotlinx-coroutines-play-services` (for `Task.await`) is declared explicitly, on the coroutines version line.
- Upload retries are bounded: transient chunk failures (429 / 5xx / rate limit) and 308 answers without progress count against `maxAttempts` (5) with growing backoff; only a chunk that advanced the upload resets the count. A status query never does.

## For `feature/drive-backup`

`ensureRootFolder()` → `ensureMonthFolder(root, month)` → duplicate check `list(DriveFormat.mediaByIdQuery(id))` → `uploadResumable(file, DriveFormat.mediaFileName(…), mime, monthId, DriveFormat.mediaAppProperties(…), persistedSession, onSessionUri = ::persist)` → compare `md5Checksum` with `DriveFormat.md5Hex(file)` → `writeJson(DriveFormat.sidecarFileName(id), monthId, DriveSidecar(…).toJson(), DriveFormat.sidecarAppProperties(id))`. Backup status: `DriveFormatReader.scan(api)`. Run uploads serially (no folder lock).

## Limitations

- An activity recreation while Google's consent screen is open drops the result; the screen's coroutine is cancelled and the user taps "Verbinden" again.
- Right after app start the state reads "Nicht verbunden" for the few milliseconds until the stored record is decrypted off the main thread.
- `disconnect()` offline: the grant stays at Google until the user removes it at <https://myaccount.google.com/permissions>.
- No folder cache: each `ensureMonthFolder` costs two list calls; parallel uploads could create duplicate month folders (harmless for discovery).
- Upload progress is reported per 64 KiB on an OkHttp thread.
- Missing Cloud configuration is recognised by status codes 10 and 28444; other Play services codes are shown raw ("Google hat die Verbindung abgelehnt (Statuscode n)").

## Verification status

- Unit tests (JVM / Robolectric): `DriveRestApiTest` 15 (MockWebServer: resumable upload interrupted after a 308 and resumed from the persisted session, expired session restart, chunk failures bounded by `maxAttempts`, partial 308, 308 without `Range`, 5xx on a chunk followed by a status query, 401 on a chunk, 401 → refresh → retry, refresh allowance per call, second 401 → reconnect, 403 quota, paging + 503 retry, root folder + manifest, JSON update, offline), `DriveFormatTest` 9, `GoogleDriveAuthTest` 18 (fake authoriser, incl. non-cancellable disconnect, late-401 race, failing Keystore, e-mail lookup failure, no crash on a non-Compose activity).
- Emulator (`emulator-5554`, API 36, Play services): Settings row and DriveAccount screen render; "Mit Google Drive verbinden" opens Google's account picker; cancelling returns cleanly to "Nicht verbunden" without an error. The configuration-missing error was **not** triggered on the emulator because that requires choosing the owner's personal Google account in the picker.
- Owner checklist (needs the Cloud setup above): connect with a test user; consent shows only the Drive-file permission; quota shown; "Konto wechseln" to a second account; revoke the app at myaccount.google.com/permissions and check that the next Drive call leads to "Erneut verbinden"; disconnect; a build signed with an unregistered certificate shows "Google-Cloud-Konfiguration fehlt (Statuscode 10)" (confirm the code).
