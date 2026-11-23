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

## Realdaten

Quelle ist das Amt für Geoinformation, Kanton Solothurn. Die Kacheln werden
für die lokale Abnahme aus dem kantonalen Downloadangebot bezogen. Sie und
daraus abgeleitete Punktdateien verbleiben im ignorierten Cache.

| Alias in `.cache/test-data` | Originaldatei | Bytes | Abruf |
| --- | --- | ---: | --- |
| `so-2019.laz` | [2594000_1227500.ch.so.agi.lidar_2019.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/2594000_1227500.ch.so.agi.lidar_2019.pointcloud.laz) | 83'247'722 | 23.11.2026 |

[Downloadangebot des Kantons](https://data.geo.so.ch/?filter=ch.so.agi.lidar_2019.pointcloud)
