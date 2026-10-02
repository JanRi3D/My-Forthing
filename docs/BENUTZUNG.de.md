# My Forthing benutzen

Für den Besitzer und Tester. My Forthing verbindet dein Handy mit der Dashcam des Forthing 4 U-Tour: Livebild,
Aufnahmen herunterladen, Einstellungen der Dashcam, Sicherung in deinem Google Drive, Kennzeichenerkennung und
Bildverbesserung – alles auf dem Handy, ohne Pflicht-Konto. Installation: [`INSTALLATION.de.md`](INSTALLATION.de.md).

Diese Anleitung beschreibt den vollen Umfang von Version 1.0.0. Fehlen in deiner Installation „Sicherung“ oder die
Kennzeichen-Suche auf dem Startbildschirm, ist es ein Vorab-Build ohne diese Teile; fehlt das Konto („Konto-Dienst ist
in dieser Installation nicht eingerichtet.“), wurde der Build ohne Firebase-Konfiguration erstellt. Beides ist kein Fehler
deines Handys.

**Wichtig vorab:** Die App ist bisher nur mit einem Simulator und auf dem Android-Emulator getestet, nicht mit der
echten Dashcam (siehe „Bekannte Einschränkungen“ und [`HARDWARE_CHECKLIST.md`](HARDWARE_CHECKLIST.md)).

## 1. Erster Start (ohne Konto)

1. Auf **Willkommen** tippst du auf **Ohne Konto fortfahren**.
2. **Ohne Konto nutzen**: einen **Profilnamen** eingeben (z. B. „Mein Auto“), optional ein **Profilbild** wählen.
3. **Offline-Profil erstellen**. Das Profil bleibt nur auf diesem Handy; kein Internet, keine E-Mail, kein Passwort.

Du landest auf dem Startbildschirm mit der Karte **Meine Dashcam** und den Kacheln **Live-Ansicht**, **Aufnahmen**,
**SD-Karte** und **Einstellungen**. Ein Konto kannst du jederzeit später hinzufügen (Abschnitt 7).

Aussehen: *Einstellungen → Darstellung*: **Material You** (Farben aus deinem Hintergrundbild, hell/dunkel wie das
Handy) oder **Schwarz** (immer dunkel).

## 2. Mit der Dashcam verbinden

Die Dashcam hat ein eigenes WLAN ohne Internet. So klappt es (steht auch in der App unter *Verbindung → „So klappt die
Verbindung“*):

1. **Zündung einschalten.** Die Dashcam läuft nur bei eingeschalteter Zündung (ACC). In der Nähe des Autos bleiben.
2. In den **WLAN-Einstellungen des Handys** das WLAN wählen, dessen Name mit **FORTHING-** beginnt (die Endung aus
   Buchstaben und Ziffern ist ab Werk vergeben).
3. **WLAN-Passwort**: ab Werk **12345678** (kann abweichen, wenn es geändert wurde; die App zeigt den Werkswert unter
   „So klappt die Verbindung“).
4. Fragt Android, ob du mit einem WLAN **ohne Internetzugang** verbunden bleiben willst: **Ja / Verbunden bleiben**.
5. In der App auf die Karte **Meine Dashcam** tippen → Bildschirm **Verbindung** → **Verbinden**.
   - Beim ersten Mal fragt die App nach der Berechtigung, um den **WLAN-Namen zu erkennen** (Standort). Ohne sie
     verbindet die App trotzdem.
   - Heißt das WLAN anders („Anderes WLAN verbunden“), aber es ist sicher deine Dashcam: **Trotzdem verbinden**.
6. Die Schritte **WLAN**, **TCP-Verbindung (Port 7878)** und **Sitzung (Schlüsselaustausch)** werden nacheinander
   „erledigt“. Erst bei **Verbunden** funktionieren Befehle; die Karte zeigt dann WLAN, Gerät, Firmware und freien
   Speicher „laut Recorder“.

**Mobile Daten:** Die App spricht mit der Dashcam über deren WLAN und lässt die mobilen Daten für alles andere (Drive,
Konto) eingeschaltet. Klappt die Verbindung trotz verbundenem WLAN nicht, **mobile Daten ausschalten** und erneut
versuchen – die App weist darauf hin.

Die Verbindung endet, wenn die App in den Hintergrund geht, und baut sich beim Zurückkehren wieder auf. **Trennen**
beendet sie sofort. Fehler zeigt die App immer mit Code, z. B. „Recorder nicht erreichbar (Code -101)“.

## 3. Live-Ansicht

*Start → Live-Ansicht* (nur bei „Verbunden“).

- Das Livebild läuft ohne Ton. Oben das Abzeichen **Live** und die **Handyzeit** (die Uhrzeit des Handys, nicht der
  Dashcam). **Vollbild** über die Schaltfläche oben, zurück mit **Zurück** oder „Vollbild verlassen“.
- **Screenshot**: speichert das aktuelle Bild in der App (Aufnahmen → Handy) und ab Android 10 zusätzlich in der
  Fotos-App (Ordner „Pictures/My Forthing“). Unter Android 8–9 nur in der App.
- **Auf der SD-Karte der Dashcam** (die Dashcam selbst speichert):
  - **Foto** – ein Foto, **5er-Serie** – fünf Fotos,
  - **Aufnahme** – startet eine manuelle Aufnahme. Der Countdown „Aufnahme · 10 s“ ist nur die Anzeige der App; die
    Länge des Clips bestimmt die Dashcam.
  - Die Antwort steht darunter („Foto – Recorder meldet: …“). „**Ergebnis unbekannt – neu verbinden und prüfen**“
    heißt: keine Antwort; ob die Dashcam es ausgeführt hat, siehst du in den Aufnahmen.
- Wenn das Livebild nicht kommt: „Livebild nicht verfügbar“ mit Grund und Code, dann **Erneut versuchen**.
- **Kennzeichen** (links neben Screenshot) schaltet die Kennzeichenerkennung ein (Abschnitt 8), **Schärfen** (rechts)
  die geschärfte Ansicht (Abschnitt 9).

## 4. Aufnahmen

*Start → Aufnahmen*. Reiter:

| Reiter | Inhalt |
| --- | --- |
| **Schleife** | normale Dauer-Aufnahmen der Dashcam (Loop) |
| **Vorfälle** | geschützte Clips, z. B. nach einem Stoß (G-Sensor) |
| **Fotos** | Fotos der Dashcam |
| **Handy** | alles, was schon auf dem Handy ist: Downloads, Screenshots, verbesserte Dateien – geht auch ohne Dashcam |

- Die ersten drei Reiter brauchen die Verbindung. Die Liste lädt beim Scrollen weiter; **Aktualisieren** lädt neu.
- Zeiten sind „**laut Recorder, Zeitzone unbekannt**“: die Uhr der Dashcam, als Handy-Zeit gelesen.
- **Herunterladen**: Datei lange drücken (Auswahl), weitere antippen, dann **Herunterladen**. Höchstens zwei laufen
  gleichzeitig. Fortschritt unter **Übertragungen** (oben) und in der Benachrichtigung („Download: …“, **Abbrechen**).
  Bricht die Verbindung ab, wartet der Download („Wartet auf die Dashcam-Verbindung“) und macht nach dem nächsten
  Verbinden an der gleichen Stelle weiter.
- **Clip öffnen** (antippen, wenn auf dem Handy): Player bzw. Bildansicht, **Teilen**, **Löschen**. Verbesserte Dateien
  zeigen ihr **Original**, Originale zeigen „**Daraus erzeugt**“.
- **Löschen – drei getrennte Kopien.** Eine Aufnahme kann auf der Dashcam, auf dem Handy und in Google Drive liegen.
  **Löschen** fragt „**Welche Kopie löschen?**“: **Handy-Kopie löschen**, **Recorder-Kopie löschen** (braucht die
  Verbindung) oder **Drive-Kopie löschen**. Jede Kopie wird einzeln gelöscht, die anderen bleiben. Ist es die letzte
  Kopie, warnt die App, dass die Datei danach endgültig weg ist.

**SD-Karte** (*Start → SD-Karte*): Speicherwerte „laut Recorder“ (Rohwerte, Einheiten unbekannt), letzter
**Kartenstatus** und **Formatieren**. Formatieren löscht **alle** Aufnahmen und Fotos auf der Karte; auf das Handy
geladene bleiben. Die App fragt doppelt („Mir ist klar, dass sich das nicht rückgängig machen lässt.“).

**Dashcam-Einstellungen** (*Einstellungen*, unter den App-Einstellungen, nur bei „Verbunden“): Videoauflösung, Länge der
Loop-Clips, Ton aufnehmen, WDR, Empfindlichkeit G-Sensor (Hoch/Mittel/Niedrig), Parküberwachung, Vorfälle
überschreiben, Fahrinfo-Einblendung, Ausschaltverzögerung, WLAN-Passwort, Werkseinstellungen.
- Nach einer Änderung liest die App den Wert zurück. Nur dann steht **bestätigt**. „gesendet: X, zurückgelesen: Y“ oder
  „angenommen, aber nicht zurückgelesen“ heißen: nicht sicher übernommen.
- **WLAN-Passwort ändern**: nur das Passwort (8–16 Zeichen, Buchstaben und Ziffern, ohne Leerzeichen und Umlaute), zweimal
  eingeben. Danach das Handy mit dem neuen Passwort neu mit dem Dashcam-WLAN verbinden. **Passwort notieren!**
- **Werkseinstellungen** setzen die Dashcam zurück, vermutlich auch WLAN-Name und Passwort.

## 5. Speicher auf dem Handy

*Einstellungen → Speicher*: Platz für **Downloads**, **Screenshots**, **Verbesserte Dateien**, **Cache** und
**Kennzeichen-Ausschnitte** sowie „Frei auf dem Handy“. **Freigeben** löscht nur Kopien auf diesem Handy; Dashcam und
Drive bleiben. Gibt es Dateien nur noch auf dem Handy, sagt die Bestätigung, wie viele danach endgültig weg sind.

Alle App-Daten liegen im privaten Speicher der App, nicht in der Android-Datensicherung. Deinstallieren löscht sie
(siehe Installationsanleitung).

## 6. Google Drive und Sicherung

**Verbinden** (*Einstellungen → Google Drive → Mit Google Drive verbinden*): Google fragt nach dem Konto und nach der
Erlaubnis, **nur die Dateien dieser App** zu sehen und zu ändern. Die App legt in deinem Drive den Ordner
**„My Forthing“** an und sieht nichts anderes. Danach steht dort „x von y belegt · z frei“.
- Unabhängig vom App-Konto: geht auch ohne Konto, und du kannst ein anderes Google-Konto nehmen als für die Anmeldung.
- **Konto wechseln** nimmt ein anderes Google-Konto; die Sicherungsstände des alten Kontos gelten dann nicht mehr.
- **Trennen**: die App verliert den Zugriff, die Dateien bleiben in deinem Drive.
- Steht dort **Erneut verbinden**, hat Google den Zugriff beendet (widerrufen oder abgelaufen): einmal antippen.

**Sicherung** (*Einstellungen → Sicherung*):

| Einstellung | Bedeutung |
| --- | --- |
| **Nur manuell** | nur, was du unter Aufnahmen auswählst und mit **Sichern** sicherst |
| **Vorfälle** | Vorfall-Clips automatisch, sobald sie auf dem Handy sind |
| **Alles** | Aufnahmen, Fotos und Screenshots, sobald sie auf dem Handy sind; verbesserte Dateien, sobald ihr Original gesichert ist |
| **Nur WLAN mit Internet** (Standard: an) | wartet auf ein WLAN mit funktionierendem Internet – das Dashcam-WLAN zählt nie |
| **Mobile Daten verwenden** | nur wählbar, wenn „Nur WLAN mit Internet“ aus ist |
| **Kennzeichen-Daten mitsichern** | erkannte Kennzeichen (Text, Stelle im Clip, Rahmen im Bild und ein interner Lesewert, der keine Trefferquote ist) kommen in die Begleitdatei neben der Aufnahme; gilt für Sicherungen ab dem Einschalten (Standard: aus) |

Regeln:
- **Gesichert wird nur, was auf dem Handy liegt.** Direkt von der Dashcam wird nichts hochgeladen: erst herunterladen.
  Im Auto (Dashcam-WLAN) passiert also nichts; die Sicherung läuft z. B. zu Hause im WLAN.
- **„In Drive gesichert“** heißt: vollständig hochgeladen, Prüfsumme gleich der Handy-Kopie, Begleitdatei daneben.
  Andere Anzeigen: „Wird gesichert“, „Sicherung fehlgeschlagen“ (mit Grund).
- Es lädt immer nur eine Datei gleichzeitig hoch; ein Abbruch (Funkloch, Neustart) macht später an der gleichen Stelle
  weiter. Fortschritt in der **Warteschlange** und als Benachrichtigung „Sicherung: …“.
- **Drive voll**: die Sicherung pausiert; Platz schaffen, dann **Fortsetzen**. **Zugriff abgelaufen**: Hinweis „Google
  Drive erneut verbinden“.
- **Jetzt prüfen** sucht sofort nach Dateien für die automatische Sicherung; **Drive-Status prüfen** kontrolliert, ob
  gesicherte Dateien noch in Drive liegen (auf dem Handy wird dabei nichts gelöscht).
- In Drive liegt jede Datei unter `My Forthing/media/<Jahr-Monat>/` mit einer gleichnamigen `.json`-Begleitdatei.

## 7. Online-Konto (optional)

Ein Konto speichert dein Profil online, damit es auf einem neuen Handy wieder da ist. Für alles andere brauchst du es
nicht.

- *Einstellungen → Konto → Online-Konto hinzufügen*: **Mit Google fortfahren** oder E-Mail + Passwort (mindestens
  8 Zeichen) und Zustimmung zu Nutzungsbedingungen und Datenschutzerklärung.
- Bei E-Mail kommt ein **Bestätigungslink** („E-Mail bestätigen“: **Mail-App öffnen**, **Ich habe bestätigt**,
  **Erneut senden**). Bestätigen geht auch später.
- Hat das Konto schon ein Profil, fragt die App „**Welches Profil behalten?**“ – **Dieses Handy** oder **Konto**. Ohne
  deine Wahl wird nichts überschrieben.
- **Synchronisiert** werden Profilname, Profilbild, Design und Exportqualität. **Nicht**: Kennzeichen-, Hochskalierungs-
  und Sicherungsoptionen (bleiben auf dem Handy) und die Dashcam-Einstellungen (bleiben in der Dashcam).
- **Abmelden** lässt das Profil auf dem Handy. **Passwort vergessen?** schickt einen Link zum Zurücksetzen.

## 8. Kennzeichenerkennung

*Einstellungen → Kennzeichenerkennung*: **In der Live-Ansicht**, **In gespeicherten Clips** (prüft neu heruntergeladene
Clips automatisch, einen nach dem anderen), **Kennzeichen-Daten in Drive-Sicherung einschließen**, **Verlauf löschen**.

- **Live**: Taste **Kennzeichen** in der Live-Ansicht. Erkannte Kennzeichen bekommen einen Rahmen im Bild; darunter
  „Erkannte Kennzeichen“ mit „x-mal gesehen“ und **Verlauf**. „Läuft auf diesem Handy · 3,6 Bilder/s“ zeigt, wie viele
  Bilder das Handy schafft; die Rahmen laufen bewegten Autos etwas hinterher.
- **Clip**: im Clip „**Kennzeichen in diesem Clip**“ mit Sprungmarken („Zu 00:02 springen“) und **Clip auf Kennzeichen
  prüfen** (nur für Clips auf dem Handy; Fortschritt mit **Abbrechen**).
- **Suchen**: *Start → Kennzeichen suchen*. Ein Teil reicht, z. B. die Städtekennung „BMK“ oder die Ziffern „4821“.
  Filter **Alle** / **Vorfälle**. Antippen öffnet den **Verlauf** des Kennzeichens: Sichtungen, zuerst/zuletzt gesehen,
  Ausschnitt; eine Sichtung aus einem Clip auf dem Handy öffnet ihn an der Stelle.

**So ehrlich ist die Erkennung:**
- Ein **?** steht für ein Zeichen, das nicht sicher lesbar war; es wird nicht ergänzt. **Auch Lesungen ohne ? können
  falsch sein.**
- Es gibt **keine Trefferquote und keine Prozentzahl**. Der einzige Hinweis ist „**unsicher**“ bzw. „unsicher gelesen“
  (gelber, gestrichelter Rahmen).
- **Verbesserte oder hochskalierte Dateien werden nie auf Kennzeichen geprüft** – sie sind rekonstruiert, kein Beweis.
  Geprüft wird nur das Original.
- Zeiten: live die Handyzeit, im Clip die Dashcam-Zeit (Zeitzone unbekannt) plus Position im Clip.
- Die Erkennung kann sich irren (Verschmutzung, Verdeckung, Nacht). **Im Original-Video nachsehen**, bevor du dich auf
  ein Kennzeichen verlässt. Zweizeilige Kennzeichen (Motorräder) werden nicht erkannt.
- Alles läuft auf dem Handy; der Verlauf bleibt dort, außer du schaltest die Drive-Option ein. Kennzeichen sind
  personenbezogene Daten: Verlauf und Sicherungen nur weitergeben, wenn du das darfst.

## 9. Bild verbessern und Clip hochskalieren

Im Clip (nur für Originale, die auf dem Handy liegen):

- **Bild verbessern**: nimmt das Bild an der aktuellen Stelle des Videos (oder das Foto). **Faktor** 4× oder 2×,
  **Verfahren** **KI-Modell** (rechnet länger) oder **Klassisch** (schnell). „Dauer etwa …“ steht erst da, wenn das
  Handy einmal gemessen hat. Nach **Verbessern** zwischen **Original** und **Verbessert** umschalten und mit zwei
  Fingern zoomen. **Verbessertes Bild speichern** legt eine neue Datei an; das Original bleibt unverändert.
- **Clip hochskalieren**: **Ausgabe** 1080p, 1440p oder 2160p (4K) – was das Handy nicht erzeugen kann, ist ausgegraut
  („Dieses Handy hat keinen Encoder für …“). **Klassisch** ist der Standard; **KI-Modell** braucht Stunden pro Minute
  Video (Handy dabei laden). **Hochskalieren starten** – läuft im Hintergrund mit Benachrichtigung und **Abbrechen**,
  immer nur ein Clip gleichzeitig. Ab Android 15 sind Aufgaben über etwa 5 Stunden nicht möglich. Das Ergebnis erscheint
  als „Hochskalierter Clip“ in den Aufnahmen.
- **Schärfen** in der Live-Ansicht: schärft die Kanten auf dem Grafikchip (ab Android 13 und nur, wenn das Handy schnell
  genug ist; sonst „Auf diesem Handy nicht verfügbar“). Es entstehen keine zusätzlichen Details; verbraucht mehr Akku.

Alles Verbesserte trägt den Hinweis **„rekonstruiert, kein Beweis“**: die zusätzlichen Pixel sind errechnet, nicht
aufgezeichnet. *Einstellungen → Verbesserung & Hochskalierung*: Standard-Exportqualität, geschärfte Live-Ansicht,
Hinweis, Modell & Lizenzen.

## 10. Diagnose (bei Problemen)

*Verbindung → Diagnose → Diagnose erfassen*, dann **Teilen**. Die App liest dabei nur (Sitzung, Geräteinfo,
Einstellungen, Speicher, Fähigkeiten, die letzten Nachrichten, WLAN-Daten) und ändert nichts. Token, Schlüssel und
Passwörter werden geschwärzt. Die Datei enthält aber Seriennummer, Firmware und WLAN-Namen: nur an den Entwickler
schicken. Ohne Verbindung enthält sie nur WLAN-Daten und das Protokoll.

## 11. Bekannte Einschränkungen

- **Nicht mit der echten Dashcam getestet** (nur Simulator und Emulator): Verbindungsaufbau, Livebild, Dateiliste,
  Downloads, Foto/Aufnahme, Einstellungen mit Rückmeldung, Formatieren, Werkseinstellungen, Statusmeldungen. Erste
  Schritte deshalb nur lesend, wie in [`HARDWARE_CHECKLIST.md`](HARDWARE_CHECKLIST.md).
- **Livebild nie mit einem echten Videostrom abgespielt**; Verzögerung, Bildformat und Screenshots daraus sind offen.
- **Konto und Google Drive** sind ohne echte Google-/Firebase-Einrichtung nicht getestet; ohne sie erscheinen
  „Konto-Dienst ist in dieser Installation nicht eingerichtet.“ bzw. „Google-Cloud-Konfiguration fehlt (Statuscode 10)“.
- **Kennzeichenerkennung** nur mit künstlich erzeugten Bildern geprüft, nicht mit echten Dashcam-Aufnahmen; die
  Live-Erkennung wurde mangels Videostrom gar nicht ausprobiert.
- **Verbessern/Hochskalieren** nur auf dem Emulator gemessen; Zeiten und 1440p/2160p auf echten Handys sind offen.
- Zeiten der Dashcam: Zeitzone unbekannt. Die Bedeutung des Aufnahmestatus („Deutung unbestätigt“) ist offen.
- Kein Streaming direkt von der Dashcam: Clips erst herunterladen. Kein Neustart der Dashcam, kein Firmware-Update,
  keine Zeitraffer-Funktion, keine automatische App-Aktualisierung (neue Versionen per APK, siehe Installation).
- Konto löschen geht noch nicht in der App; Nutzungsbedingungen und Datenschutzerklärung sind Platzhalter.
