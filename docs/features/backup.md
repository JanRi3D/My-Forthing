# Backup to Google Drive (feature/drive-backup)

Package `me.ri3d.cam.backup`. Contract: `docs/CONTRACTS.md` §10. Drive client and format: `docs/features/drive.md`,
`docs/DRIVE_FORMAT.md` (unchanged by this feature).

| File | Contents |
| --- | --- |
| `BackupRules.kt` | Which items the automatic modes select; WorkManager constraints from the network conditions |
| `DriveBackup.kt` | `DriveBackup` (verified upload of one item, delete Drive copy, reconcile, account-switch reset), `BackupStore` (private bookkeeping), `BackupOutcome`, `BackupErrors` |
| `BackupQueue.kt` | `BackupQueue` (scheduling, automatic-rules observer, user actions), `BackupWorker` (Hilt, foreground), notifications, `BackupInitializer` (androidx.startup) |
| `BackupScreen.kt` | Route `Backup` (Settings → Sicherung), `BackupViewModel`, `BackupSelectionAction` ("Sichern"), `driveDeleteTargets` ("Drive-Kopie löschen"), `BackupStateTag` (chip) |

## Rules (`AppPreferences`)

- **Only phone copies are uploaded** (`localUri` set): downloads, screenshots, enhanced/upscaled outputs. Nothing is
  streamed from the recorder; "Sichern" on a recorder-only item answers "nicht auf dem Handy – erst herunterladen".
- `backupMode`
  - `MANUAL`: only what the user selects in Aufnahmen → "Sichern".
  - `INCIDENTS`: items of category `EVENT` once they are on the phone.
  - `ALL`: every original on the phone (`ORIGINAL_VIDEO`, `ORIGINAL_PHOTO`, `SCREENSHOT`).
  - Derived items (`ENHANCED_FRAME`, `UPSCALED_CLIP`, category inherited from the original) are selected only once
    their original is `DONE` (and they match the mode); manual "Sichern" takes them any time.
- Automatic modes act when an item becomes local (the library is observed for the whole process lifetime, started by
  `BackupInitializer` – also in a process started only for a background download), on rule changes, and on "Jetzt
  prüfen" (also re-queues automatic items that had FAILED and lifts a storage pause).
- **Exclusions**: an item the user cancelled, whose Drive copy was deleted in the app, or that "Drive-Status prüfen"
  found missing in Drive is not selected automatically again; "Sichern" in the selection includes it again.
- Network conditions → WorkManager constraints (`BackupRules.constraints`):
  - JobScheduler evaluates network constraints against the app's default network and requires validated internet.
    The process never binds to the recorder Wi-Fi; that network has no internet, so it is never validated and never
    satisfies the constraint, even if Android made it the default (e.g. with mobile data off);
  - `backupRequireInternetWifi` (default on): `NetworkRequest` Wi-Fi + `INTERNET` + `VALIDATED`, with the builder's
    default `NOT_VPN` removed so a VPN over Wi-Fi qualifies (API 28+; below: unmetered). While it is on, "Mobile Daten
    verwenden" is disabled in the UI (it could never apply);
  - otherwise unmetered, or any connected network with `backupOnMobileData`.
  - The worker checks the default network again before it uploads (`BackupRules.networkFits`: validated internet,
    Wi-Fi when required, unmetered unless mobile data is allowed; a VPN reports its underlying transport) and waits
    (`Result.retry`, uncounted) when it no longer fits.
  - A change of the conditions re-enqueues the waiting/running work with the new constraints (`REPLACE`); a running
    upload resumes from its session URI.
- `backupIncludePlateMetadata`: sidecar `plates` = `PlateExport.forMedia(id)` mapped 1:1 when on (`[]` when nothing was
  recognised), `null` when off. Applies to uploads from then on; existing sidecars are not rewritten.

## Queue behaviour

- The queue is the `backupState` column: `QUEUED` → `UPLOADING` → `DONE` / `FAILED`. WorkManager holds **exactly one**
  unique work at a time (`backup-<mediaId>`, tag `backup`) – the item uploading now. When it ends, the worker enqueues
  the next pending item (an interrupted `UPLOADING` one first, then newest first in library order). `DriveBackup.upload`
  additionally holds a process-wide lock, so two uploads never overlap (DriveApi has no folder lock).
- Nothing is scheduled while Drive is `NotConnected`, `NeedsReconnect` or the storage pause is set; the observer
  schedules again as soon as the state changes.
- Upload of one item (`DriveBackup.upload`):
  1. Drive must be `Connected`; the account is adopted first (see account switch).
  2. Phone copy must exist (else `FAILED` `NO_LOCAL_COPY`, Drive is not contacted).
  3. MD5 of the phone copy; `ensureRootFolder` → `ensureMonthFolder` (month of the recorder time guess, else download
     or creation time).
  4. **Duplicate prevention**: `files.list` of all files with this `mf.id`. Any listed media file means its upload
     session is used up, so the stored session is dropped. A media file with the same MD5 is adopted (no upload; the
     sidecar goes next to it).
     - Another MD5 **with** a sidecar and no matching file: Drive holds a verified other version → `FAILED`
       `DRIVE_CONFLICT`, nothing is touched.
     - Another MD5 **without** a sidecar is an unverified upload. Readers take the oldest media file per id, so these
       are removed before any sidecar is written (also when adopting): at once if it is the upload this app recorded
       as unverified (`unverified:<id>`, set right after `uploadResumable` returns), otherwise only when its
       `createdTime` is more than 24 h old; a recent unknown one makes the item wait (uncounted retry).
  5. `uploadResumable` with the persisted session URI (`onSessionUri` stores a new one together with the account hash;
     a session of another account is ignored, also one written late by a callback after a switch; restart after
     process death continues where Drive stopped).
  6. **Verification**: Drive `md5Checksum` must equal the phone copy's MD5. Mismatch → the uploaded file is deleted
     (the phone copy stays) and the item is retried.
  7. Only then the sidecar (`DriveSidecar`, `writeJson` – replaces an existing one of that name in the folder).
  8. The stored session is dropped (uploaded or adopted, a completed session is never reused); only then `DONE` with
     `driveFileId` and `driveMd5`, and only if the Drive account is still the same (otherwise the reset for the new
     account runs at once).
- Errors:
  - `DriveError.Offline` → wait (`Result.retry`, not counted, linear backoff from 30 s), item `QUEUED`.
  - `InsufficientStorage` → **pause** everything (persisted), item stays `QUEUED`, notification "Google-Drive-Speicher
    voll", Backup screen shows "Belegt: x von y" from `about()` and "Fortsetzen".
  - `NeedsReconnect` (the auth state turned `NeedsReconnect`) / `NotConnected` → **pause**, item stays `QUEUED`;
    notification "Google Drive erneut verbinden"; the Backup screen and the "Sichern" snackbar lead to the existing
    `DriveAccount` screen. Reconnecting resumes automatically. A worker that met "not connected" right after process
    start (the account record is still being loaded) retries when Drive reads connected by the time it returns.
  - Everything else (HTTP errors, MD5 mismatch, file read errors) counts: up to `MAX_ATTEMPTS` = 5 real failures per
    item, then `FAILED` with `backupError` (`MD5_MISMATCH`, `HTTP:<code>:<reason>`, `AUTH:<status>`, …) shown in German
    (`backupErrorText`). Only a rejected request (4xx other than 408/429) drops the session URI; 5xx, 429 and network
    failures keep it, so the next attempt resumes.
- **Account switch** (CONTRACTS §10): `BackupStore.account` remembers (as a SHA-256 of the e-mail) the Drive account
  the states belong to. When `Connected.accountEmail` differs (seen by the observer or by a worker that starts first),
  every row with Drive fields, a backup state or a backup error is reset through `MediaRepository.markDriveDeleted`
  (state `NONE`, `driveFileId`/`driveMd5` cleared; rows with no other copy disappear, local files are never touched),
  everything stored per item (session URIs, unverified uploads, completion times, failure counts, exclusions) and the
  storage pause are dropped, and running backup work is cancelled. Disconnect + reconnect of the same account keeps
  everything.
- UI: Backup screen queue list with per-item state ("In der Warteschlange", "Wartet auf ein WLAN mit Internet" etc. when
  WorkManager waits for constraints or a retry, bytes + progress bar while running, German failure reason), "Abbrechen"
  / "Erneut versuchen" / "Entfernen" (TalkBack reads them with the file name); progress also as a silent foreground
  notification (data sync) "Sicherung: <name>" with "x von y", removed when the worker ends (also when it ran without
  foreground). Without the notification permission (Android 13+) uploads run silently (the permission is requested by
  the first download).

## What "gesichert" means

Only `DONE` counts as backed up and only `DONE` shows the chip **"In Drive gesichert"**: the media file is completely
uploaded to the connected account, Drive's `md5Checksum` equals the phone copy, and the sidecar was written next to it
afterwards (so web readers see it as complete, `DRIVE_FORMAT.md` §6). `QUEUED`/`UPLOADING` show "Wird gesichert",
`FAILED` "Sicherung fehlgeschlagen" (error colours). Chips: Recordings rows (recorder tabs and "Handy") and the clip
header.

## Delete Drive copy and Drive check

- **"Drive-Kopie löschen"** (`clipDeleteTargets`, danger confirmation): lists every file with this `mf.id` in the
  current account and deletes the media files first. Only when they are gone is the item excluded from the automatic
  rules, its session dropped and `markDriveDeleted` called (a failed media delete changes nothing). Then the sidecar is
  deleted; if that fails the row keeps `backupError = SIDECAR_LEFT` (state `NONE`, no chip) and the target stays
  offered, so the user can remove the sidecar – which may hold plate data – with another tap. Phone and recorder
  copies stay; the dialog says so. Enabled while connected for `DONE` items, `DRIVE_CONFLICT` failures and
  `SIDECAR_LEFT`; refused while an upload of the item is pending.
- **"Drive-Status prüfen"**: `DriveFormatReader.scan`; every `DONE` item (snapshot taken before listing) without a
  complete entry (media + sidecar) becomes `NONE` via `markDriveDeleted`, loses its stored session and is excluded from
  the automatic rules. Items that became `DONE` less than 5 minutes ago are skipped (Drive's listing can lag behind).
  Nothing is deleted anywhere.
- `DRIVE_CONFLICT` text says that the verified version in Drive stays untouched; it does not ask the user to delete it.

## Interfaces (for integration)

```kotlin
// AxoNavHost
mediaGraph(navController,
  selectionActions = { items, clear -> BackupSelectionAction(items, clear, onConnectDrive = { navController.navigate(DriveAccount) }) },
  clipDeleteTargets = { item -> driveDeleteTargets(item) })
backupGraph(navController)                      // route Backup
@Composable fun BackupStateTag(item: MediaItem)  // chip, used in media/ (RecordingsScreen rows, ClipScreen header)

object BackupRules { fun automatic(item, mode, parent: MediaItem?): Boolean; fun constraints(prefs): Constraints; fun hasPhoneCopy(item)
                     fun networkFits(caps: NetworkCapabilities?, prefs): Boolean }
class DriveBackup { suspend fun upload(id, onProgress): BackupOutcome; suspend fun deleteOnDrive(id): Result<Unit>
                    suspend fun reconcile(): Result<Int>; suspend fun adoptAccount(email): Boolean }
class BackupQueue { val progress: StateFlow<Map<String, BackupProgress>>; val storageFull: StateFlow<Boolean>
                    fun start(); suspend fun observe(); suspend fun enqueue(ids): EnqueueResult; suspend fun retry(id)
                    suspend fun resume(); suspend fun checkNow(); suspend fun cancel(id)
                    suspend fun deleteOnDrive(id): Result<Unit>; suspend fun reconcile(): Result<Int>
                    suspend fun schedule(replace = false, excluding: UUID? = null) }
```

Shared files touched: `core/navigation/AxoNavHost.kt` (slot arguments + `backupGraph`), `core/FeatureFlags.kt`
(`backup = true`, enables the existing Settings row), `res/values/strings.xml` (`<!-- backup -->` block),
`AndroidManifest.xml` (one `meta-data` for `BackupInitializer` under the existing `InitializationProvider`),
`media/RecordingsScreen.kt` + `media/ClipScreen.kt` (one `BackupStateTag(item)` call each place, allowed exception).

## Limitations

- `ponytail:` the queue order is library order (newest recordings first), not strictly first in, first out (no queue
  time column); the observer scans the whole library on every library change (fine for thousands of rows).
- Started from the background on Android 12+, `setForeground` is refused: the upload runs as normal work, JobScheduler
  may stop it after ~10 minutes, and it resumes from its session URI on the next run.
- `recorderPath` in the sidecar is null when the recorder copy was already gone at backup time (the row forgets it).
- Sidecars are written once per backup; turning plate metadata on later does not update existing sidecars.
- After a storage pause the queue resumes only by "Fortsetzen", "Sichern" or "Jetzt prüfen" (no automatic quota poll).
- Cancel lives in the Backup screen (the progress notification has no cancel action, so the database stays the single
  source of the queue).
- Snackbars after "Sichern" / "Drive-Kopie löschen" run in the screen's lifecycle scope; a deletion that removes the
  last copy closes the clip screen and its snackbar may not show (the deletion itself always completes).
- Exclusions, session URIs (with the account hash), unverified upload ids and completion times live in the app-private
  SharedPreferences `backup_queue` (Android backup is off app-wide), not encrypted.
- A Drive-only item whose sidecar delete fails loses its row with the media file, so `SIDECAR_LEFT` cannot be kept:
  that sidecar stays in Drive (readers ignore it; it can be removed in Drive).

## Needs the real Drive setup (owner)

A real upload is impossible until the owner creates the Google Cloud OAuth client(s) for `me.ri3d.cam`
(`docs/features/drive.md` → Google Cloud setup); without them "Mit Google Drive verbinden" ends with status code 10.
Then check on a phone:
1. Connect, mode "Vorfälle", download an incident clip on Wi-Fi with internet → notification "Sicherung: …", chip "In
   Drive gesichert"; in Drive `My Forthing/media/<yyyy-MM>/<id>.mp4` + `<id>.json`, sidecar md5 = Drive md5.
2. Airplane mode mid-upload, force-stop, restart online → upload continues (no second file in Drive).
3. Phone only on the recorder Wi-Fi + mobile data: with "Nur WLAN mit Internet" nothing uploads; with it off and
   "Mobile Daten verwenden" on, it uploads over mobile data.
4. Revoke the app at myaccount.google.com/permissions → "Google Drive erneut verbinden" notification, queue paused,
   reconnect resumes.
5. Full Drive (test account) → pause with quota and "Fortsetzen".
6. "Konto wechseln" to another account → every chip disappears, nothing uploads into the old account.
7. "Drive-Kopie löschen" → both files gone in Drive, phone copy plays; delete a file in the Drive web UI → "Drive-Status
   prüfen" reports it missing.
8. Check the web side against `DRIVE_FORMAT.md` (completeness, adoption after an interrupted run).

## Verification status

- Unit tests (Robolectric, fake `DriveApi` in memory, `FakeDriveAuth`): `DriveBackupTest` 22 (verified upload +
  sidecar content, resume from the persisted session URI after a restart, 5xx keeps / 4xx drops the session, adopting
  a completed upload clears its session and a later backup starts a fresh one, a session of another account is never
  used, MD5 mismatch → unverified file deleted, no sidecar, retries until `FAILED`, full Drive → paused, revoked →
  paused until reconnect, adoption of a matching Drive file, our recorded unverified upload replaced at once, an
  unknown recent one waits and a day-old one is replaced, verified other version = conflict, adoption also removes
  unverified copies, failing sidecar write → queued, next attempt adopts, account switch during the transfer never
  DONE, account switch reset with sessions and exclusions dropped, plates only with the opt-in, delete Drive copy keeps
  phone + recorder copies and clears the session, delete offline / not connected changes nothing, half-done delete →
  `SIDECAR_LEFT` and a second delete, reconciliation incl. listing lag, missing phone copy, only queued items upload),
  `BackupQueueTest` 9 (WorkManager test driver, SDK 33: one work at a time until all `DONE`, constraints on the work, a
  job on a network that no longer fits waits, offline retry keeps the single slot, automatic rules on becoming local
  per mode, cancel excludes until manual "Sichern", changed conditions re-applied, nothing scheduled while not
  connected / reconnect / full, observer-side account switch, `BackupViewModel`), `BackupRulesTest` 5 (mode × kind ×
  category × local matrix, derived items × parent state, constraints at SDK 33 incl. `NOT_VPN` removed, the pre-28
  fallback at SDK 27, `networkFits` for Wi-Fi / cellular / recorder Wi-Fi × conditions).
- Emulator (`emulator-5554`, API 36, debug, Drive **not** connected, simulator [SIM] for two incident downloads):
  Settings row "Sicherung"; Backup screen (status card, connect button, mode radios, conditions with the disabled
  mobile-data row, plate switch, empty queue); "Sichern" in the Handy selection → "Google Drive nicht verbunden" →
  "Verbinden" opens the Google Drive screen; clip delete chooser lists "Drive-Kopie löschen" disabled. With states set
  directly in the debug database (DONE / FAILED `HTTP:403:accessNotConfigured` / QUEUED, no Drive involved): chips in
  the Handy rows and the clip header, queue list with the German reason, "Erneut versuchen", "Abbrechen", pause note
  "Google Drive ist nicht verbunden". A fake-connected Drive state could not be injected without changing `drive/`
  (the account record is Keystore-encrypted), so connected states are covered by the unit tests only.
