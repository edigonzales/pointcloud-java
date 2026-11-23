# Etappenprotokoll

Dieses Protokoll hält Etappenabnahmen nach [Abschnitt 8.4 des Auftrags](../codex-auftrag-pointcloud-java.md#84-verbindliche-dokumentation-und-testmetriken-je-etappe)
fest. Der Etappencommit ordnet dem Ergebnis die geprüften Quellen zu. Pro
Etappe erfassen wir Datum, Plattform, Toolchain, Eingaben, ausgeführte Befehle,
Ergebnisse, Fehlerkorrekturen und die Einfachheitsprüfung. Ein wiederverwendeter
Lauf wird mit Herkunft und unveränderten relevanten Eingaben ausgewiesen.

| Prüfung | Nachweis |
| --- | --- |
| Gradle und JUnit | Aufgabe und Selektion, ausgeführt, erfolgreich, fehlgeschlagen, Fehler und übersprungen, Laufzeit, Exitstatus, nativer Reportpfad |
| CLI und Integration | Aufruf, Soll und Ist, Exitstatus, Laufzeit und relevante Dateigrössen |
| LAS, LAZ und PDAL | Gelesene, ausgewählte, geschriebene und vollständig verglichene Records, Abweichungen, geprüfte Felder und Metadaten, Referenzversion und Decoder, Toleranzen |
| Leistung und Speicher | Anzahl Läufe, Wandzeit und Streuung, Punkte/s, Batch, Worker, Heap und maximales RSS soweit messbar, Runtime, Hardware und Cache-Zustand |
| AOT, Native und GPU | Erzeugtes Artefakt bzw. Gerät, Build, Start, Grösse bzw. Transfer und fachlicher Vergleich |
| Dokumentation | Konkret gelesene Verträge und geprüfte Verweise, Grund für nicht anwendbare Laufzeitmetriken |

## B0 · Repo-Regeln und Skills

23. November 2026, Darwin arm64. Ausgangspunkt `5e84742` auf `main`,
Index leer, einzige unversionierte Eingabe der vollständige Auftrag. Auf
`codex/pointcloud-engine` bleibt der Auftrag am ursprünglichen Pfad und wird
unverändert mitversioniert.

Auftrag vollständig gelesen. Die verlinkten Q16- und Q17-Dateien am jeweils
genannten Commit mit `curl -fsSL` gelesen. Aus GRETL stammen die Orientierung
am tatsächlichen Verbraucher und die direkte Vereinfachungsprüfung. Aus
INTERLIS stammen die direkte JUnit-Selektion, native Berichte und die
Wiederverwendung gültiger Läufe. Die Regeln für Reviews, Veröffentlichung und
Referenzsuites richten sich nach dem PointCloud-Auftrag.

Manuelle Abnahme anhand von Abschnitt 1.3 und der B0-Abnahme. Alle drei
Skill-Frontmatter aus `name` und `description`, Ordnernamen, Auslöser und
Verweise geprüft. Etappenfolge, autonome Ausführung, externe Reviews und
Testregeln sind konsistent. KISS, Ockham und das Verbot von Tests von Tests
sind explizit. Die vier Regeldateien und dieses Protokoll reichen für den
Bootstrap aus. `git diff --cached --check` prüfte den gestagten Stand auf
Whitespace-Fehler. Laufzeitmetriken sind in B0 nicht anwendbar, da diese
Etappe ausschliesslich Dokumentation anlegt. B0 abgenommen.

## P0 · Umgebung und Fundament

23. November 2026. Apple M5 (Mac17,3), 10 CPU- und 10 GPU-Kerne,
24 GiB RAM, macOS 27.0.1 (26A434) arm64, 431 GiB frei. SDKMAN und die
üblichen macOS-JDK-Verzeichnisse inventarisiert. Für Launcher, Daemon,
Compiler, Testprozess und CLI wurde Temurin 25.0.5+8-LTS aarch64 verwendet
(`./gradlew --version`, `./gradlew -q javaToolchains`, Build mit `--info`,
explizites `JAVA_HOME`). Gradle 9.9.1 aus dem lokalen Wrapper-Cache für
die Generierung genutzt, Release und Java-25-Kompatibilität bei Gradle
geprüft. Wrapper-URL und offizieller SHA-256-Wert sind gepinnt. JUnit 6.0.3.

Für spätere Abnahmen vorhanden sind PDAL 2.10.2 (Release,
conda-forge `libpdal-core h151f718_4` im vorhandenen Environment `pdal`),
LAS-Reader/-Writer/Null-Writer und eingebaute LazPerf-Symbole
(`pdal --version`, `--drivers`, `--options readers.las`, `nm`).
Oracle GraalVM und `native-image` 25.0.4.1.1 und 25.0.3 sind als
aarch64-Binaries vorhanden. Apple clang 21.0.0 und Metal-4-fähige M5-GPU
vorhanden. `xcrun --find metal` meldet im aktiven Command-Line-Tools-SDK
kein `metal`-Werkzeug. Eine TornadoVM-Geräteausführung ist mit dieser
Inventur noch nicht belegt. Keine zusätzliche Installation vorgenommen.

| Prüfung | Ergebnis |
| --- | --- |
| `./gradlew clean check installDist --info` | Exit 0, 1.92 s Wandzeit. `MainTest`: 2 ausgeführt und erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen, Suite 0.032 s. |
| `./gradlew --offline clean check installDist` | Exit 0, 1.02 s Wandzeit, 2 frisch ausgeführt und erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen, Suite 0.020 s. |
| `build/install/pointcloud/bin/pointcloud --help` | Exit 0, 0.18 s, `Usage: pointcloud --help \| --version` auf stdout. |
| `build/install/pointcloud/bin/pointcloud --version` | Exit 0, 0.05 s, `pointcloud 0.1.0` auf stdout. |

Testreports unter `build/test-results/test/TEST-pointcloud.MainTest.xml`
und `build/reports/tests/test/index.html`. Die CLI-Aufrufe benötigen keine
Punktdaten, Launcher 8'706 B und Bibliotheks-JAR 1'171 B. Der negative
Test prüft Exit 2, leeres stdout und eine Fehlermeldung auf stderr.
CI-Konfiguration für den Standardbuild angelegt und lokal dieselben Befehle
geprüft. Es wurde kein Remote-CI-Lauf ausgelöst.

Einfachheitsprüfung mit `simplify-change`: ein Gradle-Standardprojekt mit
Application-Plugin, eine CLI-Klasse, direkte JUnit-Tests, keine eigene Gate-
oder Distributionslogik. Die gestagten Dateien mit
`git -c core.whitespace=cr-at-eol diff --cached --check` geprüft. Damit
bleibt die generierte Windows-Datei mit ihren originalen CRLF-Zeilenenden
erhalten. Kein fehlgeschlagener Pflichtlauf. P0 abgenommen.

## P1 · Unveränderten Codec mit echten Daten verstehen

23. November 2026, Temurin 25.0.5+8-LTS aarch64, Apple M5 und macOS wie P0.
PDAL 2.10.2 conda-forge arm64. Die [Reader-Implementierung](https://github.com/PDAL/PDAL/blob/2.10.2/io/LasReader.cpp)
dieser Version verwendet LazPerf für die Chunkdekodierung. Der
[Herkunftsnachweis](provenance.md) enthält Datenquelle und Lizenzbefunde.
Vor dem Download waren 430 GiB frei. Eine Kachel mit 83'247'722 B
(79.39 MiB), zwei LAS-Ausschnitte unter 36 KiB und ein 64-KiB-Fehlerfall
blieben unter beiden Datenlimits.

### Befunde

| Bereich | Prüfung und Randbedingungen |
| --- | --- |
| LAS-1.2-Header und LAZ-Format 3 | Dateiheader und VLRs geprüft. Version 1.2, 34 B, 14'566'627 Punkte, Punktdaten ab 615, XYZ-Scale 0.01, Offset 0. Kompressor 2, Coder 0, Chunkgrösse 50'000, Items (6,20,2), (7,8,2), (8,6,2). |
| Reader-Tiefe | Komfortiterator legt einen Wrapper pro Aufruf von `readNext` an und signalisiert auch bei `false` infolge eines Defekts Iteratorende. `getNumberOfPointRecords` allein liefert bei dieser 1.2-Datei 0. Low-Level-Reader liefert Roh-XYZ und führt `npoints`, `p_count` und eine Prüfung am Ende der Kodierung. `seek` castet auf `int`. |
| Struktur und Ressourcen | Low-Level-Header entfernt das LASzip-VLR und normalisiert Offset 615 auf 509 sowie VLR-Anzahl 4 auf 3. `ByteStreamInFile` schliesst das Dateihandle, die `MappedByteBuffer` bleiben bis zur Freigabe durch die JVM gemappt. Bei Trunkierung liefert der Reader `false` und schreibt Stacktrace und Chunkfehler auf stderr. |
| Weitere Reader | Im Quellcode ausgewählt werden für Legacy-Items Version 1/2 und für moderne Items Version 3, Version 4 über die Version-3-Implementierung. Der vollständige Realdatennachweis dieser Etappe deckt Format 3 mit Itemversion 2 ab. |
| LAS-Writer | Low-Level-Writer mit `LASZIP_COMPRESSOR_NONE`, separatem `LASinventory` und abschliessendem `update_header` erfolgreich. Daraus folgt der Nachweis für Format 3. |
| LAZ-Writer | POINT10 v2 und POINT14 v3 sind Dummies, `init` und `write` geben jeweils `false` zurück. Kein brauchbarer Encoder für die relevanten Varianten im untersuchten Port. |

### Reproduktion

```sh
./gradlew downloadTestData
mkdir -p .cache/laszip4j .cache/p1/classes build/reports/p1
curl -fLsS https://codeload.github.com/mreutegg/laszip4j/tar.gz/29a01a31607f1029f1330d1a09f19b17ea587005 |
  tar -xz --strip-components=1 -C .cache/laszip4j
find .cache/laszip4j/src/main/java -name '*.java' > .cache/p1/upstream-sources
"$JAVA_HOME/bin/javac" -d .cache/p1/classes @.cache/p1/upstream-sources
"$JAVA_HOME/bin/javac" -cp .cache/p1/classes -d .cache/p1/classes tools/CodecProbe.java
probe=com.github.mreutegg.laszip4j.laslib.CodecProbe
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -cp .cache/p1/classes "$probe" \
  low .cache/test-data/so-2019.laz .cache/p1/sample.las
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m \
  -XX:StartFlightRecording=filename=build/reports/p1/public.jfr,settings=profile,dumponexit=true \
  -cp .cache/p1/classes "$probe" public .cache/test-data/so-2019.laz
"$JAVA_HOME/bin/jfr" view allocation-by-site build/reports/p1/public.jfr
head -c 65536 .cache/test-data/so-2019.laz > .cache/p1/truncated.laz
"$JAVA_HOME/bin/java" -Xmx512m -cp .cache/p1/classes "$probe" low .cache/p1/truncated.laz
```

`pdal info --stats .cache/p1/sample.las` schrieb den nativen Statistikauszug
nach `build/reports/p1/sample-pdal.json`. `pdal pipeline .cache/p1/reference.json
--stream` verwendete diese kleine Referenzpipeline:

```json
{"pipeline":[{"type":"readers.las","filename":".cache/test-data/so-2019.laz","count":1024},{"type":"writers.las","filename":".cache/p1/reference.las","forward":"all","compression":false,"extra_dims":"all"}]}
```

Ab Headeroffset 509 im Java-LAS und 423 im PDAL-LAS wurden mit
`cmp <(tail -c +510 .cache/p1/sample.las) <(tail -c +424 .cache/p1/reference.las)`
alle Punktrecords überprüft. Die drei Projektions-VLR-Nutzdaten wurden
zusätzlich gegen die Quelldatei verglichen. PDAL interpretiert das CRS als
CH1903+ / LV95 und serialisiert seine Projektions-VLRs anders.

| Prüfung | Beobachtung |
| --- | --- |
| `./gradlew downloadTestData` | Download Exit 0, 1.81 s, 83'247'722 B. Offline-Aufruf mit Cache Exit 0, 0.33 s. |
| Unveränderter Low-Level-Scan mit Writer | Exit 0, 3.75 s, ca. 3.88 Mio. Punkte/s, max. RSS 375'652'352 B. Gelesen und Endzähler je 14'566'627, geschrieben 1'024 / 35'325 B. |
| Unveränderter Komfort-Scan mit JFR | Exit 0, 4.22 s, ca. 3.45 Mio. Punkte/s, RSS 400'982'016 B. Gelesen 14'566'627. Kein Zeitvergleich wegen Profiling und unterschiedlicher Arbeit. |
| PDAL liest LAS / PDAL-LAZ-Referenz | Exit 0 in 0.18 s bzw. 0.06 s. Je 1'024 Punkte, Referenz-LAS 35'239 B. 1'024 vollständig verglichene 34-B-Records, 0 Abweichungen. XYZ-Quantisierung, Grenzen und sämtliche Punktattribute exakt gleich. Drei Projektions-VLR-Nutzdaten stimmen zwischen Quelle und Java-Ausgabe überein. |
| Trunkierte LAZ | Erwarteter Exit 1, 0.06 s. Dekodierfehler bei 10'440 / 14'566'627, keine Ausgabe erzeugt. |

Beide Ganzkachelläufe liefern rohe XYZ-Summen
`3778944994702787,1788429997583373,1078422075172`. Je ein Kurzprofil auf
einem Prozess, keine Warm-up-Serie. JFR-Allokationsdruck in
`RGB12_v2.read` 50.67 %, `POINT10_v2.read` 46.04 % und Iterator-Wrapper
1.45 %. Die Allokation dieser temporären Objekte ist damit ein konkreter
Ansatz für spätere Messungen. Keine Produktperformance daraus abgeleitet.

Fehler im Versuchsaufruf behoben. Der direkte Writer braucht ein initialisiertes
`LASinventory`. Der Komfortlauf verwendet zusätzlich den Legacy-Zähler des
Headers. Beide betroffenen Läufe erneut vollständig und erfolgreich ausgeführt.
JUnit wurde in P1 nicht erneut ausgeführt. Die Produktquellen, Unit-Tests
und die Standardbuildkonfiguration aus P0 bleiben unverändert. Der neue
Download-Task wurde mit Download und Cacheverwendung direkt geprüft.
Einfachheitsprüfung: eine temporäre Probe mit zwei direkten Aufrufen zum
Upstream, Standardprogramme für Vergleich und Profiling, keine neue
Produktabstraktion. P1 abgenommen.

## P2 · Codecquellen übernehmen

23. November 2026, Apple M5/macOS und Temurin 25.0.5+8-LTS aarch64 wie P1.
In `4434396` die 53 für den direkten Punktdecoder nötigen Java-Dateien
per `cmp` gegen den unveränderten Checkout geprüft und mit Lizenztexten
importiert. Der anschliessende Adapter setzt am Punktdatenoffset an und
schreibt Decoder-Records vollständig in Little Endian. Der Originalport
erhält zwei kleine Korrekturen für das Entpacken des LASzip-VLR und die
Fehlerweitergabe. Herkunft, Umfang und Lizenztexte stehen im
[Herkunftsnachweis](provenance.md).

Der Encoder und die LAStools-Anwendung sind ausserhalb des Imports. Ein
separates, ersetzbares `codec.jar` entsteht aus dem lokalen Gradle-Projekt.
`./gradlew --offline dependencies --configuration runtimeClasspath`
zeigte nur `project :codec` (Exit 0, Gradle 468 ms). Die Dateianbindung
verwendet den Java-Dateistream des Ports ohne JNI und ohne Prozessaufruf.
`jar tf codec/build/libs/codec.jar` zeigte alle vier Lizenztexte und
keine `lastools`- oder `laslib`-Pakete.

```sh
./gradlew --offline clean check installDist --info
"$JAVA_HOME/bin/javac" -cp codec/build/libs/codec.jar -d .cache/p2/probe tools/DecodeTile.java
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -cp codec/build/libs/codec.jar:.cache/p2/probe \
  DecodeTile .cache/test-data/so-2019.laz .cache/p1/sample.las
```

| Prüfung | Ergebnis |
| --- | --- |
| Standardbuild aus lokalen Quellen | Exit 0, 1.56 s. `MainTest` 2 ausgeführt und erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen. JUnit-Suite 0.020 s, Report `build/test-results/test/TEST-pointcloud.MainTest.xml`, HTML unter `build/reports/tests/test/`. |
| Importregression gegen P1 | Exit 0, 4.78 s, 3.05 Mio. Punkte/s, max. RSS 372'129'792 B. Gesamte 83'247'722-B-Eingabe in 14'566'627 Records dekodiert. Rohe XYZ-Summen exakt wie P1. Alle 1'024 P1-Ausschnittrecords mit sämtlichen 34 B pro Record und in gleicher Reihenfolge verglichen, 0 Abweichungen. Keine zusätzliche Punktdatei erzeugt. |
| Trunkierung | Derselbe Befehl mit `.cache/p1/truncated.laz` meldete `corrupt chunk table` als IOException, Exit 1 in 0.07 s. Die Quelle wurde als fehlerhaft erkannt, bevor ein Record an den Aufrufer gelangte. |

Der erste Build zeigte beim Quellvergleich, dass die neue Prüfung des
Rückgabewerts von `init_dec` in einem Seek-Zweig statt im Lesepfad stand.
Prüfung an den benötigten Aufrufer verschoben und den Standardbuild sowie
beide Dekodierversuche mit der korrigierten Basis ausgeführt. Der
Unverändert-Versuch ist über `git show ba10229:tools/CodecProbe.java`
reproduzierbar. Er ist aus dem aktuellen Arbeitsbaum entfernt.
Einfachheitsprüfung: der Adapter nutzt den bestehenden Itemdecoder direkt.
Ordentliche Dateihülle, Batchverarbeitung und Ressourcenlebensdauer gehören
zur nächsten Etappe. Keine Pflichtprüfung aus P2 offen. P2 abgenommen.

## P3 · Verlustfreier Batchpfad

23. November 2026, Apple M5/macOS, Temurin 25.0.5+8-LTS aarch64, PDAL
2.10.2/LazPerf wie P1. Der [Dateivertrag](architecture.md) hält Layout,
Formatmatrix, Fehler und Metadatenregeln fest. Der Reader liefert
einen wiederverwendeten Bytepuffer vollständiger Records und trennt
Roh-XYZ und skalierte Double-Koordinaten. Das vermeidet Kopien in ein
zweites Punktmodell und bewahrt Flags, GPS-Zeit, RGB/NIR und Extra Bytes.
Der Writer kopiert Metadatenbereiche aus der Originaldatei und schliesst
den Header mit neu berechneten Zählern und Bounds ab. Die Codec-Änderungen
betreffen die Ressourcen- und Strukturgrenzen um den Entropiedecoder.

```sh
python3 tools/make-fixtures.py "$PDAL"
./gradlew --offline test --tests pointcloud.LasIoTest
./gradlew --offline clean check installDist --info
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -cp 'build/install/pointcloud/lib/*' \
  tools/DecodeTile.java .cache/test-data/so-2019.laz .cache/p1/sample.las .cache/p3/selected.las
"$PDAL" info --stats .cache/p3/selected.las
```

Die erste Fixtureprüfung lief mit 12 erfolgreichen und einer fehlerhaften
Ausführung (13 Tests, 0 Fehler, 0 Skips, Exit 1, Gradle 1 s). Ursache war
die von PDAL doppelt abgelegte Infrarotdimension im LAZ-8-Fixture.
Die Fixture-Erzeugung gibt nun die gewünschte Extra-Bytes-Dimension
explizit an. Die Assertions auf unveränderte Recordlänge und Rohbytes
blieben bestehen. Die korrigierte Selektion lief mit 13 Erfolgen und
0 Failures, Fehlern oder Skips (Exit 0, Gradle 753 ms).

| Prüfung | Ergebnis |
| --- | --- |
| Offline-Standardbuild | Exit 0, 1.29 s. `LasIoTest`: 13 erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen, 0.134 s. `MainTest`: 2 erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen, 0.001 s. JUnit-XML unter `build/test-results/test/`, HTML unter `build/reports/tests/test/`. |
| Fixture-Reader und -Writer | 14 LAS-/LAZ-Eingaben, 70 vollständige Records bytegenau an den ursprünglichen LAS-Punktbytes gemessen. 28 ausgewählte Ausgaberecords vollständig verglichen, 0 Abweichungen. Vorhandene VLR-/EVLR- und Extra-Bytes-Nutzdaten geprüft, zusätzlich leere und Einpunktausgabe, Return-Zähler und Bounds. |
| Defekte Eingaben | Trunkierung, ungültige Recordlänge und Header, Waveform, beschädigte Chunktabelle, übergrosse Layer sowie Abbruch und Zielalias direkt geprüft. Verworfenes Ergebnis und vorhandenes Ziel geprüft. |
| Ganzkachel und Ausgaberegression | Exit 0, 4.17 s, 3.49 Mio. Punkte/s, max. RSS 419'332'096 B. Eingabe 83'247'722 B, 14'566'627 gelesen, 1'024 ausgewählt und geschrieben, 35'325 B Ausgabe. Vollständiger Ausschnittvergleich gegen P1 mit 0 Recordabweichungen und gleichen Roh-XYZ-Summen über den Gesamtbestand. |
| PDAL liest P3-Ausgabe | `info --stats` Exit 0 in 0.13 s und 1'024 Punkte. Gleiche Projektions-VLR-Nutzdaten wie P1 und kein LASzip-VLR. |

Die Einfachheitsprüfung behält eine Byte-Repräsentation und eine
transaktionale Writer-Grenze. Die grossen Originalmetadaten werden direkt
übertragen, statt sie in eine Liste oder ein zweites Dateimodell zu laden.
Der Git-Diff und alle Pflichtprüfungen dieser Etappe sind geprüft. P3 abgenommen.

## P4 · PDAL-Pipeline-Teilmenge und CLI

23. November 2026, Plattform wie P3. Gradle 9.9.1 und Temurin 25.0.5+8-LTS
aarch64. Gson 2.13.2 liest den JSON-Baum. PDAL 2.10.2/LazPerf ist die
Referenz für ausführbare Beispiele, Dekimation und Statistik. Ein direkter
Stage-Switch erzeugt pro Lauf die Selektoren und Akkumulatoren. Die
Ausgabe folgt der Reihenfolge im Eingabestrom. `Pipeline` ist zugleich
die Bibliotheks-API. Die [Optionen und das JSON-Ergebnis](architecture.md#pipelines)
sind dokumentiert.

Die kleine Beispielabnahme verwendete `format-3.las` mit X/Y-Offsets
2'594'200 / 1'227'700, damit die fünf lokalen Punkte in der Beispielbox
liegen. In den Beispiel-JSONs wurden dafür nur Dateipfade ausgetauscht.
`pointcloud pipeline .cache/p4/<name>.json --metadata .cache/p4/java-<name>.json`
und `pdal pipeline .cache/p4/<name>.json --stream --metadata .cache/p4/pdal-<name>.json`
liefen auf derselben Konfiguration. Java-Punktdateien wurden vorher unter
eigenen Namen abgelegt. Recordlänge und Punktanzahl wurden aus dem LAS-Header
ausgelesen und alle Records vollständig verglichen.

| Prüfung | Ergebnis |
| --- | --- |
| `pdal pipeline examples/<name>.json --validate` | Alle drei Originaldateien `valid: true`, `streamable: true`, Exit 0. Ground-Crop 0.07 s, Stats 0.05 s, Decimate 0.05 s. |
| Ground-Crop auf Fixture | Je fünf gelesen, zwei ausgewählt und geschrieben, je 359 B LAS, 2 vollständige Records verglichen mit 0 Abweichungen. Java/PDAL Exit 0, 0.33/0.16 s. |
| Stats auf Fixture | Je fünf gelesen und konsumiert, keine LAS-Ausgabe. Fünf Dimensionen verglichen. Java/PDAL Exit 0, 0.15/0.05 s. |
| Decimate auf Fixture | Je fünf gelesen und einer ausgewählt und geschrieben, je 325 B LAS. 1 vollständiger Record verglichen, 0 Abweichungen. Java/PDAL Exit 0, 0.14/0.05 s. |
| `pointcloud info src/test/resources/las/format-8.laz` | Exit 0, 0.10 s. Format 8 / LAS 1.4 / 5 Headerpunkte / 40 B Record, zwei Extra Bytes. Header-Bounds und alle vier VLRs angezeigt. |
| `./gradlew --offline clean check installDist --info` | Exit 0, 3.14 s. `PipelineTest`: 6 erfolgreich (0.135 s), `LasIoTest`: 13 (0.261 s), `MainTest`: 2 (0.002 s). Insgesamt 21 ausgeführt, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen. XML unter `build/test-results/test/`, HTML unter `build/reports/tests/test/`. |

Alle endlichen Stats-Werte verglichen mit `max(1e-10, 5.1e-10 * max(|Java|,|PDAL|))`
wegen der ungefähr zehn signifikanten Stellen im PDAL-JSON. Anzahl und
Extrema exakt. Grösste absolute Abweichung beim Mittelwert 2.33e-10,
bei der Varianz 1.20e-7 und bei der Standardabweichung 4.66e-6.

Vier zusätzliche Mini-Pipelines mit `writers.null` und demselben
Befehlspaar prüften leere Auswahl (`Intensity < 0`), einen Punkt
(`Intensity == 20`) und je einen NaN- bzw. +Inf-GPS-Wert im ersten von
fünf Records. Beide CLIs Exit 0. Wandzeit Java/PDAL in dieser Reihenfolge
0.14/0.04 s, 0.12/0.04 s, 0.11/0.04 s, 0.10/0.04 s.
PDAL 2.10.2 meldet bei 0 Punkten DBL_MAX-/−DBL_MAX-Sentinels und Nullwerte
für die Momente. Java schreibt bei 0 Punkten die fünf Werte als JSON-null.
Bei einem Punkt stimmen die Lagewerte, beide behandeln Stichprobenstreuung
als undefiniert. Bei NaN und +Inf stimmen Stichprobenanzahl und endliche
Extrema. Java kodiert undefinierte oder nicht endliche Resultate als null,
PDAL kodiert NaN und Infinity als Strings und lässt undefinierte Streuung weg.

Die erste Testselektion erkannte eine falsch handgerechnete
Chunkgrenzenerwartung (5 gelaufen, 4 erfolgreich, 1 Failure, 0 Fehler,
0 Skips, Exit 1, Gradle 1 s). Bei Schritt 24'999 und Offset 1 nach dem
Vorschaltfilter gehören die ursprünglichen Records 2, 25'001 und 50'000
zum Ergebnis. Das korrigierte Testorakel prüft auch, dass ein exklusives
Limit 49'999 den letzten davon ausschliesst. Die breitere Wiederholung
bestand mit Batchgrössen 7, 13 und 32'768 auf der Zwei-Chunk-Fixture.
Die zweite fachliche Ausführung derselben Pipeline hat keinen Altzustand.

Einfachheitsprüfung mit den Repository-Skills. Feste Stage-Menge,
ein JSON-Baum, ein kleiner Ausdrucksparser und ein Selektor pro Stage.
Der Standardbuild bleibt unabhängig von PDAL und Realdaten. P4 abgenommen.
