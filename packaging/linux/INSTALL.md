# solvis2mqtt ohne Docker (JRE + systemd)

Der empfohlene Weg ist das Docker-Image (siehe README). Wer kein Docker hat, zum Beispiel in einem LXC
oder auf einem Raspberry Pi mit nacktem Java, installiert das Jar so:

```sh
# Java 8 oder neuer
sudo apt install default-jre-headless

# Benutzer und Verzeichnis
sudo useradd --system --home-dir /opt/solvis --shell /usr/sbin/nologin solvis
sudo mkdir -p /opt/solvis/solvis2mqtt
sudo cp solvis2mqtt.jar base.xsd /opt/solvis/solvis2mqtt/
sudo cp base.xml.new /opt/solvis/solvis2mqtt/base.xml      # danach anpassen, siehe unten
sudo chown -R solvis:solvis /opt/solvis
sudo chmod 600 /opt/solvis/solvis2mqtt/base.xml           # enthaelt Zugangsdaten

# Dienst
sudo cp solvis2mqtt.service /etc/systemd/system/
sudo systemctl daemon-reload
```

In `base.xml` mindestens setzen: `writablePathLinux="/opt/solvis"` (dort entsteht `SolvisServerData` mit gelernten
Bildern, Backups und Logs), URL und Zugangsdaten der SolvisRemote, Anlagentyp und Hauptheizung der Unit, MQTT-Broker.
Passwoerter stehen verschluesselt in der Datei:

```sh
java -jar /opt/solvis/solvis2mqtt/solvis2mqtt.jar --string-to-crypt=geheim
```

Vor dem ersten Start und nach jeder Aenderung der `control.xml` ist ein Learning noetig. Es laeuft einige
Minuten und braucht etwa 300 MB Heap:

```sh
sudo -u solvis java -Xmx384m -jar /opt/solvis/solvis2mqtt/solvis2mqtt.jar \
    --base-xml=/opt/solvis/solvis2mqtt/base.xml --server-learn
```

Danach:

```sh
sudo systemctl enable --now solvis2mqtt
journalctl -u solvis2mqtt -f
```

Beenden und Neustart laufen ueber systemd (`systemctl stop|restart solvis2mqtt`) oder ueber die
MQTT-Server-Befehle `TERMINATE` und `RESTART`; letzterer beendet den Prozess mit Exit-Code 24, den die
Unit per `Restart=on-failure` neu startet.

Update: Dienst stoppen, Jar tauschen, bei geaenderter `control.xml` Learning, Dienst starten.
