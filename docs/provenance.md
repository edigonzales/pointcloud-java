# Herkunft von Codec und Daten

## Codec-Ausgangsstand

Die Ausgangsmessung verwendet unveränderte Quellen von
[laszip4j an Revision 29a01a3](https://github.com/mreutegg/laszip4j/tree/29a01a31607f1029f1330d1a09f19b17ea587005).
Der Java-Port basiert auf LASzip und Teilen von LAStools. Die Lizenzbefunde
gelten für einzelne Dateien und Originalheader.

| Stelle | Befund |
| --- | --- |
| `LICENSE`, `pom.xml` | LGPL 2.1 als Root-Lizenz bzw. Projektangabe |
| `LASReader.java`, `ByteStreamInFile.java` | LGPL 3 oder neuer im jeweiligen Header |
| `LASwriter.java`, weitere frühe Portierungen | LGPL ohne Versionsnummer im Header, Verweis auf die Lizenzdatei |
| `LASreadItemCompressed_POINT14_v3.java`, `LASwriteItemCompressed_POINT10_v2.java` | Apache 2.0 im jeweiligen Header |

## Übernommene Quellen

Unter `codec/src/main/java` liegt die transitive Quellmenge des
Punktdecoders `LASreadPoint`, seines Dateistreams und der Punktrecord-Typen.
Der Import besteht aus 53 Java-Dateien in den originalen Packages
`com.github.mreutegg.laszip4j.laszip` und `com.github.mreutegg.laszip4j.clib`.
Paketnamen, Copyright- und Lizenzheader stammen aus dem genannten Commit.
Die `lastools`-Anwendung, `laslib`-Factory und komprimierten Writer sind
ausserhalb des importierten Umfangs.

Die Original-Root-Lizenz liegt in `codec/licenses/UPSTREAM-LGPL-2.1.txt`.
Daneben liegen die Texte für LGPL 3.0, das von ihr referenzierte GPL 3.0
und Apache 2.0. Die Zuordnung ergibt sich aus den jeweiligen Headern.

Lokale Änderungen an `LASzip.java` validieren den LASzip-VLR beim
Entpacken und initialisieren seine Items. `LASreadPoint.java` reicht Fehler
beim Chunkstart über seinen Rückgabewert und Diagnose weiter. `LazDecoder`
ist neu und übergibt vollständige Records als Bytes. Der Standardbuild
verpackt den Codec separat in `codec.jar` mit Lizenztexten unter
`META-INF/licenses/laszip4j/`.

Der Dateistream wurde auf `MemorySegment` mit schliessbarer Arena
umgestellt. Die lokale Chunkprüfung lehnt ungültige Tabellen und
überlange Layer vor der Allokation ab. Beim LASzip-Abwesenheitsmarker
prüft sie beim letzten Punkt die exakte Position am Ende des
Punktdatenbereichs. Die jeweiligen geänderten
Codecquellen tragen einen Änderungshinweis.

In `POINT10_v2`, `RGB12_v2`, `GPSTIME11_v2` und `LASreadPoint`
werden die kurzlebigen Ausgaberecords bzw. der Lesekontext lokal
wiederverwendet. Die Vorhersage- und Entropiemodelle bleiben identisch.

Der Chunkversuch nutzt aus dem Codec die validierten Offset- und
Punktbereiche und startet an der Seed-Record-Grenze. Der nicht
verwendete 32-Bit-Punktseek des Ports wurde dabei entfernt.
Im E4-Nachversuch teilen die wiederverwendeten Decoder die einmal
validierte Chunktabelle derselben geöffneten Datei. Mapping, Cursor
und Decodiermodelle gehören jeweils einem Worker.

`ArithmeticModel` verwendet für die Dekodiermodelle mit 33, 64, 128
oder 256 Symbolen zwei zusätzliche Indexbits in der Suchtabelle. Die
kumulativen Häufigkeiten und das LAZ-Format bleiben gleich.
[Versuchsbericht](experiments.md#ground-crop-bestätigung-für-2)

Das Native Binary ist ein lokales Versuchsartefakt. Eine spätere
Verteilung muss die jeweiligen Codec-Hinweise und Lizenztexte,
passende Quelltexte und die für den eingebetteten LGPL-Code
erforderliche Austausch- bzw. Relink-Möglichkeit abdecken
(LGPL 2.1 §6 bzw. LGPL 3.0 §4). Für die mit Oracle GraalVM
eingebetteten Komponenten gelten zusätzlich dessen Free Terms
and Conditions und die Hinweise zu separat lizenzierten Teilen.
Die JVM-Distribution hält den Codec in einem separaten JAR.
[Oracle GraalVM-Lizenz](https://www.oracle.com/downloads/licenses/graal-free-license.html)

## Polygongeometrie

Der Polygonfilter verwendet das unveränderte Maven-Artefakt
`org.locationtech.jts:jts-core:1.20.0`. Die Distribution enthält
die Eclipse Distribution License 1.0 und die Lizenzhinweise von JTS
unter `licenses/jts/`.
[JTS 1.20.0](https://central.sonatype.com/artifact/org.locationtech.jts/jts-core/1.20.0)

## Dreiecksnetz

`writers.dtm` verwendet für den Tinfour-Pfad `org.tinfour:TinfourCore:2.1.9`
mit Apache-2.0-Lizenz.
Das Artefakt enthält die von Tinfour angepasste Double-Double-Arithmetik
aus JTS unter EDL 1.0. Die mitgelieferten Lizenztexte und Hinweise liegen
unter `licenses/tinfour/`. [Tinfour 2.1.9](https://github.com/gwlucastrig/Tinfour/tree/2.1.9)

Der Delaunator-Pfad adaptiert den radialen Aufbau aus Mapbox Delaunator
5.0.1. Die Java-Fassung arbeitet mit ganzzahligen LAS-XY-Koordinaten
und verwendet eigene exakte geometrische Prädikate.
Die Distribution enthält den Lizenztext unter `licenses/delaunator/`.
[Delaunator 5.0.1 und ISC-Lizenz](https://github.com/mapbox/delaunator/tree/v5.0.1)

## Realdaten

Die Solothurner Kacheln stammen vom Amt für Geoinformation des Kantons.
Autzen ist eine veröffentlichte COPC-Beispieldatei von Hobu aus dem
PDAL-Datenbestand. Die Dateien für die lokale Abnahme liegen im ignorierten Cache.

| Alias in `.cache/test-data` | Originaldatei | Bytes | Abruf |
| --- | --- | ---: | --- |
| `so-2019.laz` | [2594000_1227500.ch.so.agi.lidar_2019.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/2594000_1227500.ch.so.agi.lidar_2019.pointcloud.laz) | 83'247'722 | 23.11.2026 |
| `so-2019-urban.laz` | [2606500_1229000.ch.so.agi.lidar_2019.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/2606500_1229000.ch.so.agi.lidar_2019.pointcloud.laz) | 52'116'409 | 26.11.2026 |
| `so-2019-height.laz` | [2592000_1228000.ch.so.agi.lidar_2019.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/2592000_1228000.ch.so.agi.lidar_2019.pointcloud.laz) | 79'958'069 | 26.11.2026 |
| `so-2019-riedholz.laz` | [2610000_1232500.ch.so.agi.lidar_2019.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/2610000_1232500.ch.so.agi.lidar_2019.pointcloud.laz) | 30'303'122 | 26.11.2026 |
| `so-2014.laz` | [2592000_1228000.ch.so.agi.lidar_2014.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2014.pointcloud/aktuell/2592000_1228000.ch.so.agi.lidar_2014.pointcloud.laz) | 74'261'841 | 23.11.2026 |
| `autzen-classified.copc.laz` | [Autzen Stadium](https://s3.amazonaws.com/hobu-lidar/autzen-classified.copc.laz) | 81'123'042 | 24.11.2026 |

[Downloadangebot des Kantons](https://data.geo.so.ch/?filter=ch.so.agi.lidar_2019.pointcloud)
· [COPC-Beispieldaten](https://copc.io/#example-data)
· [Lizenz der PDAL-Beispieldaten (CC BY 4.0)](https://github.com/PDAL/data/blob/main/LICENSE)

Die HAG-Abnahme verwendet das Terrainmodell der kantonalen Befliegung
2019. `tools/check-hag-dem.py` liest nur die benötigten Ausschnitte
per HTTP-Range über GDAL und mittelt das 25-cm-Raster auf 1 m.
Die unkomprimierten Float64-GeoTIFFs enthalten je 252'004 gültige
Zellen inklusive 1 m Rand, mit je 2'017'904 Bytes bei GDAL 3.13.3.
[Amtliches Solothurn-DTM](https://files.geo.so.ch/ch.so.agi.lidar_2019.dtm/aktuell/ch.so.agi.lidar_2019.dtm.tif)

| Ausschnitt | SHA-256 des lokalen GeoTIFFs |
| --- | --- |
| Stadt | `036ab83686e8fdcb4b9adc9bebab781f0d2054709f5357cb26e89d37453b7977` |
| Wald | `2a07ab615b5cfdb448b5efdba78bdba094072fe0ad4c13ec3e8950f0bcfdc747` |

Für die Höhen-Schwellenanteile in Riedholz wird aus derselben DTM-Quelle
ein 202 × 202 Zellen grosses Float64-Fenster mit einem Meter Rand erzeugt.
Die 327'044 Bytes ergeben SHA-256
`4bead5a305c00a81c088e62a7e63b9af2b0627288ba1271242a41654f5a1597c`.
Die LAZ enthält 7'327'607 Punkte und hat SHA-256
`fc2c5adde539277e33ecca04e0b310099c33acc41c3aed85ab1b8a7921569f4b`.

## Kleine Fixtures

`src/test/resources/las` enthält eigene synthetische LAS-Bestände mit
je fünf Punkten und die mit PDAL 2.10.2/LazPerf daraus erzeugten LAZ-Dateien.
Die Fixtures verwenden keine kantonalen Punkte oder Upstream-Probedateien.
Erzeugung ab Repositorywurzel mit `python3 tools/make-fixtures.py /pfad/zu/pdal`.
Für Format 8 wird `Confidence=uint16` beim Erzeugen angegeben. PDAL
2.10.2 würde mit `extra_dims=all` auch `Infrared` als zweite Dimension
hinter den formatüblichen NIR-Wert schreiben.

`chunks.laz` wurde mit demselben Werkzeug erzeugt. Es enthält 50'003
synthetische Punkte in zwei Chunks. Die rohe X-Koordinate gibt die
ursprüngliche Position im Strom an.
