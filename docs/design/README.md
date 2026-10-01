# Design reference – mapping and deviations

Source: artifact https://claude.ai/artifact/4oYoRuE8yn5wUVtj1Ypdbq, page "Material 3" (`M3*.dc.html`). The "Black design" page is the older variant; only its black theme tokens (`.t-black`) matter. `Web*.dc.html` describe the **future web app** and only inform the Drive format. `AppIconAxolotl.dc.html` is the icon direction.

Theme tokens per artboard: `.t-blue/.t-purple/.t-green` = Material You previews (dynamic colour in the app), `.t-black` = "Schwarz" theme (surface #050506, on-surface #f4f5f7, primary #f4f5f7, error #ff6961, rec #ff453a, ok #30d158, Geist-like sans; Android uses system sans + optional bundled font).

| Artboard | App screen (German) | Notes |
| --- | --- | --- |
| M3Welcome | Willkommen | Create account / Sign in / "Ohne Konto fortfahren" |
| M3SignIn | Anmelden | **Deviation:** no Apple, no "e-mail code" link (out of scope). Google + e-mail/password + "Passwort vergessen" |
| M3CreateAccount | Konto erstellen | Step 1 of 2; Google or e-mail/password; terms checkbox |
| M3VerifyEmail | E-Mail bestätigen | **Deviation:** Firebase sends a verification *link*, not a 6-digit code → "Link gesendet an …", "Mail-App öffnen", "Ich habe bestätigt", "Erneut senden" |
| M3ForgotPassword / M3ResetSent | Passwort zurücksetzen / Link gesendet | As drawn |
| M3Offline | Offline-Profil | Guest profile: name, local picture, explanations |
| M3Upgrade | Online-Konto hinzufügen | Guest → account migration; **Deviation:** Google only; existing-account conflict handling added |
| M3Home | Start | Plate search bar, dashcam card (real connection state, SSID, SD free), 4 tiles |
| M3Live | Live-Ansicht | Real RTSP; Plates toggle; screenshot; Upscale toggle only if measured feasible, else replaced by note |
| M3Recordings | Aufnahmen | Tabs normal/photo/incident from recorder listing types 0/2/1; real paging; selection + download/backup |
| M3SDCard / M3SDFiles | SD-Karte | Values from command 4099/4097 only where returned; raw file lists |
| M3Settings | Einstellungen | Recorder settings only those confirmed (4097 readback), changes shown after rval=0; **Deviation:** "Restart dashcam" removed; "Firmware" from 4098; app settings separate section |
| M3Appearance | Darstellung | Material You / Schwarz |
| M3Plates / M3PlateDetail | Kennzeichen / Verlauf | Real history; "Vorfall" badge from event-type recordings |
| M3Clip | Clip | Player for downloaded files; "Bild verbessern", "Clip hochskalieren", plates in clip |
| M3Enhance | Bild verbessern | **Deviation:** "98% match" only if a measured basis exists; enhanced image labelled "verbessert – kein Beweis" |
| M3Upscale | Clip hochskalieren | Estimates only after measuring; progress, cancel, output saved separately |
| EmailCode / EnterCode | – | Not implemented (out of scope) |

Mock data in the artboards (plates, times, storage figures, SSID "FORTHING-XXXX") must be replaced with live state. Example plates are fictional.
