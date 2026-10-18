# solvis2mqtt

solvis2mqtt bindet Solvis-Heizungsanlagen mit **SolvisControl 2** und **SolvisRemote** (SolvisMax 6 und 7) per
**MQTT** an Smart-Home-Systeme an, insbesondere an **Home Assistant** über MQTT Auto-Discovery. Messwerte werden
über die XML-Schnittstelle der SolvisRemote gelesen. Sollwerte und Betriebsarten setzt der Server über die
Web-Oberfläche der SolvisRemote, die eine Pixelkopie der SolvisControl ist: Werte werden per OCR gelesen, per
simulierten Klicks geändert und anschließend verifiziert.

Für Anlagen mit **SolvisControl 3** oder einer SC2 ab Firmware MA205 ist Modbus der bessere Weg, zum Beispiel mit
[hass_solvis_control](https://github.com/LarsK1/hass_solvis_control). solvis2mqtt ist für die älteren Anlagen,
die nur die Web-Oberfläche haben.

> **Herkunft:** solvis2mqtt führt den [SolvisSmartHomeServer](https://github.com/GollmerSt/SolvisSmartHomeServer)
> von Stefan Gollmer (GollmerSt) weiter, Basis ist Upstream 01.06.00. Version 1.0.0 reduziert ihn auf eine reine
> MQTT-Bridge: TCP-Client-Schnittstelle, FHEM-Modul, Mail-Versand, Windows-Installer und ioBroker-Generator sind
> entfallen, Docker ist der Standardweg. Die ausführliche Beschreibung der Arbeitsweise, der XML-Dateien und des
> Learnings steht weiterhin im [Upstream-Wiki](https://github.com/GollmerSt/SolvisSmartHomeServer/wiki).
> Fehler und Wünsche bitte als [Issue](https://github.com/lufi42/solvis2mqtt/issues).

## Funktionen

* Auslesen aller Messwerte und Zustände (Fühler, Pumpen, Ventile, Laufzeiten, Zähler, Betriebsarten).
* Setzen der Anlagenparameter: Solltemperaturen, Betriebsarten der Heizkreise, Warmwasser, Zirkulation,
  Heizkurven, optional auch Installateur-Parameter (Feature `Admin`).
* Home Assistant MQTT Auto-Discovery mit Geräten nach dem Solvis-Anlagenschema (siehe unten).
* Erkennen von Anlagenfehlern (Fehlerscreen) sowie von Nutzer- und Service-Eingriffen am Touchscreen mit
  anschließendem Neulesen der Parameter.
* Nachjustieren der Solvis-Uhr.
* Anpassung an die Anlage über `base.xml`; die laut Solvis-Anlagenschema möglichen Konfigurationen der
  SolvisMax 6 und 7 sind in `control.xml` hinterlegt, darunter Pelletkessel SolvisLino und Heizkreise ohne
  Raumfühler.

## Voraussetzungen

* Solvis-Anlage mit SolvisControl 2 und SolvisRemote, erreichbar im lokalen Netz.
* Ein MQTT-Broker (z. B. Mosquitto, in Home Assistant als Add-on).
* Docker, oder ein Rechner mit Java 8 oder neuer.

## Installation mit Docker

Images gibt es auf `ghcr.io/lufi42/solvis2mqtt` (`latest` = letztes Release, `1.x`, `main` = aktueller
Entwicklungsstand; linux/amd64 und linux/arm64, also auch Raspberry Pi 3/4/5 mit 64-Bit-System). Das Image
enthält Java und das Jar; `base.xml` und das Datenverzeichnis `SolvisServerData` liegen auf einem Volume unter
`/data`.

```sh
mkdir data
docker run --rm --entrypoint cat ghcr.io/lufi42/solvis2mqtt /app/base.xml.new > data/base.xml
# data/base.xml anpassen: writablePathLinux="/data", Solvis-Zugang, Anlagentyp, MQTT-Broker (siehe Konfiguration).
# Passwörter verschlüsseln:
docker run --rm ghcr.io/lufi42/solvis2mqtt --string-to-crypt=geheim
# Learning (einmalig und nach jeder Änderung der control.xml, dauert einige Minuten):
docker run --rm -v ./data:/data ghcr.io/lufi42/solvis2mqtt --server-learn
# Betrieb:
docker run -d --name solvis2mqtt --restart unless-stopped --stop-timeout 30 -v ./data:/data ghcr.io/lufi42/solvis2mqtt
```

Alle Argumente hinter dem Image-Namen gehen an den Server. Der Container läuft als Benutzer mit UID 1000, das
Verzeichnis `data` muss für diesen schreibbar sein (oder `--user` setzen). Die Heap-Größe steht in `JAVA_OPTS`
(Default `-Xmx384m`, das Learning braucht etwa 300 MB). Beim Stoppen bekommt der Server SIGTERM, meldet sich beim
Broker ab und bricht eine laufende GUI-Sequenz ab. Vor einem geplanten Stopp daher `GUI_COMMANDS_DISABLE` über
MQTT absetzen und warten, bis `gui_access` nicht mehr `CONTROL_WRITE_ONGOING` zeigt; sonst kann die Anlage in einem
Zwischenzustand der Sequenz zurückbleiben. `--stop-timeout 30` bzw. `stop_grace_period` lassen dem Abmelden Zeit.
Ein Beispiel für Docker Compose liegt in [docker/compose.yaml](docker/compose.yaml).

Ohne Docker: [packaging/linux/INSTALL.md](packaging/linux/INSTALL.md) (Jar, systemd-Unit).

## Konfiguration (`base.xml`)

Die Datei wird gegen `base.xsd` validiert; dort sind alle Attribute dokumentiert. Die wichtigsten Stellen:

```xml
<tns:ExecutionData timeZone="Europe/Berlin" writablePathLinux="/data"
    writeablePathWindows="C:\solvis2mqtt\data" echoInhibitTime_ms="2000" />

<tns:Unit id="mySolvis" type="SolvisMax6" mainHeating="Pellet" heatingCircuits="2"
    url="192.168.0.183" account="solvis" passwordCrypt="..." ...>
    <tns:Features>
        <tns:Feature id="InteractiveGUIAccess" value="true" />   <!-- Schreibzugriff über die GUI -->
        ...
    </tns:Features>
    <tns:Extensions>
        <tns:Extension id="OhneRaumfuehler_HK1" />
    </tns:Extensions>
</tns:Unit>

<tns:Mqtt enable="true" brokerUrl="192.168.0.70" port="1883"
    userName="solvis" passwordCrypt="..."
    subscribeQoS="1" publishQoS="1" idPrefix="solvis2mqtt"
    topicPrefix="solvis2mqtt" smartHomeId="HomeAssistant"
    haDiscoveryPrefix="homeassistant" />
```

* **Unit:** `type` (`SolvisMax6`, `SolvisMax6PurSolo`, `SolvisMax7`), `mainHeating` (`OelBW`, `OelNT`, `Gas`,
  `Fern`, `WaermeP`, `Extern`, `Pellet`), `heatingCircuits` und die Extensions bestimmen, welche Kanäle es gibt.
  Die `id` wird Teil der MQTT-Topics und der Entity-Ids, später nicht mehr ändern.
* **Pelletkessel SolvisLino:** `mainHeating="Pellet"` entspricht `Extern` (Kesselfühler S14, Anforderung über O-1),
  bildet aber die Lino-Belegung ab: A12 ist die interne Brenneranforderung, A14 bleibt unbelegt, die Störmeldung des
  Kessels wird als `binary_sensor` angelegt (I-3 bei `SolvisMax6`, I-1 bei `SolvisMax6PurSolo`).
* **Heizkreise ohne Raumfühler:** Extensions `OhneRaumfuehler_HK1..3` entfernen Raumfühler und Raumeinfluss des
  Heizkreises (kein Kanal, keine Entity).
* **Schreibzugriff:** nur mit Feature `InteractiveGUIAccess`. Ohne dieses Feature liest der Server nur die
  XML-Schnittstelle; alle Kanäle der SolvisControl-Oberfläche (`C*`) entfallen dann.
* **Passwörter** stehen verschlüsselt in der Datei (`--string-to-crypt`).

Vor dem ersten Start und nach jeder Änderung der `control.xml` ist ein **Learning** nötig (`--server-learn`):
Der Server fährt alle Screens an und merkt sich deren Erkennungsmerkmale im Datenverzeichnis. Während des
Learnings und bei Schreibzugriffen darf niemand die SolvisRemote-Oberfläche parallel bedienen.

### MQTT-Topics

Alle Topics beginnen mit `<topicPrefix>/<smartHomeId>/`, Kanalnamen sind `<id>:<name>`, z. B.
`S01:Speicher_oben` oder `C12:Anlagenmodus_HK2`:

Topic | Richtung | Inhalt
--- | --- | ---
`<unitId>/<kanal>/data` | Server → Broker | aktueller Wert (retained)
`<unitId>/<kanal>/meta` | Server → Broker | Beschreibung des Kanals: Typ, Einheit, Wertebereich, schreibbar (retained)
`<unitId>/<kanal>/cmnd` | Broker → Server | neuen Wert setzen
`<unitId>/<kanal>/update` | Broker → Server | `true`: Kanal einmalig neu lesen
`<unitId>/status` | Server → Broker | Verbindung zur Anlage (`SOLVIS_CONNECTED`, `POWER_OFF`, ...)
`<unitId>/gui_access` | Server → Broker | Zustand der GUI-Steuerung (`CONTROL_WRITE_ONGOING`, `CONTROL_FINISHED`, ...)
`<unitId>/human_access` | Server → Broker | manueller Eingriff an der Anlage (`USER_ACCESS_DETECTED`, ...)
`<unitId>/server/cmnd` | Broker → Server | Befehle für die Unit: `GUI_COMMANDS_DISABLE`, `GUI_COMMANDS_ENABLE`, `UPDATE_CHANNELS`, `SERVICE_RESET`, `SERVICE_TRIGGER`, `SCREEN_RESTORE_INHIBIT/ENABLE`, `COMMAND_OPTIMIZATION_INHIBIT/ENABLE`
`server/cmnd` | Broker → Server | Befehle für den Server: `BACKUP`, `RESTART`, `TERMINATE`
`server/online` | Server → Broker | `true`/`false`, Last Will
`online` | Broker → Server | `true`/`false`: das Smart-Home-System ist online
`error` | Server → Broker | Fehlermeldung zu einem ungültigen Befehl

Die Kanalliste der eigenen Konfiguration erzeugt `--documentation --csvSemicolon` als CSV im Datenverzeichnis
(`Appendix/`). `GUI_COMMANDS_DISABLE` vor Wartungsarbeiten an der Anlage absetzen und danach
`GUI_COMMANDS_ENABLE`, der Server fasst die Oberfläche dann nicht an. `RESTART` beendet den Prozess mit
Exit-Code 24, `TERMINATE` mit 0; der Dienst-Manager (Docker-Restart-Policy, systemd `Restart=`) startet ihn neu.

## Home Assistant

Mit `haDiscoveryPrefix` im `Mqtt`-Element veröffentlicht der Server beim Verbindungsaufbau für jeden Kanal eine
retained Discovery-Nachricht; Home Assistant legt die Entities automatisch an. Voraussetzung ist die
[MQTT-Integration](https://www.home-assistant.io/integrations/mqtt/) am selben Broker.

Entity-Typ | Kanäle
---------- | ------
`sensor` | Messwerte (Temperaturen, Laufzeiten, Zähler, ...)
`number` | schreibbare Zahlenwerte (Solltemperaturen, Differenzen, ...)
`select` | Betriebsmodi (Automatik / Tag / Nacht / Standby, ...)
`binary_sensor` | binäre Zustände
`switch` | schreibbare Binärkanäle

Nicht angelegt werden Kanäle ohne Belegung in der Anlagenkonfiguration (z. B. `I-2`, `O-4`) sowie alle `C*`-Kanäle
ohne `InteractiveGUIAccess`.

Die Discovery-Nachrichten enthalten:

* **Namen** aus `ha-labels.properties` (Begriffe des Anschlussplans und der SolvisControl-Bildschirme, z. B.
  `S01` → „Oben“ im Gerät „Speicher“, `C08` → „Absenktemperatur“ im Gerät „Heizkreis 1“).
* **`unique_id` und Entity-Id** aus Unit und Kanal-Id (`sensor.solvis_<unitId>_s01`); ein Umbenennen des Kanals in
  der `base.xml` ändert weder Entity-Id noch Historie.
* **`entity_category`**: `diagnostic` für Anlagentyp, Systemnummer, Uhrzeit und Server-Status, `config` für die
  Installateur-Parameter (`C31`–`C49`).
* **`device_class`** und **`state_class`** aus der Einheit (Temperatur, Dauer, Durchfluss, Leistung, Energie, Druck;
  `total_increasing` für Zähler und Laufzeiten). Differenzen in K bekommen bewusst keine Klasse.
* **`suggested_display_precision`** aus der Messgenauigkeit des Kanals.
* **`value_template`** bei Fühlern: offener oder kurzgeschlossener Fühler (≥ 250 °C, ≤ −35 °C) wird „unbekannt“.
* **`configuration_url`** auf die SolvisRemote.

**Geräte:** Die Entities einer Unit werden nach dem Anlagenschema auf Geräte verteilt, alle mit dem Hauptgerät
„Solvis &lt;unitId&gt;“ verknüpft:

Gerät | Inhalt
--- | ---
`Solvis <unitId>` | Außentemperatur, Anlagentyp, Systemnummer, Uhrzeit, Server-Status
`<unitId> Speicher` | Speicherfühler S1, S3, S4, S9, Pufferparameter
`<unitId> Solaranlage` | Kollektor, Vor-/Rücklauf, Druck, Durchfluss, Pumpen, Ventile, Wärmemenge, Laufzeiten
`<unitId> Warmwasser` | Warmwasser- und Zirkulationsfühler, Pumpen, Solltemperatur, Nachheizen
`<unitId> Heizkreis 1` … `3` | Vorlauf, Raumfühler, Pumpe, Mischer, Betriebsart, Temperaturen, Heizkurve
`<unitId> Wärmeerzeuger` | Kessel/Brenner, Pelletkessel, Wärmepumpe, Fernwärme: Fühler, Anforderung, Ladepumpe, Störung, Laufzeiten, Starts

Die Zuordnung steht in `ha-groups.properties` und berücksichtigt die Belegung des Anlagentyps (`S09.Heissgas` gehört
zur Wärmepumpe, `S09.Heizungspuffertemperatur_unten` zum Speicher). **Anpassung pro Anlage:** eine Datei
`ha-groups.properties` oder `ha-labels.properties` im Datenverzeichnis (neben `graficData.xml`) überschreibt die
eingebauten Einträge: Namen ändern, Kanäle anderen Gruppen zuordnen (`S09=waermeerzeuger`), neue Gruppen anlegen
(`device.keller=Keller`), Kanäle dem Hauptgerät zuweisen (`S14=`). UTF-8, Format wie die eingebauten Dateien unter
`rsc/de/sgollmer/solvismax/data/`, wirksam nach Neustart.

Die Discovery-Nachrichten werden bei jedem Verbindungsaufbau zum Broker und bei jedem Start von Home Assistant
(`homeassistant/status` = `online`) erneut veröffentlicht; verwaiste Nachrichten früherer Läufe (umbenannte Unit,
entfallene Kanäle) löscht der Server. Hat sich das Gerät einer Entity geändert, entfernt der Server die Entity kurz und
legt sie neu an, weil Home Assistant sie sonst nicht umzieht; Entity-Id und Historie bleiben, manuell vergebene Bereiche
oder Namen gehen dabei verloren.

Zusätzliche Status-Entities: Verbindungsstatus, GUI-Status, Anlagenzugriff (je `sensor`) und „Schreibzugriff
aktiviert“ (`binary_sensor`).

## Umstieg vom SolvisSmartHomeServer

Eine bestehende Installation (Linux mit systemd oder Windows) lässt sich ohne neues Learning und ohne Änderung der
MQTT-Topics und Home-Assistant-Entities auf solvis2mqtt umstellen. Der konfigurierte `topicPrefix` und die Unit-Id
bleiben, auch wenn der Prefix noch `SolvisSmartHomeServer` heißt.

### 1. `base.xml` anpassen

Kopie der bisherigen `base.xml` anlegen und darin:

* Attribut `port` am `ExecutionData`-Element entfernen; `writablePathLinux` auf das neue Datenverzeichnis setzen
  (im Container `/data`).
* Elemente `ExceptionMail` und `Iobroker` entfernen.
* Features `SendMailOnError` und `SendMailOnErrorsCleared` entfernen, `ClearErrorMessageAfterMail` heißt jetzt
  `ClearErrorMessage`.
* `Mqtt` muss aktiviert sein (`enable="true"`).

Alles andere (Unit, Features, Extensions, verschlüsselte Passwörter) bleibt gültig; die Verschlüsselung ist zwischen
den Versionen kompatibel. Die Datei wird beim Start gegen `base.xsd` geprüft, Fehler nennen Zeile und Attribut.

### 2. Datenverzeichnis übernehmen

Aus dem bisherigen `writablePath` das Verzeichnis `SolvisServerData` in das neue Datenverzeichnis kopieren, mit
`LearnedImages`, `graficData.xml`, `control.xml`, `control.xsd`, `measurements.xml` und eventuellen
`ha-groups.properties`/`ha-labels.properties`; die Logdateien `solvis-*.log*` können entfallen. Der Server prüft beim
Start, ob die gelernten Daten zur eingebauten `control.xml` und zu den Features der `base.xml` passen; nur wenn
nicht, lernt er neu (dauert einige Minuten, in der Zeit die SolvisRemote nicht bedienen). Ob ein Learning ansteht,
zeigt vorab ein Lauf im Dokumentationsmodus, der die Daten nur liest:

```sh
docker run --rm -v ./data:/data ghcr.io/lufi42/solvis2mqtt --documentation --csvSemicolon
```

Im Container muss das Verzeichnis für UID 1000 schreibbar sein (`chown -R 1000:1000 data`).

### 3. Umschalten

Es darf immer nur ein Server die SolvisRemote steuern, und ein laufender GUI-Zugriff darf nicht unterbrochen werden:

1. Am alten Server per MQTT `GUI_COMMANDS_DISABLE` auf `<topicPrefix>/<smartHomeId>/<unitId>/server/cmnd` senden
   (z. B. aus Home Assistant mit `mqtt.publish`) und warten, bis `<unitId>/gui_access` nicht mehr
   `CONTROL_WRITE_ONGOING` meldet.
2. Alten Dienst stoppen und deaktivieren (`systemctl disable --now SolvisSmartHomeServer`), danach noch einmal die
   aktuelle `measurements.xml` übernehmen (sie wird beim Beenden geschrieben und enthält Zählerstände).
3. solvis2mqtt starten (`docker compose up -d`). Im Datei-Log `SolvisServerData/solvis-tiny.log.0` sollten
   „Connection to MQTT successfull“, „Home Assistant MQTT discovery messages published“ und die ersten gelesenen
   Kanäle erscheinen; in Home Assistant aktualisieren sich die bestehenden Entities, weil `unique_id` und Topics
   gleich bleiben. Die GUI-Befehle sind nach dem Start automatisch wieder aktiv.
4. Die alte Installation einige Tage als Rückfallebene behalten, aber nie parallel starten.

Entfallen sind der TCP-Port, `--server-terminate` und `--server-restart` (Stoppen über SIGTERM bzw. den
Dienst-Manager), FHEM-Modul, Mail-Versand, Windows-Task und der ioBroker-Generator. ioBroker und andere Systeme
nutzen die MQTT-Topics direkt. Als Ersatz für die Störungs-Mail stehen `<unitId>/status` = `ERROR` und die
Störungskanäle zur Verfügung, die Benachrichtigung übernimmt das Smart-Home-System.

## Entwicklung

```sh
scripts/build-jar.sh                 # baut solvis2mqtt.jar (JDK 8 oder neuer, kein Ant/Maven)
docker build -t solvis2mqtt .        # oder das Image
```

Die Quellen sind Cp1252-kodiert. GitHub Actions baut bei jedem Push das Jar mit einem Offline-Test, hängt bei Tags
`v*` das Linux-Zip an das Release und veröffentlicht das Image. Änderungen stehen in
[docs/CHANGES.txt](docs/CHANGES.txt).

## Lizenz

[MIT](LICENSE), Copyright (c) 2020 GollmerSt, Weiterentwicklung lufi42. Solvis, SolvisMax, SolvisControl,
SolvisRemote und SolvisLino sind Marken der Solvis GmbH; dieses Projekt ist nicht mit ihr verbunden.
