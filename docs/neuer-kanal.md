# Neuen Kanal anlegen (control.xml)

Kurzanleitung am Beispiel des Kanals `C50.Ausgang_O1_Typ` (Typ des Analogausgangs O-1, Brennersperre, Issue #18).
Alle Kanäle, die der Server über die Bedienoberfläche der SolvisControl liest oder schreibt, sind in
`rsc/de/sgollmer/solvismax/data/control.xml` beschrieben; das Schema steht in `control.xsd` daneben, die
Dokumentation der Elemente in den `annotation`-Blöcken dort.

## 1. Screens aufnehmen

Den Weg vom Home-Screen bis zum Screen mit dem Wert mit Bildschirmfotos der SolvisRemote festhalten
(`display.bmp`, 240 × 128). Das Skript `remote_gui.py` im Repo solvis-stack (`docs/sc2-screens`) macht Fotos und
Berührungen; vorher die GUI-Befehle des Servers mit `GUI_COMMANDS_DISABLE` anhalten und danach mit
`GUI_COMMANDS_ENABLE` freigeben. Koordinaten sind Display-Koordinaten wie in der `control.xml`.

Pro Screen notieren: Berührungspunkt auf dem Vorgänger-Screen, Zurück-Ziel der Hardware-Taste, ein Rechteck mit
unverwechselbarem Inhalt (meist der Titel; bei gleichen Titeln wie den zwei Seiten „AUSGÄNGE“ ein Menüpunkt, der
nur auf einer Seite steht).

## 2. Screens definieren

Jeder Screen ist ein `Screen`-Element unter `Screens`:

```xml
<tns:Screen id="Analoge-Ausgaenge" backId="Ausgaenge-2" previousId="Ausgaenge-2" service="true">
    <tns:Configuration admin="ADMIN" />          <!-- nur mit Feature Admin, Installateur-Menue -->
    <tns:TouchPoint pushTimeRefId="Standard" releaseTimeRefId="WindowChange">
        <tns:Coordinate X="220" Y="63" />        <!-- Beruehrung auf dem Vorgaenger (previousId) -->
    </tns:TouchPoint>
    <tns:Identification>
        <tns:Grafic id="Analoge-Ausgaenge">      <!-- Grafik wird beim Learning aufgenommen -->
            <tns:Rectangle>
                <tns:TopLeft X="26" Y="2" />
                <tns:BottomRight X="215" Y="14" />
            </tns:Rectangle>
        </tns:Grafic>
    </tns:Identification>
</tns:Screen>
```

* `previousId`: Screen, von dem aus der Berührungspunkt gedrückt wird. `backId`: Screen nach der Zurück-Taste
  (bei Seite 2 eines Menüs meist das übergeordnete Menü, siehe `Wasser-Anforderung-2/2`).
* `Identification`: ein oder mehrere Grafiken (`Grafic`, beim Learning gelernt), OCR (`Ocr`, nur Ziffern und
  wenige Zeichen) oder weiße Bereiche (`MustBeWhite`). Grafik-Ids müssen eindeutig sein.
* `Configuration`: Konfigurationsmaske (`ConfigurationMask`, Bits siehe Kopf der `control.xml`), Feature
  (`feature="..."`) oder `admin="ADMIN"` für Screens unterhalb des Installateur-Menüs. Kanäle auf solchen Screens
  erkennt der Server automatisch als Installateur-Einstellungen.
* `service="true"` kennzeichnet Service-Screens (Erkennung von Service-Zugriffen).

## 3. Kanal definieren

Ein `ChannelDescription`-Element unter `ChannelDescriptions`. Die `id` folgt dem Schema (`C` = Einstellung über die
GUI, `S` = Fühler, `A` = Ausgang, `X` = berechnet, `Z` = Statusbyte), fortlaufend nummeriert. Die wichtigsten Typen:

Typ | Verwendung | Elemente
--- | --- | ---
`TypeReadWrite` | Zahlenwert mit +/− (Temperaturen, Differenzen) | `GuiModification` mit `format` (regulärer Ausdruck auf das OCR-Ergebnis), `Upper`/`Lower` Berührungspunkte, `least`/`most`/`increment`, optional `divisor`
`TypeRead` | nur lesbarer Zahlenwert | `GuiRead` mit `format`
`TypeMode` | Auswahl aus Texten (Betriebsart, Auto/Hand) | je `ModeEntry` ein Berührungspunkt und eine `ScreenGrafic`, die beim Learning gelernt wird
`TypeButton` | Zustand eines Buttons (selektiert/nicht) | –

`GuiAccess` nennt den Screen und das Rechteck mit dem aktuellen Wert (`CurrentValue`); `UpdateBy` legt fest, wann
der Kanal neu gelesen wird (`HumanAccess`: nach einem Eingriff an der Anlage).

Beispiel `C50` (gekürzt):

```xml
<tns:ChannelDescription id="C50">
    <tns:Control>
        <tns:GuiAccess screenId="O1-Modulation">
            <tns:CurrentValue>
                <tns:TopLeft X="155" Y="19" />
                <tns:BottomRight X="190" Y="31" />
            </tns:CurrentValue>
        </tns:GuiAccess>
        <tns:TypeMode>
            <tns:ModeEntry id="Hand">
                <tns:GuiSet>
                    <tns:Touch pushTimeRefId="Standard" releaseTimeRefId="ModeChange">
                        <tns:Coordinate X="205" Y="25" />
                    </tns:Touch>
                    <tns:ScreenGrafic id="O1TypHand" exact="true" />
                </tns:GuiSet>
                <tns:MustBeWhite> ... </tns:MustBeWhite>
            </tns:ModeEntry>
            <tns:ModeEntry id="Auto"> ... </tns:ModeEntry>
        </tns:TypeMode>
        <tns:UpdateBy>
            <tns:HumanAccess />
        </tns:UpdateBy>
    </tns:Control>
</tns:ChannelDescription>
```

**Learning eines `TypeMode`-Kanals:** Ohne `MustBeWhite` drückt der Server beim Learning nacheinander den
Berührungspunkt jedes Eintrags und lernt die Grafik, die danach im `CurrentValue`-Rechteck steht; der letzte Eintrag
bleibt eingestellt. Mit `MustBeWhite`-Rechtecken (pro Eintrag Bereiche, die nur bei diesem Eintrag weiß sind)
erkennt der Server nach jeder Berührung, welcher Eintrag angezeigt wird. Weiß heißt dabei wirklich weiß: Die
Schwelle liegt in der Mitte zwischen dem dunkelsten und dem hellsten Pixel des Screens, der graue Hintergrund der
Buttons (RGB 119) liegt darunter. Bereiche auf einem Button taugen also nicht; geeignet sind Stellen, an denen bei
einem Eintrag Text steht und beim anderen nicht (bei `C50` die zweite Zeile, „Aktuelle Ansteuerung“ gegenüber
„Vorgabe Hand“). Mit `invertFunction="true"` muss der Bereich dunkle Pixel enthalten, so lässt sich derselbe
Bereich für beide Einträge nutzen. Die Rechtecke lassen sich offline gegen Bildschirmfotos prüfen, siehe
`O1ModeDetectionTest` mit den Fotos in `test/resources`. Das ist nötig, wenn die Berührungspunkte
nicht direkt einen Eintrag wählen, sondern weiterschalten (Pfeile ◄ ►) – die Reihenfolge der Einträge bestimmt dann
die Reihenfolge der Berührungen, der letzte Eintrag ist der Endzustand (`Auto` bei `C50`).

Die Pixelpositionen für `MustBeWhite` ermittelt man aus den Bildschirmfotos (z. B. mit einem kurzen Python-Skript,
das ein Rechteck als ASCII ausgibt).

## 4. Zuordnung, Namen, Gruppen

* `ChannelAssignments`: `<tns:Assignment id="C50" name="C50.Ausgang_O1_Typ" />` – der Name erscheint in den
  MQTT-Topics (`C50:Ausgang_O1_Typ`). Konfigurationsabhängige Namen bekommen eine `Configuration`.
* `ha-labels.properties`: Anzeigename in Home Assistant, optional `|config` / `|diagnostic` / `|disabled`.
* `ha-groups.properties`: Gerät in Home Assistant (Installateur-Einstellungen landen automatisch im Gerät
  „Installateur“).
* `ha-values.properties`: Texte für Rohcodes (nur Zahlenkanäle).

## 5. Prüfen

1. `scripts/test.sh` – die Tests laden die `control.xml` offline gegen die mitgelieferte `base.xml`
   (XML-Verifikation, Konfiguration, Installateur-Erkennung).
2. `java -jar solvis2mqtt.jar --documentation --csvSemicolon` erzeugt die Kanalliste der eigenen Konfiguration.
3. Learning an der Anlage: nach einer Änderung der `control.xml` lernt der Server fehlende Grafiken beim nächsten
   Start selbst (nur die neuen Screens, Dauer einige Minuten). Dabei darf niemand die Oberfläche parallel bedienen.
   Die gelernten Bilder liegen in `SolvisServerData/LearnedImages`, das Ergebnis in `graficData.xml`.
4. Eintrag in `docs/CHANGES.txt` mit dem Hinweis „erfordert ein neues Learning“.
