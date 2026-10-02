# Drive restore (feature/drive-restore)

Owner request: after clearing the app data or on a new phone, reconnect the Google Drive account used before and show
every backed-up video and photo in the app again. Packages `backup/` (import, Drive-account hint, Drive tab state),
`media/` (Drive tab, Drive downloads, merge in the library), `drive/` (REST additions, silent reconnect, image auth),
`account/` (synced key). Contracts: `docs/CONTRACTS.md` §9 and §10. Nothing new is written to Drive;
`docs/DRIVE_FORMAT.md` is unchanged.

| File | Contents |
| --- | --- |
| `backup/DriveRestore.kt` | `DriveRestore`: `importFromDrive()` (single flight), `importOnce(email)`, the merge of "unmerged twins", Drive thumbnails (`thumbnail(item)`), Drive-account hint (`followDriveAccount`) and silent reconnect (`reconnectFromHint`); `ImportReport`; `driveRow` (sidecar → row) |
| `backup/DriveLibrary.kt` | `DriveLibraryViewModel` (Drive tab and clip screen), `DriveMedia` (photo / thumbnail from Drive), `DriveDownloadAction` ("Vom Drive laden" / "Auf dem Handy speichern") |
| `media/DriveDownloads.kt` | `DriveDownloadQueue` + `DriveDownloadWorker`: the Drive copy to the phone, MD5-checked, WorkManager, backup network conditions |
| `media/MediaRepository.kt` | `importDriveCopy`, `unmergedTwins`, `mergeIntoDriveCopy` (moves children and plate sightings), `currentId` |
| `drive/DriveRestApi.kt`, `drive/DriveApi.kt` | `readJson(id)`, `download(id, target, onProgress)` (Range resume of `<target>.part`), `DriveFile.thumbnailLink`, `contentUrl(id)` |
| `drive/GoogleDriveAuth.kt`, `drive/DriveAuth.kt` | `reconnectSilently(accountEmail)` |
| `drive/DriveModule.kt` | `DriveAuthInterceptor`, `driveImageClient` |

## What it does

1. **Drive account follows the app account.** While Drive is connected, the e-mail of the Drive account is kept in the
   synced preference `driveAccount` (`AppPreferences.driveAccount`, DataStore `drive_account`); "Trennen" writes `""`.
   `ProfileSync` sends it to `users/{uid}.preferences.driveAccount` while signed in (it is only sent once this phone knew
   a value, so a guest who never connected Drive does not overwrite the account's value) and applies it from the account
   at sign-in. Values from the account that are neither `""` nor shaped like an e-mail address are ignored.
   **Last writer wins:** phones of one app account connected to different Drive accounts each write their own e-mail when
   their Drive state changes (connect, switch, disconnect, process start); a fresh install reconnects the account written
   last.
2. **Silent reconnect.** When nothing is stored on this phone and the hint is a non-empty e-mail (after sign-in on a
   fresh install, or at process start), `DriveAuth.reconnectSilently(email)` asks Google without UI: granted → connected
   exactly as "Verbinden" (e-mail read from `about.user`, scope check, record stored); Google needs the user →
   `NeedsReconnect(reason, email)` in memory only, so Settings → Google Drive shows "Erneut verbinden", which asks Google
   for that account (the Backup screen and the Drive tab lead there with their own "Erneut verbinden"); any error →
   stays "Nicht verbunden" (logged with the exception class only). Once per hint value per process; never after the user
   disconnected on this phone (`BackupStore.driveDisconnected`, kept until the next connect). Google UI is never
   launched by this. "Trennen" on such a pending reconnect only forgets it locally (no `revokeAccess`, which would end
   another phone's grant of that Google account) and writes `""` as usual.
3. **Import** (`DriveRestore.importFromDrive`): `DriveFormatReader.scan` → complete entries (media + sidecar, oldest
   media file per id) → ids whose row has no `driveFileId` yet → sidecars fetched 4 at a time → rows:
   - new row: id = `mf.id` (must be a UUID), kind/category/recorderType/originalFileName/recorderTime from the sidecar,
     `recorderTimeEpochGuess` as the recorder listing reads it, `downloadedAt` from the ISO time, `parentId` /
     `parentPositionMs` from `parent`, `driveFileId` = media file, `driveMd5` = its `md5Checksum`, `DONE`, `createdAt` =
     the media file's `createdTime`; no recorder or phone copy;
   - a row with that id but no Drive copy takes over the Drive fields and keeps everything else;
   - an unusable sidecar (not parsable, other id, `backup.complete` false, unknown kind, MD5 differing from the media
     file) is skipped and counted (`ImportReport.unreadable`); it is read again by every later import.
   Then the **"unmerged twins" pass** (one join query over the library): every row without Drive copy that is the same
   recorder file (type + name + raw time) as a Drive row – the recorder was listed or a clip downloaded before Drive was
   connected, or an earlier merge was refused – is merged into the Drive row in one transaction: the Drive row takes its
   recorder copy and, when it has none, its phone copy; derived items (`parentId`) and plate sightings move to the Drive
   id; the row's backup bookkeeping goes; a screen still holding the old id in this process saves under the Drive id
   (`MediaRepository.currentId`, used by `registerDerived`). Conditions: its phone copy, if any, has the Drive copy's
   MD5 (otherwise it is another recording or a damaged copy and stays on its own), not both rows have a phone copy, and
   nothing works on it right now. **In use** = uploading, a recorder download pending, a plate check running or queued,
   an upscale pending, or (with a phone copy) a Drive download of the Drive row pending; such a row is excluded from the
   automatic backup and merged by the next import ("Aktualisieren"). The merge re-checks under the library lock that the
   recording, phone copy, backup state and parent are unchanged and takes the row's current recorder fields (a
   re-listing in between does not refuse it). A queued (not yet uploading) row is merged: its upload work finds no row
   and skips.
   A sidecar that cannot be fetched (offline, 5xx after retries) ends the import; rows written so far stay, the next run
   finishes it. A second run changes nothing (it reads only the sidecars of entries it could not use before). The end
   time is kept as `BackupStore.lastImport` ("Stand"), dropped by an account switch.
4. **When it runs** (one at a time; a second call gets the running one's result; never while not connected / reconnect
   needed – `DriveError.NotConnected` / `NeedsReconnect`):
   - automatically in `BackupQueue`'s observer while the connected account has no import time (fresh install, account
     switch, first start with this version). **The automatic rules queue nothing before that import succeeded**; a
     failed one is tried again at a later observer pass (at most once a minute), and the observer re-runs as soon as any
     import succeeds (the import time is one of its inputs). "Jetzt prüfen" follows the same gate but imports at once
     (no retry window); until the first import succeeded the Backup screen says "Wartet auf den ersten Abgleich mit
     Drive";
   - "Drive-Status prüfen" (Backup screen) after the existing vanish handling; the snackbar adds "n Sicherungen aus Drive
     in die App übernommen", or why taking them over failed (the vanish count still stands);
   - the refresh icon of the Drive tab, and once per process when the Drive tab is first shown (fresh thumbnail links).
   Errors are German (`driveMessage()`) where the user started it (Drive tab snackbar, Drive check); automatic runs only
   log the exception class.
5. **Drive tab** (Aufnahmen → "Drive", the last of five tabs in a scrollable tab row): every row with `driveFileId`,
   newest first, day groups, the recorder row component with its chips ("Vorfall", "rekonstruiert", "auf dem Handy";
   not "In Drive gesichert", every row here is). Header "Stand: <last import>" (· "wird aktualisiert…" while it runs),
   the count and a refresh icon (spinner while importing). Not connected / reconnect needed: the Drive status card ("Verbinde
   Google Drive, um deine gesicherten Aufnahmen hier zu sehen und aufs Handy zu laden.") with "Mit Google Drive verbinden" /
   "Erneut verbinden", which open the Google Drive screen like the Backup screen's card, above the rows already known.
   Tapping a row opens the clip screen; long press selects, the selection offers "Vom Drive laden".
6. **Thumbnails**: the local thumbnail when there is one, else Drive's `thumbnailLink`, loaded by the app's media Coil
   loader (same disk cache as the recorder thumbnails, `cacheDir/recorder_thumbs`, "Speicher → Cache") under the stable
   key `drive-thumb:<fileId>`, because the link expires. The loader's call factory sends requests for Google's hosts to
   `driveImageClient` (the `@DriveHttp` internet client with `DriveAuthInterceptor`), everything else to the recorder
   client as before. The interceptor adds `Authorization: Bearer` only to HTTPS requests for `googleusercontent.com` /
   `googleapis.com` and their subdomains; a 401 invalidates the token. Links live in memory: after a restart cached
   thumbnails show at once, missing ones after the tab's first import of the process. An account switch drops the links
   and the previous account's cached thumbnails (disk and memory) and photos (memory).
7. **Clip screen** for an item without phone copy but with Drive copy: photos (`ORIGINAL_PHOTO`, `SCREENSHOT`,
   `ENHANCED_FRAME`) are shown from Drive (`files/<id>?alt=media` through the same loader, memory cache only) with "Auf dem
   Handy speichern"; videos show Drive's thumbnail and "Vom Drive laden" (they play once on the phone). Without a Drive
   connection the button is off and a note says why. A recorder copy still offers "Herunterladen". "Drive-Kopie löschen"
   and the three independent copies are unchanged.
8. **"Vom Drive laden"** (`DriveDownloadQueue`): WorkManager unique work `drive-download-<id>` (KEEP, linear backoff
   30 s) under the **backup's network conditions** (`BackupRules.constraints`: by default Wi-Fi with internet; mobile data
   only when allowed under Einstellungen → Sicherung). Changed conditions are re-applied to waiting and running downloads
   (REPLACE; a running one resumes its part). The worker checks the default network before and after waiting for the slot
   (`BackupRules.defaultNetworkFits`) and waits when it does not fit; such a download shows "Wartet auf ein Netz, das die
   Bedingungen unter Einstellungen → Sicherung erfüllt". It survives process death, one transfer at a time (process-wide
   slot), independent of the recorder's download slot; a Drive download and a recorder download of the same item are
   never queued together. `DriveApi.download` writes `media/<id>/<name>.drive.part` (Range resume; a non-continuing range
   starts over; a 416 whose `bytes */<size>` equals the part finishes it), then `media/<id>/<name>.drive`; its MD5 must
   equal `driveMd5`, else it is deleted ("Prüfsumme", German error); then the atomic rename to
   `MediaDownloader.targetFile` (where a recorder download would put it) and `MediaRepository.markDownloaded`. Progress,
   failures ("nicht mehr in Google Drive", "Google Drive hat die Datei nicht geliefert", "nicht verbunden",
   "Prüfsumme") and cancel appear in the row, the clip screen and the "Übertragungen" sheet ("… · aus Drive"), with the
   transfers notification "Aus Drive: <name>". Offline waits are retried without limit; every other failure ends the work
   (retry from the sheet). Speicher counts and frees interrupted `.drive` / `.drive.part` files (never those of a running
   transfer).

## Decisions and deviations from the brief

- Automatic import trigger: "the account has never been imported" (`lastImport` empty) instead of "adoptAccount changed
  or no account recorded yet". It covers both and also the first start after updating to this version (the owner's phone,
  connected since 1.0.4). A reconnect of the same account does not import again by itself (nothing was lost: disconnect
  keeps the rows); "Aktualisieren" does (PM decision).
- The automatic rules wait for the first successful import of the connected account (see 4.).
- Merges move references instead of being refused (review M3). Deviations from the review: a **queued** upload does not
  refuse the merge (its work finds no row and skips, which is what prevents the second copy); a frame being **enhanced**
  ("Bild verbessern" open) does not refuse it either – there is no app-wide signal for it, and its save resolves the
  merged id (`currentId`, in memory, same process).
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
- Aufnahmen uses a scrollable tab row (five tabs; segmented buttons cut the labels at 360–393 dp); the Home tile names
  the same tabs from the tab label strings.

## What "never uploaded twice" really covers

The backup never uploads a recording a second time **under another id** when the Drive copy is known to the library
before the upload starts: the first import of an account runs before any automatic queueing, and every import merges
the rows of recordings already in Drive. Remaining cases, where a recording can end up twice in Drive:
- an upload of the other row was already **running** when the import found the Drive copy (it finishes; the row is
  excluded from further automatic backups);
- a manual **"Sichern"** of such a row before the merge (manual backups do not wait for the import);
- the phone copy and the Drive copy have different content (another recording with the same name and time, or a damaged
  copy): both are kept on purpose;
- recordings already backed up twice under different ids (reinstalls before this feature) stay twice.

## Limitations

- **Plates are not restored**: sidecar `plates` (only present with "Kennzeichen-Daten mitsichern") are ignored; the
  plate history stays on the phone it was recognised on.
- **Backup settings are not restored**: they are local preferences, so after a reinstall the mode is "Nur manuell" until
  the user picks another one.
- **Upgrade gap of the disconnect flag**: `driveDisconnected` exists since this version. A phone where Drive was
  disconnected under 1.0.4 or earlier does not have it; once another phone of the same app account writes its Drive
  e-mail as hint, that phone reconnects silently at its next start (no migration).
- The hint is last-writer-wins between phones with different Drive accounts (see 1.).
- The recorder copy of a Drive-only row is re-attached by the next listing of that type (type + name + raw time); until
  then the row has no recorder copy.
- `ponytail:` thumbnail links are kept in memory only (they expire within hours); a Drive download waiting for the slot
  holds a WorkManager slot (fine for a few); phone copies that differ from their Drive copy are hashed again after a
  restart; the merged-id alias lives in memory (it redirects only while no row has the old id, an account switch
  drops it).
- `downloadedAt` of a Drive download is the time of that download (the sidecar's value is replaced, as for a recorder
  download).
- Guests (no app account) connect Drive manually after a reinstall; the import then runs as above.
- Firestore rules allow up to 20 preference keys (`firebase/firestore.rules`); `driveAccount` is the third, no rule change.
- On narrow phones (≈ 360 dp) the "Drive" tab sits entirely off-screen in the scrollable tab row, with no peek of it; it
  is reached by swiping the row and is named on the Home tile.
- Orphan Drive file: a queued row whose media file was already uploaded and verified, but whose sidecar write failed,
  leaves that media file without sidecar in Drive once it is merged into its Drive row. Only reachable through a manual
  "Sichern" before the merge; readers ignore media files without sidecar (`DRIVE_FORMAT.md` §6).
- Stale source ids after a merge: derived items moved to the Drive id keep the merged row's old id as `sourceMediaId`
  in their `*.enhance.json` and as `parent.id` in any Drive sidecar already written. Provenance text only; the library
  links (`parentId`) are moved.

## Owner checklist (phone, needs the Google Cloud setup of `drive.md`)

1. On the phone with 1.0.4 and Drive connected: install this build over it, start it once signed in → in the Firebase
   console `users/<uid>.preferences.driveAccount` holds the Drive account's e-mail; Aufnahmen → Drive lists the backups.
2. Settings → Apps → My Forthing → Speicher → "Daten löschen" (or install on a second phone), start, sign in with the same
   app account → without any tap Settings → Google Drive shows the account as connected (or "Erneut verbinden": one tap
   there, Google's sheet preselects the account).
3. Aufnahmen → Drive: every backed-up video and photo with thumbnails, "Stand: …", refresh icon works; airplane mode +
   refresh → snackbar "Keine Internetverbindung …".
4. Open a photo → shown from Drive; "Auf dem Handy speichern" → it appears in "Handy" and plays/shows offline.
5. Open a video → thumbnail + "Vom Drive laden" on Wi-Fi → progress in the row, the clip screen and "Übertragungen";
   airplane mode halfway, then back online → continues (no restart from 0); the video plays afterwards. On mobile data
   only (Wi-Fi off) it waits ("Wartet auf ein Netz, …") until mobile data is allowed under Einstellungen → Sicherung.
6. Connect the recorder, list the incidents → the restored clips show their recorder copy again (one row each, no
   duplicates); with mode "Vorfälle" nothing that is already in Drive is uploaded again (Backup screen queue stays empty).
7. Settings → Google Drive → Trennen → `driveAccount` becomes `""` in Firestore; clear data again and sign in → Drive
   stays "Nicht verbunden".
8. A guest (no app account) after clearing data: connect manually → the Drive tab fills.

## Verification status

- Unit tests (JVM / Robolectric): `DriveRestoreTest` 17 (new rows with every field, derived parent, plates not restored,
  thumbnail key; same-id adoption; recorder-only merge and re-listing under the Drive id; phone copy merges only with the
  same MD5; plate sightings and derived items move to the Drive id; rows in use (pending recorder download, running
  upload, queued plate check) are excluded and merged by the next import; a merge takes the current recorder fields but
  refuses a changed phone copy; incomplete / orphaned entries ignored, oldest duplicate wins; unusable sidecars counted;
  idempotent second run; nothing while not connected / reconnect needed; failing sidecar read and finishing run;
  automatic import until it succeeded, at most once a minute, again after a switch, no write for the old account; an
  account switch forgets thumbnail links and cached thumbnails; Drive-account hint incl. disconnect; silent reconnect
  once per value, never after a disconnect here), `BackupQueueTest` +4 ("Jetzt prüfen" imports without the retry window; first connect: import before the rules, the
  downloaded clip takes the Drive id, no upload; no automatic queueing before a successful import; the Drive check keeps
  its count when the import fails), `DriveDownloadsTest` 6 (target location + markDownloaded, MD5 mismatch discarded,
  WorkManager run reported as Drive transfer, 404 → "nicht mehr in Google Drive", backup network conditions incl. a
  network that does not fit and the REPLACE on a changed preference, never together with a recorder download),
  `DriveRestApiTest` +6 (readJson, Range resume after an interrupted body, 416 restart, a complete part finished on 416,
  401 → refresh → retry, 404), `DriveAuthInterceptorTest` 3, `GoogleDriveAuthTest` +5 (granted → connected + stored,
  needs UI → NeedsReconnect without UI and the one-tap reconnect for that account, errors → not connected, a stored
  connection is never replaced, "Trennen" on a pending silent reconnect revokes nothing), `SyncedPreferencesTest` +2,
  `ProfileSyncTest` +2.
- Emulator (`emulator-5554`, AVD `Pixel_10_Pro_XL`, API 36, debug build): the reviewer's smoke run showed the Drive tab's
  not-connected card with German texts and no crash, and found the five segmented buttons cut at narrow widths. After the
  switch to the scrollable tab row: at 360 dp (`wm density 597` on the 1344 px wide AVD) "Schleife", "Vorfälle", "Fotos",
  "Handy" are whole and "Drive" scrolls into view; the Drive tab shows the card with the new text and "Mit Google Drive
  verbinden"; the Home tile reads "Schleife, Vorfälle, Fotos, Drive".
- **Hardware-unverified**: everything against real Google Drive and a real Google account (silent authorisation after a
  reinstall, `thumbnailLink` with the Bearer header, `alt=media` Range answers, photo display, download speed), real
  Firebase sync of `driveAccount`.
