# Drive restore (feature/drive-restore)

Owner request: after clearing the app data or on a new phone, reconnect the Google Drive account used before and show
every backed-up video and photo in the app again. Packages `backup/` (import, Drive-account hint, Drive tab state),
`media/` (Drive tab, Drive downloads), `drive/` (REST additions, silent reconnect, image auth), `account/` (synced key).
Contracts: `docs/CONTRACTS.md` §9 and §10. Nothing new is written to Drive; `docs/DRIVE_FORMAT.md` is unchanged.

| File | Contents |
| --- | --- |
| `backup/DriveRestore.kt` | `DriveRestore`: `importFromDrive()` (single flight), `importOnce(email)`, Drive thumbnails (`thumbnail(item)`), Drive-account hint (`followDriveAccount`) and silent reconnect (`reconnectFromHint`); `ImportReport`; `driveRow` (sidecar → row) |
| `backup/DriveLibrary.kt` | `DriveLibraryViewModel` (Drive tab and clip screen), `DriveMedia` (photo / thumbnail from Drive), `DriveDownloadAction` ("Vom Drive laden" / "Auf dem Handy speichern") |
| `media/DriveDownloads.kt` | `DriveDownloadQueue` + `DriveDownloadWorker`: the Drive copy to the phone, MD5-checked, WorkManager |
| `drive/DriveRestApi.kt`, `drive/DriveApi.kt` | `readJson(id)`, `download(id, target, onProgress)` (Range resume of `<target>.part`), `DriveFile.thumbnailLink`, `contentUrl(id)` |
| `drive/GoogleDriveAuth.kt`, `drive/DriveAuth.kt` | `reconnectSilently(accountEmail)` |
| `drive/DriveModule.kt` | `DriveAuthInterceptor`, `driveImageClient` |

## What it does

1. **Drive account follows the app account.** While Drive is connected, the e-mail of the Drive account is kept in the
   synced preference `driveAccount` (`AppPreferences.driveAccount`, DataStore `drive_account`); "Trennen" writes `""`.
   `ProfileSync` sends it to `users/{uid}.preferences.driveAccount` while signed in (it is only sent once this phone knew
   a value, so a guest who never connected Drive does not overwrite the account's value) and applies it from the account
   at sign-in. Values from the account that are neither `""` nor shaped like an e-mail address are ignored.
2. **Silent reconnect.** When nothing is stored on this phone and the hint is a non-empty e-mail (after sign-in on a
   fresh install, or at process start), `DriveAuth.reconnectSilently(email)` asks Google without UI: granted → connected
   exactly as "Verbinden" (e-mail read from `about.user`, scope check, record stored); Google needs the user →
   `NeedsReconnect(reason, email)` in memory only, so Settings → Google Drive, the Backup screen and the Drive tab show the
   existing one-tap "Erneut verbinden", which asks Google for that account; any error → stays "Nicht verbunden" (logged
   with the exception class only). Once per hint value per process; never after the user disconnected on this phone
   (`BackupStore.driveDisconnected`, kept until the next connect). Google UI is never launched by this.
3. **Import** (`DriveRestore.importFromDrive`): `DriveFormatReader.scan` → complete entries (media + sidecar, oldest
   media file per id) → ids whose row has no `driveFileId` yet → sidecars fetched 4 at a time → rows:
   - new row: id = `mf.id` (must be a UUID), kind/category/recorderType/originalFileName/recorderTime from the sidecar,
     `recorderTimeEpochGuess` as the recorder listing reads it, `downloadedAt` from the ISO time, `parentId` /
     `parentPositionMs` from `parent`, `driveFileId` = media file, `driveMd5` = its `md5Checksum`, `DONE`, `createdAt` =
     the media file's `createdTime`; no recorder or phone copy;
   - a row with that id but no Drive copy takes over the Drive fields and keeps everything else;
   - a row of the **same recorder file** (type + name + raw time) under another id without Drive copy (the recorder was
     listed or a clip downloaded before Drive was connected) is replaced by the Drive-id row, carrying over its recorder
     and phone copies – only when no derived item and no plate sighting refers to it, no recorder download or upload of
     it runs, and its phone copy (if any) has the Drive file's MD5. Otherwise both rows stay. The Drive id wins, so the
     backup never uploads that recording a second time;
   - an unusable sidecar (not parsable, other id, `backup.complete` false, unknown kind, MD5 differing from the media
     file) is skipped and counted (`ImportReport.unreadable`).
   A sidecar that cannot be fetched (offline, 5xx after retries) ends the import; rows written so far stay, the next run
   finishes it. Idempotent: a second run reads no sidecar and changes nothing. The end time is kept as
   `BackupStore.lastImport` ("Stand"), dropped by an account switch.
4. **When it runs** (one at a time; a second call gets the running one's result; never while not connected / reconnect
   needed – `DriveError.NotConnected` / `NeedsReconnect`):
   - automatically in `BackupQueue`'s observer the first time an account is connected (`lastImport` empty: fresh
     install, account switch, first start with this version), once per account and process, **before** the automatic
     rules queue anything – so a recording downloaded before Drive was connected takes its Drive id instead of being
     uploaded again;
   - "Drive-Status prüfen" (Backup screen) after the existing vanish handling; the snackbar adds "n Sicherungen aus Drive
     in die App übernommen";
   - the refresh icon of the Drive tab, and once per process when the Drive tab is first shown (fresh thumbnail links).
   Errors are German (`driveMessage()`) where the user started it (Drive tab snackbar, Drive check); automatic runs only
   log the exception class.
5. **Drive tab** (Aufnahmen → "Drive", after "Handy"): every row with `driveFileId`, newest first, day groups, the
   recorder row component with its chips ("Vorfall", "auf dem Handy", "In Drive gesichert"). Header "Stand: <last import>"
   (· "wird aktualisiert…" while it runs), the count and a refresh icon (spinner while importing). Not connected / reconnect
   needed: the Drive status card with "Mit Google Drive verbinden" / "Erneut verbinden" (→ Google Drive screen) above the
   rows already known. Tapping a row opens the clip screen; long press selects, the selection offers "Vom Drive laden".
6. **Thumbnails**: the local thumbnail when there is one, else Drive's `thumbnailLink`, loaded by the app's media Coil
   loader (same disk cache as the recorder thumbnails, `cacheDir/recorder_thumbs`, "Speicher → Cache") under the stable
   key `drive-thumb:<fileId>`, because the link expires. The loader's call factory sends requests for Google's hosts to
   `driveImageClient` (the `@DriveHttp` internet client with `DriveAuthInterceptor`), everything else to the recorder
   client as before. The interceptor adds `Authorization: Bearer` only to HTTPS requests for `googleusercontent.com` /
   `googleapis.com` and their subdomains; a 401 invalidates the token. Links live in memory: after a restart cached
   thumbnails show at once, missing ones after the tab's first import of the process.
7. **Clip screen** for an item without phone copy but with Drive copy: photos (`ORIGINAL_PHOTO`, `SCREENSHOT`,
   `ENHANCED_FRAME`) are shown from Drive (`files/<id>?alt=media` through the same loader, memory cache only) with "Auf dem
   Handy speichern"; videos show Drive's thumbnail and "Vom Drive laden" (they play once on the phone). A recorder copy
   still offers "Herunterladen". "Drive-Kopie löschen" and the three independent copies are unchanged.
8. **"Vom Drive laden"** (`DriveDownloadQueue`): WorkManager unique work `drive-download-<id>` (KEEP, any connected
   network, linear backoff 30 s), survives process death, one transfer at a time (process-wide slot; waiting works read
   "Wartet"), independent of the recorder's download slot. `DriveApi.download` writes
   `media/<id>/<name>.drive.part` (Range resume; a non-continuing answer or 416 starts over), then
   `media/<id>/<name>.drive`; its MD5 must equal `driveMd5`, else it is deleted ("Prüfsumme", German error); then the
   atomic rename to `MediaDownloader.targetFile` (where a recorder download would put it) and
   `MediaRepository.markDownloaded`. Progress, failures ("nicht mehr in Google Drive", "Google Drive hat die Datei nicht
   geliefert", "nicht verbunden", "Prüfsumme") and cancel appear in the row, the clip screen and the "Übertragungen" sheet
   ("… · aus Drive"), with the transfers notification "Aus Drive: <name>". Offline waits are retried without limit; every
   other failure ends the work (retry from the sheet). Speicher counts and frees interrupted `.drive` / `.drive.part` files
   (never those of a running transfer).

## Decisions and deviations from the brief

- Automatic import trigger: "the account has never been imported" (`lastImport` empty) instead of "adoptAccount changed
  or no account recorded yet". It covers both and also the first start after updating to this version (the owner's phone,
  connected since 1.0.4) and an automatic import that failed (retried at the next process start). A reconnect of the same
  account does not import again by itself (nothing was lost: disconnect keeps the rows); "Aktualisieren" does.
- The automatic import runs inside `BackupQueue`'s observer and the rules wait for it (see 4.). Without that the rules
  would queue a downloaded clip in the same pass as the connect, before the import could merge it.
- The Drive tab shows the status card **above** the rows already known instead of replacing them (NeedsReconnect after a
  revoke keeps phone copies viewable). It also imports once per process when first shown.
- `NeedsReconnect` from a silent attempt is not persisted (no record without the user's consent on this phone); the next
  process start tries silently again.
- The "user disconnected here" flag is persisted (until the next connect), not only per process: another phone of the
  same account would otherwise write the e-mail back and this phone would reconnect at its next start.
- No Room schema change (DB stays version 4): the Drive tab is a query on `driveFileId`; `lastImport` and the disconnect
  flag live in `BackupStore` (`backup_queue` SharedPreferences).
- `ProfileSync` now adds synced keys the account does not have yet (otherwise a key new in this version, like
  `driveAccount`, would only reach the account with the next change of another synced value).
- Drive downloads have their own part name (`.drive.part`), so a recorder download of the same item never appends to it.
- The tab row has five segments now; the segments drop Material's check mark (the filled segment still shows the
  selection) so the labels keep their room. Not seen on a phone yet; on narrow phones a label may still be shortened
  with "…".

## Limitations

- **Plates are not restored**: sidecar `plates` (only present with "Kennzeichen-Daten mitsichern") are ignored; the
  plate history stays on the phone it was recognised on.
- A recording whose recorder download or Drive upload runs at the moment of the import is not merged; it shows twice and
  may be backed up a second time under its other id.
- Rows of the same recording backed up twice under different ids (earlier reinstalls without this feature) both appear.
- The recorder copy of a Drive-only row is re-attached by the next listing of that type (type + name + raw time); until
  then the row has no recorder copy.
- `ponytail:` thumbnail links are kept in memory only (they expire within hours); a Drive download waiting for the slot
  holds a WorkManager slot (fine for a few).
- `downloadedAt` of a Drive download is the time of that download (the sidecar's value is replaced, as for a recorder
  download).
- Guests (no app account) connect Drive manually after a reinstall; the import then runs as above.
- Firestore rules allow up to 20 preference keys (`firebase/firestore.rules`); `driveAccount` is the third, no rule change.

## Owner checklist (phone, needs the Google Cloud setup of `drive.md`)

1. On the phone with 1.0.4 and Drive connected: install this build over it, start it once signed in → in the Firebase
   console `users/<uid>.preferences.driveAccount` holds the Drive account's e-mail; Aufnahmen → Drive lists the backups.
2. Settings → Apps → My Forthing → Speicher → "Daten löschen" (or install on a second phone), start, sign in with the same
   app account → without any tap Settings → Google Drive shows the account as connected (or "Erneut verbinden" with that
   account; one tap, Google's sheet preselects it).
3. Aufnahmen → Drive: every backed-up video and photo with thumbnails, "Stand: …", refresh icon works; airplane mode +
   refresh → snackbar "Keine Internetverbindung …".
4. Open a photo → shown from Drive; "Auf dem Handy speichern" → it appears in "Handy" and plays/shows offline.
5. Open a video → thumbnail + "Vom Drive laden" → progress in the row, the clip screen and "Übertragungen"; airplane mode
   halfway, then back online → continues (no restart from 0); the video plays afterwards.
6. Connect the recorder, list the incidents → the restored clips show their recorder copy again (one row each, no
   duplicates); with mode "Vorfälle" nothing that is already in Drive is uploaded again (Backup screen queue stays empty).
7. Settings → Google Drive → Trennen → `driveAccount` becomes `""` in Firestore; clear data again and sign in → Drive
   stays "Nicht verbunden".
8. A guest (no app account) after clearing data: connect manually → the Drive tab fills.

## Verification status

- Unit tests (JVM / Robolectric): `DriveRestoreTest` 13 (new rows with every field, derived parent, plates not restored,
  thumbnail key; same-id adoption; recorder-only merge and re-listing under the Drive id; phone copy merges only with the
  same MD5; rows with plates or children are not merged; incomplete / orphaned entries ignored, oldest duplicate wins;
  unusable sidecars counted; idempotent second run without sidecar reads; nothing while not connected / reconnect needed;
  failing sidecar read and finishing run; automatic import once per account, again after a switch, no write for the old
  account; Drive-account hint incl. disconnect; silent reconnect once per value, never after a disconnect here),
  `BackupQueueTest` +1 (first connect: import before the rules, the downloaded clip takes the Drive id, no upload),
  `DriveDownloadsTest` 4 (target location + markDownloaded, MD5 mismatch discarded, WorkManager run reported as Drive
  transfer, 404 → "nicht mehr in Google Drive"), `DriveRestApiTest` +5 (readJson, Range resume after an interrupted body,
  416 restart, 401 → refresh → retry, 404), `DriveAuthInterceptorTest` 3 (Google hosts over HTTPS only, look-alike
  hosts and cleartext without token, 401 invalidates), `GoogleDriveAuthTest` +4 (granted → connected + stored, needs UI →
  NeedsReconnect without UI and the one-tap reconnect for that account, errors → not connected, a stored connection is
  never replaced), `SyncedPreferencesTest` +2 (round trip with `""` and an e-mail, invalid values ignored),
  `ProfileSyncTest` +2 (fresh install receives the hint, a missing key is added to the account and `""` follows).
- Emulator: **not run**. The shared `emulator-5554` (AVD `Pixel_10_Pro_XL`) was in use by another session with the app in
  the foreground, and the AVD refuses a second instance unless the first runs `-read-only`; it was left alone. The Drive
  tab (not-connected card, five-tab row) is therefore only compiled and lint-checked, not seen.
- **Hardware-unverified**: everything against real Google Drive and a real Google account (silent authorisation after a
  reinstall, `thumbnailLink` with the Bearer header, `alt=media` Range answers, photo display, download speed), real
  Firebase sync of `driveAccount`.
