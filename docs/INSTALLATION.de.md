# My Forthing installieren

Für den Besitzer und Tester. My Forthing gibt es nicht im Play Store, sondern als APK-Datei. Diese Anleitung gilt für
Android 8 bis Android 16. Die Bedienung der App steht in [`BENUTZUNG.de.md`](BENUTZUNG.de.md).

## 1. Die richtige Datei

Du bekommst zwei APK-Dateien und eine Prüfsummendatei, z. B.:

| Datei | Für |
| --- | --- |
| `MyForthing-1.0.1-2-arm64-v8a.apk` | fast alle Handys ab etwa 2017 – **nimm diese** |
| `MyForthing-1.0.1-2-armeabi-v7a.apk` | nur ältere 32-Bit-Handys, falls die erste Datei sich nicht installieren lässt |
| `SHA256SUMS.txt` | Prüfsummen, um beschädigte oder fremde Dateien zu erkennen (optional) |

Die Zahlen im Namen sind Version (`1.0.1`) und Build-Nummer (`2`). Meldet Android bei der arm64-Datei „App nicht
installiert“, obwohl alles andere stimmt, ist das Handy ein 32-Bit-Gerät: dann die armeabi-v7a-Datei nehmen.

**Nicht installieren:** `MyForthing-1.0.1-2-debug-universal.apk` (steht auch in `SHA256SUMS.txt`). Das ist eine
Entwickler-Version für den Emulator; über sie lässt sich die normale Version später nicht installieren, ohne die App
und alle ihre Daten zu löschen (Abschnitt 5).

**Prüfsumme (optional)** am Windows-PC: in PowerShell `Get-FileHash -Algorithm SHA256 MyForthing-1.0.1-2-arm64-v8a.apk`
und den angezeigten Wert mit der Zeile in `SHA256SUMS.txt` vergleichen (Groß-/Kleinschreibung egal).

## 2. Datei aufs Handy bringen

- per USB-Kabel in den Ordner „Download“ des Handys kopieren, oder
- über einen Link (z. B. aus deinem eigenen Google Drive) im Browser herunterladen.

Messenger und E-Mail blockieren oder verändern APK-Dateien oft; besser nicht darüber verschicken.

## 3. Installieren

1. Öffne die APK-Datei auf dem Handy, z. B. in der App **Dateien** (oder „Eigene Dateien“ bei Samsung) unter
   „Downloads“, oder tippe im Browser auf den fertigen Download.
2. Beim ersten Mal sagt Android sinngemäß: *„Aus Sicherheitsgründen darfst du auf deinem Smartphone keine
   unbekannten Apps aus dieser Quelle installieren.“* Tippe auf **Einstellungen** und schalte **„Aus dieser Quelle
   zulassen“** (je nach Hersteller „Dieser Quelle vertrauen“ / „Berechtigung zulassen“) ein. Dann zurück.
   - Diese Erlaubnis gilt seit Android 8 **pro App**: für die App, mit der du die Datei öffnest (Dateien, Chrome,
     Drive …). Du findest sie später unter *Einstellungen → Apps → Spezieller App-Zugriff → Unbekannte Apps
     installieren* (Samsung: *Einstellungen → Apps → ⋮ → Spezieller Zugriff → Unbekannte Apps installieren*).
   - Nach der Installation kannst du sie wieder ausschalten.
3. Tippe auf **Installieren**.
4. **Google Play Protect** warnt eventuell, weil die App nicht aus dem Play Store kommt („Unbekannte App“, „App
   scannen?“ oder „Von Play Protect blockiert“). Je nach Android-Version: **App scannen** abwarten oder
   **Weitere Details → Trotzdem installieren**. Die App ist mit dem Schlüssel „CN=My Forthing, O=Jan Ried“ signiert.
5. Ist unter Android 16 der **Erweiterte Schutz** (Advanced Protection) eingeschaltet, blockiert Android die
   Installation von APK-Dateien grundsätzlich. Dann geht es nur nach dem Ausschalten dieses Modus.
6. Fertig: Das Symbol heißt **My Forthing**. Weiter mit [`BENUTZUNG.de.md`](BENUTZUNG.de.md), „Erster Start“.

Mit USB-Debugging am PC geht es auch so: `adb install MyForthing-1.0.1-2-arm64-v8a.apk`.

## 4. Berechtigungen, die die App später fragt

| Berechtigung | Wann | Wofür | Ablehnen? |
| --- | --- | --- | --- |
| Standort (genau) | auf dem Bildschirm „Verbindung“, Schaltfläche „Berechtigung erteilen“ | Android verrät den WLAN-Namen nur Apps mit Standort-Berechtigung; die App prüft damit nur, ob du im Dashcam-WLAN bist. Der Standort wird weder gespeichert noch gesendet. | geht: die App verbindet trotzdem, zeigt dann „unbekanntes WLAN (Berechtigung fehlt)“ |
| Benachrichtigungen (Android 13+) | beim ersten Download oder Hochskalieren | Fortschritt von Downloads, Hochskalieren und Sicherung; „Abbrechen“ bei Download und Hochskalieren | geht: alles läuft, nur ohne Anzeige außerhalb der App |

## 5. Aktualisieren auf eine neue Version

1. Neue APK-Datei genauso öffnen und **Installieren** bzw. **Aktualisieren** tippen. **Nicht vorher deinstallieren.**
2. Alle Daten bleiben erhalten (Profil, heruntergeladene Aufnahmen, Screenshots, Kennzeichen-Verlauf, Einstellungen,
   Drive-Verbindung).

Das klappt nur, wenn die neue Datei eine höhere Build-Nummer hat und mit demselben Schlüssel signiert ist. Typische
Meldungen:

| Meldung | Ursache | Was tun |
| --- | --- | --- |
| „App nicht installiert, da das Paket mit einem vorhandenen Paket in Konflikt steht“ | auf dem Handy ist eine anders signierte Ausgabe (z. B. eine Entwickler-Version aus Android Studio) | nur nach Rücksprache deinstallieren – **das löscht alle App-Daten auf dem Handy** (siehe 6) |
| „App nicht installiert“ beim Zurückgehen auf eine ältere Datei | Android erlaubt kein Downgrade | die neueste Datei verwenden |
| „App nicht installiert“ bei einer neuen Installation | falsche Prozessor-Variante | die andere APK-Datei nehmen (Abschnitt 1) |

## 6. Deinstallieren

*Einstellungen → Apps → My Forthing → Deinstallieren.* Dabei löscht Android **alles, was die App auf diesem Handy
gespeichert hat**: Offline-Profil, heruntergeladene Aufnahmen, Screenshots in der App, verbesserte und hochskalierte
Dateien, Kennzeichen-Verlauf, Einstellungen. Die App nutzt bewusst keine Android-Datensicherung.

Unberührt bleiben: alles auf der SD-Karte der Dashcam, alles im Ordner „My Forthing“ in deinem Google Drive, dein
Online-Konto (falls angelegt) und Screenshot-Kopien in der Fotos-App.

## 7. Hinweis für später

Google hat angekündigt, die Installation von Apps außerhalb des Play Stores auf zertifizierten Android-Geräten an eine
Registrierung des Entwicklers zu knüpfen (zuerst in einzelnen Ländern ab September 2026, breiter ab 2027). Funktioniert
die Installation eines Tages nicht mehr wie oben beschrieben, bitte beim Entwickler melden.
