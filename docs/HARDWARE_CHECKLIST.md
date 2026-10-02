# Hardware-Checkliste (für den Besitzer)

Alles, was nur mit der echten Dashcam, einem echten Handy oder der echten Google-Einrichtung geprüft werden kann.
Zusammengeführt aus `docs/features/*.md` (dashcam, live, media, accounts, drive, backup, plates, enhance) und
`recorder/README.md`. Bisher lief die App nur gegen den Simulator und auf dem Emulator – **nichts hier ist schon
bestätigt.**

Reihenfolge = Risiko: zuerst nur lesen, dann Dateien aufs Handy, dann Cloud, zuletzt Befehle, die an der Dashcam etwas
ändern oder löschen. Bitte nicht springen: Wenn ein früher Schritt scheitert, sind spätere meist sinnlos.

## So läuft es ab

- **Vorher**: wichtige Aufnahmen von der SD-Karte am PC sichern (Kartenleser). Notieren: Handy-Modell,
  Android-Version, App-Version (*Einstellungen des Handys → Apps → My Forthing*), WLAN-Name und Passwort der Dashcam.
- **Eine Sache nach der anderen.** Bei einer Überraschung (Absturz, Dashcam startet neu, falsche Werte) anhalten,
  Diagnose erfassen (A2), notieren.
- **Ablage**: alles in einen Ordner `C:\Users\Jan\Desktop\My Forthing\reference\hardware\<Datum>\` (vom Git
  ausgenommen, wird nie veröffentlicht):
  - Diagnose-Dateien (`myforthing-diagnose-….json`, über **Teilen** z. B. an dich selbst / in Drive),
  - Bildschirmfotos des Handys (Ein/Aus + Leiser),
  - `notizen.md` mit der Punktnummer aus dieser Liste, Uhrzeit, was du getan hast, was du gesehen hast.
- **Weitergeben**: in der nächsten Sitzung mit dem Projektmanager (Claude) sagen „Hardware-Ergebnisse liegen in
  `reference/hardware/<Datum>`“. Er überträgt die Befunde in die Feature-Dokumente und plant Korrekturen. Diagnose-Dateien
  enthalten Seriennummer, Firmware und WLAN-Namen (Passwörter, Token und Schlüssel sind geschwärzt): nicht öffentlich teilen.
- Für Messungen mit ADB (Abschnitt H) ist ein Debug-Build nötig. Der ist anders signiert als die Release-APK: Wechsel
  zwischen beiden geht nur mit Deinstallieren, das löscht die App-Daten auf dem Handy. H daher auf einem Zweit-Handy
  oder ganz am Anfang/Ende machen.

## 0. Installation (Release-APK)

0.1 Installation nach `INSTALLATION.de.md` auf dem eigenen Handy. **Beobachten:** Warnungen (Play Protect), welche APK
(arm64-v8a / armeabi-v7a) passte, Android-Version. **Notieren:** genaue Meldungen.
0.2 Später, mit der nächsten Version: Update über die alte Version ohne Deinstallieren; Profil und Downloads müssen bleiben.

## A. Nur lesen – kein Risiko für die Dashcam

**A1 Verbindung** (Zündung an, Handy im FORTHING-WLAN)
- Verbindung → **Verbinden**, einmal mit **mobilen Daten an**, einmal **aus**.
- **Beobachten:** erreicht die App **Verbunden**? Wie lange dauern die Schritte WLAN / TCP-Verbindung / Sitzung? Wird der
  WLAN-Name erkannt (mit Standort-Berechtigung)? Zeigt die Karte Gerät und Firmware? Fehlermeldung mit Code?
- Fragt Android nach „WLAN ohne Internet“? Was hast du gewählt? Funktioniert dabei Internet über mobile Daten weiter
  (z. B. Browser)?
- App in den Hintergrund (Home-Taste), 30 s warten, zurück: verbindet sie sich neu? Während der Verbindung das WLAN kurz
  wechseln oder die Zündung aus: welche Meldung?
- Prüft: WLAN-Bindung ohne Internet-Fähigkeit, Sitzungsaufbau mit dem echten Schlüssel (aescode), verschlüsselter
  Verkehr, Heartbeat (Abbruch nach ≈ 11 s ohne Antwort), Lebenszyklus.

**A2 Diagnose erfassen und teilen** – das Wichtigste der ganzen Liste
- Verbindung → **Diagnose** → **Diagnose erfassen** → **Teilen** → Datei ablegen.
- Zweimal: direkt nach „Verbunden“ und nach 2–3 Minuten Fahrt bzw. laufender Aufnahme (dann sind Statusmeldungen dabei).
- **Beobachten:** dauert es lange, bleiben Fragen ohne Antwort (Fähigkeiten 20480–20485)? Bricht die Verbindung dabei ab?
- Liefert ohne weiteres Zutun: Sitzungsantwort (`version`, `productType`, `timeOut`), Geräteinfo (4098: Modell,
  Seriennummer, Firmware, Hardware, MCU), alle Einstellungen (4097, inkl. ob `osdContent` eine Zahlenliste ist), Speicher
  (4099: Werte und vermutliche Einheiten), Fähigkeiten, ob `sdStatus`/`recStatus` gemeldet werden, echte Fehlercodes.

**A3 SD-Karte und Einstellungen ansehen** (nichts ändern)
- *Startbildschirm → SD-Karte*: Werte notieren und mit der Kartengröße vergleichen (Einheit?). Kartenstatus vorhanden?
- *Einstellungen* (Dashcam-Teil): stimmen die angezeigten Werte mit dem Menü der Dashcam bzw. der Hersteller-App
  überein? Was steht unter „Weitere Werte (unbestätigt)“?
- Home-Karte: welcher Aufnahmestatus wird gemeldet, passt er (läuft eine Aufnahme)?

**A4 Live-Ansicht**
- *Startbildschirm → Live-Ansicht*, 1 Minute laufen lassen, dann **Vollbild** und zurück.
- **Beobachten:** kommt ein Bild? Wie lange bis zum ersten Bild? Verzögerung (Hand vor die Kamera, Sekunden zählen)?
  Bildfehler, Farben, ruckelt es? Mit mobilen Daten an und aus. Fehlermeldung und Code, falls nicht.
- 10 Minuten am Stück: bleibt der Bildschirm an, bleibt die Verbindung?
- **Screenshot**: Datei unter Aufnahmen → Handy und in der Fotos-App; Größe und Farben richtig?
- Optional, für Fachleute: mit VLC auf einem zweiten Gerät `rtsp://192.168.42.1/ch1/sub/av_stream` öffnen, einmal
  **ohne** dass die App verbunden ist (braucht das Livebild eine Sitzung?) und einmal **gleichzeitig** mit der App
  (teilt die Dashcam, verweigert sie, bricht die erste ab?).

**A5 Aufnahmen ansehen** (nichts herunterladen)
- *Aufnahmen*: Reiter Schleife, Vorfälle, Fotos ganz durchscrollen.
- **Beobachten:** Reihenfolge (neueste oben?), Anzahl gegenüber der Anzeige „… Dateien … laut Recorder“, doppelte oder
  fehlende Einträge beim Weiterladen, erscheint „Die Liste endet hier“? Vorschaubilder da? Enthält „Fotos“ auch Videos?
- **Uhrzeit**: die Zeit der neuesten Aufnahme mit der echten Uhrzeit vergleichen (Zeitzone der Dashcam).
- Während die Dashcam aufnimmt, 3–5 Minuten auf dem Reiter Schleife bleiben: erscheinen neue Clips von selbst?

## B. Dateien aufs Handy – die Dashcam wird nur gelesen

**B1 Download**
- Einen Schleifen-Clip herunterladen. **Beobachten:** Dauer und Größe (MB/s), Benachrichtigung, spielt der Clip?
- Einen größeren Clip starten und bei ≈ 50 % den Flugmodus 10 s einschalten, dann wieder verbinden: macht der Download
  an der gleichen Stelle weiter oder fängt er neu an? (Zeigt, ob die Dashcam Teil-Downloads unterstützt.)
- Zwei Clips gleichzeitig: laufen beide?
- Einen größeren Clip starten und dann in der App **Trennen** (Dashcam-WLAN bleibt verbunden): läuft der Download
  weiter? (Zeigt, ob die Dashcam nach dem Ende der Steuer-Sitzung weiter Dateien über HTTP liefert; die App lässt einen
  laufenden Download weiterlaufen.)
- Länger als eine Clip-Länge warten und dann einen alten Schleifen-Clip laden, der inzwischen überschrieben sein könnte:
  welche Meldung?

**B2 Kennzeichen in echten Clips**
- 3–5 heruntergeladene Clips mit lesbaren Kennzeichen (Tag, Nacht, Regen, Autobahn): **Clip auf Kennzeichen prüfen**.
- **Notieren** je Clip: Dauer der Prüfung, sichtbare Kennzeichen, davon richtig erkannt, mit `?`, falsch, verpasst.
  Stimmen die Rahmen an den Sprungmarken? Wie oft wird die Plakette als Buchstabe gelesen?
- Mit „In gespeicherten Clips“ an: einen ganzen Clip herunterladen – wird er automatisch geprüft, wie lange pro Minute?
- Stimmt die Zeit einer Sichtung aus einem Clip mit der Wirklichkeit (Dashcam-Zeit = Clip-Anfang?)?

**B3 Kennzeichen live**
- Live-Ansicht, Taste **Kennzeichen**: Welche „Bilder/s“ zeigt die App? Wird das Handy spürbar warm?
- **Lage der Rahmen**: geparkte Autos, ein Kennzeichen in der Bildmitte und eins am Bildrand; je normal und im
  **Vollbild**, Handy hochkant und quer. **Beobachten:** liegt der Rahmen auf dem Schild, oder ist er versetzt, zu groß,
  zu klein? Bei Versatz: Richtung, ungefähr wie viele Schildbreiten, überall gleich oder nur am Rand bzw. nur im
  Vollbild? Bildschirmfoto des Handys dazu.
- **Nachlauf**: an einem vorbeifahrenden Auto bzw. bei langsamer Fahrt. **Beobachten:** wie weit hängt der Rahmen hinter
  dem Schild (Schildbreiten), wie lange bleibt er stehen, wenn das Auto schon aus dem Bild ist (Sekunden)? Erwartet: die
  App prüft nur alle ≈ 0,3–1 s ein Bild, so lange darf ein Rahmen nachlaufen. Deutlich länger oder Rahmen ohne Auto
  darunter: notieren.
- **Schärfen darf die Erkennung nicht erreichen** (nur, wenn **Schärfen** angeboten wird): Erkennung und **Screenshot**
  lesen das Bild ungeschärft aus dem Videostrom (`TextureView.getBitmap()`), die Schärfung gilt nur für die Anzeige.
  Prüfen bei stehendem Bild: **Schärfen** aus → **Screenshot**, **Schärfen** an → **Screenshot**, dann mit **Kennzeichen**
  an eine neue Sichtung im **Verlauf** öffnen (Ausschnitt). Beide Screenshots am PC nebeneinander vergrößert
  vergleichen: Sie müssen gleich scharf sein (keine zusätzlichen hellen oder dunklen Säume an Kanten im zweiten), der
  Ausschnitt ebenso. Sieht der zweite geschärft aus, bekommt auch die Kennzeichenerkennung geschärfte Bilder: melden.

**B4 Verbessern und Hochskalieren**
- Einen 1080p-Frame **Bild verbessern** mit KI-Modell ×4: Dauer gegenüber „Dauer etwa …“, Vergleich mit Zoom, Größe
  der gespeicherten Datei, Meldungen zu Speicher auf einem Handy mit 2–3 GB RAM.
- Gesten: ohne Zoom mit einem Finger über das Bild scrollen (hoch und quer), mit zwei Fingern zoomen, Umschalter behält
  den Zoom.
- Einen echten Clip **hochskalieren** auf 1080p, 1440p und 2160p (Klassisch; auf dem Emulator ging keine Ausgabe): Dauer pro Minute, spielt das Ergebnis in der App
  und in einem anderen Player, Ton synchron? Zweiter Lauf: steht jetzt „etwa … Rechenzeit“? Einmal mit Bildschirm aus und
  App im Hintergrund, einmal **Abbrechen** aus der Benachrichtigung (bleibt nichts übrig?).
- Welche Ausgaben sind auf diesem Handy ausgegraut? Darf nie eine angeboten werden, die dann mit „Encoder“ scheitert.
- Ein KI-Hochskalieren von wenigen Sekunden: Dauer, Akku, Wärme.
- **Schärfen** in der Live-Ansicht: wird es angeboten (Android 13+)? An/aus im Vergleich, Vollbild, Akku über 10 Minuten.

## C. Cloud – erst nach der Einrichtung in `SETUP.md`

**C1 Online-Konto** (erst mit einem Build, der `app/google-services.json` enthält; 1.0.0 Build 1 hat sie nicht)
- Mit Google anmelden; mit E-Mail registrieren: kommt die Bestätigungs-Mail (deutsch), funktioniert der Link,
  „Ich habe bestätigt“? Passwort-zurücksetzen-Mail.
- Profilname ändern, auf einem zweiten Handy anmelden: kommt Name/Bild/Design an? Mit bestehendem Konto auf einem Handy
  mit Gast-Profil: erscheint „Welches Profil behalten?“
- Fehlermeldungen notieren (wörtlich).

**C2 Google Drive verbinden**
- Verbinden mit einem Testnutzer: zeigt Google nur die Erlaubnis für „Dateien dieser App“? Speicheranzeige?
- **Konto wechseln** auf ein zweites Konto. App unter myaccount.google.com/permissions entfernen → nächste Aktion zeigt
  „Erneut verbinden“? **Trennen**.

**C3 Sicherung**
1. Modus „Vorfälle“, einen Vorfall-Clip im WLAN mit Internet herunterladen → Benachrichtigung „Sicherung: …“, Anzeige
   „In Drive gesichert“; in Drive `My Forthing/media/<Jahr-Monat>/<id>.mp4` + `<id>.json`.
2. Flugmodus mitten im Hochladen, App beenden, online neu starten → geht es weiter, ohne zweite Datei in Drive?
3. Handy nur im Dashcam-WLAN + mobile Daten: mit „Nur WLAN mit Internet“ lädt nichts; aus + „Mobile Daten verwenden“ an:
   lädt über mobile Daten.
4. Zugriff unter myaccount.google.com/permissions widerrufen → Hinweis „Google Drive erneut verbinden“, Warteschlange
   pausiert, nach dem Verbinden geht es weiter.
5. Volles Drive (Testkonto) → Pause mit Speicheranzeige und „Fortsetzen“.
6. Konto wechseln → alle „In Drive gesichert“-Anzeigen verschwinden, nichts landet im alten Konto.
7. **Drive-Kopie löschen** → beide Dateien in Drive weg, Handy-Kopie spielt noch. Eine Datei im Drive-Web löschen →
   **Drive-Status prüfen** meldet sie als fehlend.

## D. Befehle, die neue Dateien auf der SD-Karte anlegen – geringes Risiko

**D1 Foto, 5er-Serie, Aufnahme** (Live-Ansicht)
- **Foto**: Antwort der App wörtlich notieren (Pfad, „Zeit laut Recorder“). Liegt das Foto danach unter Aufnahmen → Fotos?
- **5er-Serie**: wie viele Antworten kommen, in welchen Abständen (Meldung „… weitere Antworten“)? Wirklich 5 Fotos?
- **Aufnahme**: kommt die Antwort sofort oder erst am Ende? Wie lang ist der entstandene Clip wirklich (der Countdown der
  App zeigt 10 s)? In welchem Reiter erscheint er?
- Stört einer der Befehle das Livebild (Meldung „Nicht im Vorschaumodus“, Code 304)?

## E. Einstellungen ändern – umkehrbar

Jede Änderung einzeln, danach den alten Wert wieder einstellen.
- Zuerst **Ton aufnehmen** umschalten: steht **bestätigt**? Wie lange dauert es? Stimmt es im Menü der Dashcam?
- Dann **Länge der Loop-Clips**, **Videoauflösung**, **WDR**, **Parküberwachung**, **Vorfälle überschreiben**,
  **Ausschaltverzögerung**, **Fahrinfo-Einblendung** (ist sie danach im Video ein/aus?).
- **Empfindlichkeit G-Sensor**: stimmt „Hoch/Mittel/Niedrig“ der App mit dem Menü der Dashcam überein (die Zuordnung ist
  im Hersteller-Code widersprüchlich)? Nicht im Fahrbetrieb testen.
- **Notieren** bei jeder Abweichung: „gesendet: X, zurückgelesen: Y“, „angenommen, aber nicht zurückgelesen“, Codes.

## F. Eine Datei auf der Dashcam löschen

- Einen unwichtigen Schleifen-Clip: **Löschen → Recorder-Kopie löschen**. Verschwindet er in der Liste und auf der Karte?
  Fehlercode, falls nicht. Danach einen Clip löschen, der schon weg ist (Code?). Mehrere auf einmal löschen.

## G. Hohes Risiko – zuletzt, mit Zeit und gesicherter Karte

**G1 WLAN-Passwort ändern** – Risiko: du kommst nicht mehr ins WLAN der Dashcam.
- Neues Passwort vorher aufschreiben (8–16 Zeichen, Buchstaben und Ziffern). Hersteller-App als Rückfallweg bereithalten.
- **Beobachten:** Antwort, startet die Dashcam ihr WLAN neu (wann?), Verbindung mit dem neuen Passwort, bleibt der
  WLAN-Name gleich? Welche Längen/Zeichen nimmt die Firmware an?

**G2 SD-Karte formatieren** – löscht alle Aufnahmen auf der Karte.
- Nur mit gesicherter Karte. **Beobachten:** Dauer (die App wartet bis 60 s), Antwort, Speicherwerte danach (A3).

**G3 Werkseinstellungen** – setzt alles zurück, vermutlich auch das WLAN.
- **Beobachten:** was wird zurückgesetzt (WLAN-Name/Passwort, Einstellungen), bricht die Verbindung ab, wie kommst du
  wieder hinein? Danach Diagnose (A2) erneut erfassen.

## H. Messungen am Handy mit ADB (Debug-Build, siehe Hinweis oben)

Voraussetzung: USB-Debugging, `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, beide APKs mit
`adb install -r` installiert, Handy nicht am Laden für Akkuwerte, ≈ 25 °C.
- **Kennzeichen-Geschwindigkeit und -Genauigkeit**: `docs/features/plates.md`, „Owner measurement procedure“, Schritte
  2–5 (PlateBenchmark, PlateEvaluationTest, CPU und Temperatur mit Erkennung an/aus).
- **Verbessern/Hochskalieren**: `docs/features/enhance.md`, „Measure on a phone“, Schritte 1–5 (EnhanceBenchmark, Akku,
  Temperatur).
- Ergebnisse (logcat-Ausgaben, `dumpsys`-Dateien) in den Ablageordner; die Zahlen kommen in die Feature-Dokumente.
