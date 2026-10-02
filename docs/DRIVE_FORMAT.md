# My Forthing – Google Drive format v1

Normative description of what the Android app writes to the user's Google Drive, so a web app can discover, list and play the backups **through Drive alone** (no server, no Firebase). Source of truth in code: `app/src/main/java/me/ri3d/dashcam/drive/format/` (`DriveFormat`, `DriveSidecar`, `DriveFormatReader`) and `DriveRestApi`. Contract: `docs/CONTRACTS.md` §10.

## 1. Access: same Google Cloud project, scope `drive.file`

- The app requests only `https://www.googleapis.com/auth/drive.file`. With this scope Drive grants access **per file to the Google Cloud project** whose OAuth client created the file.
- `appProperties` are private to that same project. A client from another project sees neither the files nor their `appProperties`.
- Therefore the web app **must use an OAuth client (type "Web application") in the same Cloud project** as the Android OAuth clients, request `drive.file`, and the user must sign in with the Google account that the phone connected under Settings → Google Drive (this may differ from the app account).
- `drive.file` cannot list the user's other files. Everything below works with it.

## 2. Folder layout

```
<My Drive>/My Forthing/                      root folder      appProperties: mf.format=1, mf.role=root
  myforthing.json                            manifest         appProperties: mf.format=1, mf.role=manifest
  media/                                     folder           appProperties: mf.format=1, mf.role=folder
    2026-10/                                 month folder     appProperties: mf.format=1, mf.role=folder
      <mediaId>.<ext>                        media file       appProperties: see §3
      <mediaId>.json                         sidecar          appProperties: see §3
```

- The root folder name comes from `Branding.driveRootFolderName` ("My Forthing"). The user may rename or move it; the app identifies it by `mf.role=root` (preferring the one with the current name, else the oldest). A trashed root is ignored and a new one is created.
- Month folder `yyyy-MM`: the month of the recorder time (`recorderTimeEpochGuess`, the recorder wall clock parsed in the phone's zone), else of the download time (derived files: creation time). Informational only – **readers must not rely on folder placement**; use the queries in §5.
- `<mediaId>` is the app's stable UUID v4 (`MediaItem.id`). `<ext>` is the lower-cased extension of the recorder file name (`MP4` → `mp4`), else derived from the MIME type (`video/mp4` → `mp4`, `image/jpeg` → `jpg`, `image/png` → `png`, otherwise `bin`).
- Manifest `myforthing.json`: `{"format":1,"app":"me.ri3d.dashcam","createdAt":"2026-10-01T12:00:00.317+02:00"}` (`createdAt` = when the root was set up).
- **Timestamps** (`createdAt`, `downloadedAt`, `backup.completedAt`) are ISO-8601 with offset as `DriveFormat.isoTimestamp` writes them: milliseconds appear when non-zero, with trailing zeros dropped (`2026-10-01T12:03:00.123+02:00`, `…:03.9+02:00`, else `2026-10-01T12:03:00+02:00`), and UTC is written as `Z`. Parse them with a full ISO-8601 parser (`new Date(…)` / `Temporal.Instant.from` both work). `recorderTime` is different: see §4.

## 3. appProperties

| Key | On | Value |
| --- | --- | --- |
| `mf.format` | everything | `"1"` – format major version |
| `mf.role` | everything | `root`, `manifest`, `folder`, `media`, `sidecar` |
| `mf.id` | media, sidecar | media UUID; pairs a media file with its sidecar |
| `mf.kind` | media | `ORIGINAL_VIDEO`, `ORIGINAL_PHOTO`, `SCREENSHOT`, `ENHANCED_FRAME`, `UPSCALED_CLIP` |
| `mf.category` | media | `NORMAL`, `EVENT`, `USER`, `UNKNOWN` |
| `mf.parent` | media, optional | UUID of the source item of an `ENHANCED_FRAME` / `UPSCALED_CLIP` |

All values are strings (Drive limit: key + value ≤ 124 bytes UTF-8).

## 4. Sidecar `<mediaId>.json` (schema v1)

Written **only after** the media upload has been verified (Drive `md5Checksum` equals the MD5 of the local file). Every field is always present; `null` is written explicitly. Readers must ignore unknown fields.

| Field | Type | Null | Meaning |
| --- | --- | --- | --- |
| `format` | int | no | `1` (equals `mf.format`) |
| `id` | string | no | media UUID (equals `mf.id` and the file name) |
| `kind` | string | no | as `mf.kind`; unknown future values → treat as generic file |
| `category` | string | no | as `mf.category`: `NORMAL` loop recording, `EVENT` incident (locked clip), `USER` user data such as photos, `UNKNOWN` |
| `recorderType` | int | yes | raw recorder listing type (0 normal video, 1 event video, 2 user data); null for files made by the app |
| `originalFileName` | string | no | file name on the SD card (or the app-generated name) |
| `recorderPath` | string | yes | full path on the recorder, e.g. `/mnt/sd/EVENT/…MP4`; null for app-made files |
| `recorderTime` | string | yes | recorder wall-clock time `yyyy-MM-dd HH:mm:ss`, **time zone unknown** – display as-is, do not convert |
| `recorderTimeZone` | string | yes | always null in v1 (the recorder reports no zone) |
| `downloadedAt` | string | yes | ISO-8601 with offset (milliseconds optional), when the phone downloaded it from the recorder; null for app-made files |
| `sizeBytes` | int | no | bytes of the media file |
| `md5` | string | no | lower-case hex MD5 of the media file (= Drive `md5Checksum`) |
| `mime` | string | no | e.g. `video/mp4`, `image/jpeg` |
| `durationMs` | int | yes | video length; null for images or when unknown (Drive's `videoMediaMetadata.durationMillis` is an alternative) |
| `parent` | object | yes | `{ "id": uuid, "positionMs": int \| null }` – source item and position for `ENHANCED_FRAME` / `UPSCALED_CLIP` |
| `plates` | array | yes | null unless the user enabled "plate metadata in backups"; items below |
| `plates[].text` | string | no | plate as displayed; unreadable characters are `?` |
| `plates[].normalized` | string | no | upper-case, no separators, e.g. `BMK4821` |
| `plates[].positionMs` | int | yes | position in the clip; null for photos |
| `plates[].confidence` | number | yes | 0–1, only when the recogniser returned one |
| `plates[].box` | int[4] | yes | `[left, top, right, bottom]` in pixels of the decoded frame |
| `backup.complete` | bool | no | `true` when written by the app (the sidecar only exists after verification) |
| `backup.completedAt` | string | yes | ISO-8601 with offset (milliseconds optional) |

`plates[].confidence` is the recogniser's internal OCR score (not calibrated, not a probability); readers must never render it as a percentage or match rate.

Example (incident clip with plates):

```json
{"format":1,"id":"7f9c1d2e-5b1a-4c3e-9a63-2f0e8c1b4d55","kind":"ORIGINAL_VIDEO","category":"EVENT","recorderType":1,
 "originalFileName":"20261001173614_0012.MP4","recorderPath":"/mnt/sd/EVENT/20261001173614_0012.MP4",
 "recorderTime":"2026-10-01 17:36:14","recorderTimeZone":null,"downloadedAt":"2026-10-01T18:02:11.482+02:00",
 "sizeBytes":45088768,"md5":"9e107d9d372bb6826bd81d3542a419d6","mime":"video/mp4","durationMs":30000,"parent":null,
 "plates":[{"text":"B-MK 4821","normalized":"BMK4821","positionMs":12000,"confidence":0.93,"box":[812,604,1044,668]}],
 "backup":{"complete":true,"completedAt":"2026-10-01T18:05:40.067+02:00"}}
```

Example (enhanced frame taken from that clip at 0:12, plate metadata off):

```json
{"format":1,"id":"0b6f3a8e-7d2c-4f19-8e55-6a1c9d3e2f70","kind":"ENHANCED_FRAME","category":"EVENT","recorderType":null,
 "originalFileName":"enhanced_20261001173614_0012_12000.jpg","recorderPath":null,"recorderTime":null,"recorderTimeZone":null,
 "downloadedAt":null,"sizeBytes":812345,"md5":"e4d909c290d0fb1ca068ffaddf22cbd0","mime":"image/jpeg","durationMs":null,
 "parent":{"id":"7f9c1d2e-5b1a-4c3e-9a63-2f0e8c1b4d55","positionMs":12000},"plates":null,
 "backup":{"complete":true,"completedAt":"2026-10-01T18:20:03.9+02:00"}}
```

The media file's appProperties for the second example: `mf.format=1, mf.role=media, mf.id=0b6f…2f70, mf.kind=ENHANCED_FRAME, mf.category=EVENT, mf.parent=7f9c…4d55`.

## 5. Discovery (Drive REST v3 `files.list`)

Always add `trashed = false`, `spaces=drive`, `pageSize=1000`, and follow `nextPageToken` until it is absent. Restrict `fields`, e.g.
`nextPageToken,files(id,name,mimeType,size,md5Checksum,appProperties,parents,createdTime,modifiedTime,hasThumbnail,thumbnailLink,videoMediaMetadata,imageMediaMetadata)`.

| Purpose | `q` |
| --- | --- |
| Everything of format v1 | `appProperties has { key='mf.format' and value='1' } and trashed = false` |
| Media + sidecars (what the app's `DriveFormatReader` uses) | the above `and mimeType != 'application/vnd.google-apps.folder'` |
| Media only | `appProperties has { key='mf.role' and value='media' } and appProperties has { key='mf.format' and value='1' } and trashed = false` |
| One item | `appProperties has { key='mf.id' and value='<uuid>' } and trashed = false` |
| Root folder | `mimeType = 'application/vnd.google-apps.folder' and appProperties has { key='mf.role' and value='root' } and trashed = false` |

Sidecar content: `GET https://www.googleapis.com/drive/v3/files/<sidecarId>?alt=media`. Media bytes: the same with the media id; send the token in the `Authorization: Bearer` header (Range requests work for seeking). Thumbnails and video resolution come from Drive itself (`thumbnailLink`, `videoMediaMetadata.width/height/durationMillis`), not from the sidecar.

**Playing videos in a browser.** A plain `<video src="…?alt=media">` cannot send the `Authorization` header, and access tokens must not be put into URLs (they end up in history, logs and referrers). Options, in order of preference:
- A **Service Worker** that intercepts requests to a same-origin path (e.g. `/media/<fileId>`), forwards them with `fetch` to `https://www.googleapis.com/drive/v3/files/<fileId>?alt=media` adding `Authorization: Bearer <token>` and passing the browser's `Range` header through, and returns the 206 response; the `<video>` element then streams and seeks normally.
- **Media Source Extensions**: fetch byte ranges yourself and append them to a `SourceBuffer` (needs fragmented MP4 or a demuxer; more work).
- A **Blob** for small files (photos, short clips): `fetch` with the header, then `URL.createObjectURL(await response.blob())`; downloads the whole file first.
CORS on `www.googleapis.com` allows these authorised `fetch` calls from the web app's origin. `thumbnailLink` also needs the token (fetch it with the header and show it as a Blob URL); it is short-lived, so re-list instead of caching it.

Mapping for the web artboards (`docs/design/Web*.dc.html`): tabs Loop / Photos / Incidents = `category` `NORMAL` / `USER` (`kind=ORIGINAL_PHOTO`) / `EVENT` ("LOCKED"); day grouping and clip times from `recorderTime` (fallback `downloadedAt`); length from `durationMs`; size from `sizeBytes`; "File" from `recorderPath`; resolution from `videoMediaMetadata`.

## 6. Completeness rules

Group the media + sidecar listing by `mf.id`:

| Found | State | Web app |
| --- | --- | --- |
| media + sidecar | **complete** | show; optionally check `sidecar.md5 == media.md5Checksum` and `backup.complete == true` |
| media only | **incomplete** – uploaded, not yet verified (or the app was interrupted before writing the sidecar) | hide or mark "Sicherung unvollständig"; the app finishes or repeats it |
| sidecar only | **orphan** – the media file was deleted in Drive | ignore |
| several media files for one id | duplicate upload | use the oldest `createdTime`; ignore the rest |

Resumable uploads only appear in Drive once the last byte is received, so a listed media file is never truncated. Sidecars may be rewritten in place later (same file id, newer `modifiedTime`), e.g. when plate metadata is added.

## 7. Versioning policy

- `mf.format` / `format` is the **major** version. v1 readers query `value='1'` and therefore never see files of another major version.
- **No bump** (additive, v1 readers stay correct): new optional sidecar fields, new appProperties keys, new `mf.role` values for files readers can skip, new `kind` / `category` values (readers must tolerate unknown values), new manifest fields. Readers must ignore unknown JSON fields and keys.
- **Bump to 2** for anything a v1 reader would misread: removing or renaming a field or key, changing a type or meaning (e.g. time-zone semantics of `recorderTime`, units of `box`), changing file naming or the media/sidecar pairing, writing sidecars before verification.
- A v2 writer sets `mf.format=2` on new files, keeps reading v1, and may migrate v1 files by rewriting them with `mf.format=2`. The manifest's `format` is the highest version written into that root.
