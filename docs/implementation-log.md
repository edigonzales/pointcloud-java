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
git show ba10229:tools/CodecProbe.java > .cache/p1/CodecProbe.java
curl -fLsS https://codeload.github.com/mreutegg/laszip4j/tar.gz/29a01a31607f1029f1330d1a09f19b17ea587005 |
  tar -xz --strip-components=1 -C .cache/laszip4j
find .cache/laszip4j/src/main/java -name '*.java' > .cache/p1/upstream-sources
"$JAVA_HOME/bin/javac" -d .cache/p1/classes @.cache/p1/upstream-sources
"$JAVA_HOME/bin/javac" -cp .cache/p1/classes -d .cache/p1/classes .cache/p1/CodecProbe.java
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

## P5 · Realdaten- und PDAL-Abnahme

23. November 2026. Apple M5/macOS wie P0, Temurin 25.0.5+8-LTS aarch64,
Gradle 9.9.1 und PDAL 2.10.2 (conda-forge arm64, eigener LazPerf-Decoder).
Die zweite Kachel ist LAS 1.2 / Format 1 mit 28-B-Records, 14'347'366
Punkten, Scale 0.01 und Offset −0.0. Ihr einziges VLR ist das LASzip-VLR.
Das Programm übernimmt weder ein angenommenes CRS noch eine Reprojektion.

```sh
./gradlew --offline clean check installDist
./gradlew downloadTestData
./gradlew downloadTestData -Ptile=2014
python3 tools/check-real-data.py "$PDAL"
JAVA_OPTS=-Xmx512m /usr/bin/time -lp build/install/pointcloud/bin/pointcloud \
  pipeline examples/ground-crop.json --metadata .cache/p5/heap-ground.json
```

Download 2014 Exit 0, 2.70 s, 74'261'841 B. Vorher 418 GiB frei.
Zusammen 157'509'563 B für zwei Kacheln (150.21 MiB). Selbst ein
ungefiltertes Paar LAS-Ausgaben der 2019-Kachel hätte unter der
1.5-GiB-Datengrenze Platz. Die tatsächlich grösste Ausgabe war 6'655'601 B.
Die Vergleichssequenz bewegt jede Java-Ausgabe aus dem Originalziel und
verwendet die gleiche Konfiguration für PDAL. Sie vergleicht die Records
in Blöcken von höchstens 8'192 und entfernt die erzeugten LAS-Paare.
Zusätzliche erste Ergebnisdateien aus der Diagnose wurden bereinigt.

| Pipeline | Gelesen | Ausgewählt | Geschrieben / komplett verglichen | Java / PDAL LAS-Bytes | Wandzeit Java / PDAL |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ground-Crop, 2019 | 14'566'627 | 195'738 | 195'738 | 6'655'601 / 6'655'515 | 4.10 / 1.85 s |
| Stats, 2019 | 14'566'627 | 14'566'627 | 0 | 0 / 0 | 3.87 / 2.14 s |
| Decimate, 2019 | 14'566'627 | 128'909 | 128'909 | 4'383'415 / 4'383'329 | 3.63 / 1.69 s |
| Stats und Decimate (500), 2014 | 14'347'366 | 28'695 | 28'695 | 803'689 / 803'687 | 4.26 / 2.24 s |

Gesamte Vergleichssequenz Exit 0, 24.45 s. Alle acht Pipelineläufe Exit 0.
353'342 verglichene Records, 0 Abweichungen. Reihenfolge, rohe XYZ,
Flags, Return- und Klassenwerte, GPS-Zeit, Intensität und RGB soweit
vorhanden sind bytegenau gleich. Format, Recordlänge, Quantisierung,
Header-Bounds und Return-Zähler stimmen überein. PDAL las jede
Java-Ausgabe mit `info --stats` vollständig und unabhängig (Exit 0,
0.10 / 0.09 / 0.05 s). PDAL interpretiert beide 2019-Ausgabepaare mit
demselben CRS CH1903+ / LV95. Die 2014-Dateien enthalten kein CRS.
Die übertragenen VLR-Nutzdaten stimmen bei allen Java-Ausgaben mit
der jeweiligen Quelle überein. PDAL darf seine VLRs anders serialisieren.

Die Toleranz für Mittelwert, Stichprobenvarianz und
Stichprobenstandardabweichung folgt P4 (`max(1e-10, 5.1e-10 * max(|Java|,|PDAL|))`).
Alle Statistikzählungen und Extrema gleich. Maximale absolute
Abweichungen (Mittelwert / Varianz / Standardabweichung):
Ground-Crop 4.40e-4 / 3.29e-3 / 3.49e-9, Stats 2019
4.43e-4 / 2.78e-4 / 2.25e-7, Stats 2014 2.27e-5 / 1.54e-6 / 1.58e-8.

Die erste 2014-Abnahme deckte einen Fehler in der Klassensemantik auf.
Die Klasse 12 floss als 12 statt 0 in die Statistik ein. Die Punkte der
Dezimierung waren schon bytegleich. Nach Anpassung der Pipeline-Dimension
an PDAL bleibt Klasse 12 als Rohwert erhalten, wird aber für Filter und
Statistik wie bei PDAL als 0 ausgewertet. Ein Regressionstest verwendet
Klasse 12 samt gesetzten Flags, prüft die fachliche Auswahl und das
unveränderte Ausgabebyte. Die korrigierten vier Pipelinevergleiche wurden
vollständig wiederholt. Zusätzliche Fixture-Prüfungen sichern nicht
beschriebene Extra Bytes und die Behandlung grosser Zähler und Offsets.

Speichernachweise mit `-Xmx512m`. Der separate P5-Ground-Crop-CLI-Lauf
schrieb 195'738 Punkte, Exit 0, 4.62 s, max. RSS 396'754'944 B.
Eine Java-Quellanwendung mit `Pipeline.read(Path.of("examples/stats.json"))`
führte dreimal `run(32_768, false)` im selben Prozess aus und verglich die
drei `Result`-Werte auf Gleichheit. Exit 0, 43'699'881 gelesene Punkte,
12.03 s, max. RSS 426'655'744 B, keine Punktdateien geschrieben.
Die Dateizuordnung umfasst bei 2019 83'247'722 B virtueller Adressfläche.
Je Lauf ist ein Batch mit 32'768 × 34 B (1'114'112 B) offen. Peak-RSS,
Heapgrenze und Mappingumfang beschreiben verschiedene Grössen.

Finaler Offline-Standardbuild Exit 0 in 1.47 s. `LasIoTest` 14
erfolgreich (0.114 s), `PipelineTest` 7 (0.081 s), `MainTest` 2 (0.001 s).
23 ausgeführt und erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen.
JUnit-XML unter `build/test-results/test/`, HTML unter `build/reports/tests/test/`.
Einfachheitsprüfung: ein expliziter PDAL-Vergleich für den realen
Dateivertrag, der temporäre Importversuch bleibt über `git show
08833a0:tools/DecodeTile.java` reproduzierbar. Alle Produktpflichtläufe
dieser Etappe erfolgreich. P5 abgenommen.

## P6 · Skalare Baseline und Allokationen

23. November 2026. Apple M5/macOS, Temurin 25.0.5+8-LTS aarch64,
Gradle 9.9.1, JMH 1.37, PDAL 2.10.2/LazPerf. E1 wurde als
[Vier-Ebenen-Messung](experiments.md#e1--baseline-und-batchlayout) mit
einzelnen CSV-Läufen, JMH-Iterationen und gezielten JFR-Profilen ausgeführt.
Die kontrollierte Auswahl zeigt, dass die Kosten der zusätzlichen
Spalten den Vorteil ihres einmaligen Filterkernels übersteigen. Der
Durchsatz des gesamten Pfades wird hauptsächlich von der Dekodierung
bestimmt. 32'768 Punkte und vollständige Records bleiben die Vorgabe.

Bei der Profilkommandovariante wurden zunächst JFR-Startmeldungen auf
stdout geschrieben. Die LAS-Dateien und die JSON-Daten nach den
Startmeldungen waren exakt gleich. Der dokumentierte und im letzten
Profil ausgeführte Aufruf trennt dies mit `-Xlog:jfr+startup=off`.
Eine zunächst als 2 notierte PDAL-Workerzahl wurde anhand des
Referenzquellcodes in der CSV auf den verwendeten Standard 7 korrigiert.

Median Ground-Crop vor/nach Wiederverwendung von Record und Lesekontext
3.79/3.58 s und max. RSS im Median 398'508'032/159'203'328 B.
Je 3 Läufe mit vollen 14'566'627 Eingabepunkten. Alle vollständigen
Ausgaben und ihre Statistiken stimmen exakt mit der unveränderten
Ausgangsbasis für die Pipeline überein. Vergleichsdateien jeweils
pauschal identische Java-Serialisierung, der unabhängige Vergleich
erfolgt zusätzlich auf Record- und Metadatenebene.

`./gradlew --offline clean check installDist` für die finale Variante
Exit 0 in 1.01 s. `LasIoTest` 14 erfolgreich (0.078 s),
`PipelineTest` 7 (0.053 s), `MainTest` 2 (0.001 s).
23 ausgeführt und erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen.
JUnit-Berichte unter `build/test-results/test/` und `build/reports/tests/test/`.
`python3 tools/check-real-data.py "$PDAL"` bestätigte danach in 23.01 s
mit Exit 0 alle 353'342 Records, Header, CRS, Zähler und Statistikgrenzen
aus P5. Die Zwischenvariante mit nur Record-Wiederverwendung bestand
dieselbe Vergleichssequenz (24.19 s, Exit 0).

Einfachheitsprüfung: kurzer, lokaler Speicherlebenszyklus im Decoder
an Stelle immer neuer Record-Instanzen. Das Experimentprojekt wird
explizit aktiviert und verwendet Standard-JMH und Gradle-Distribution.
Der Standardbuild bleibt frei von Experimentabhängigkeiten. P6 abgenommen.

## P7 · SIMD, Speicher und Batchparallelität

23. November 2026. Apple M5/macOS, Temurin 25.0.5+8-LTS aarch64,
Gradle 9.9.1 und JMH 1.37. `./gradlew --offline -Pexperiments
-Pvector :experiments:installDist` Exit 0, Gradle 515 ms.
Das [Experimentprotokoll](experiments.md#e2-e3-und-e5--vektoren-batchparallelität-und-io)
enthält die reproduzierbaren Messbefehle und Rohdaten. Reihenfolge der
ersten Messreihe war E2, E3, E5, danach der Gegenlauf zur unveränderten
Produkt-CLI mit denselben E3-Stages.

E2 mit zwei Forks und je fünf Messiterationen je Kernelvariante.
Alle Vergleiche der Auswahl und kompaktifizierten Recordbytes sowie
der Distanzen einschliesslich Tail-Tests bestanden. Vektorauswahl
109.826 µs statt 128.269 µs pro Samplebatch. Distanz praktisch
gleich. E3 mit zwölf vollständigen Versuchsläufen und drei
CLI-Gegenläufen. Je 195'738 Punkte geschrieben, stabiler
Record- und Statistikvergleich bestanden. Ein Worker erreicht
ungefähr den Durchsatz des schlankeren Produktpfads. E5 führte 126
kleine vollständige LAS-Scans mit gleichen XYZ-Summen durch.
Der warme Median fiel im Mappingversuch von 0.451 auf 0.285 ms,
der erste Zugriff dauerte länger. Alle Experimente wurden mit
Exit 0 gemessen und bleiben ausserhalb der Produktimplementierung.

Beim separat aufgerufenen E3-CLI-Gegenlauf fehlten zunächst die
Shellvariablen für die Zielpfade. Der Ausschnitt wurde mit den
richtigen Variablen aufgerufen und alle drei CLI-Läufe bestanden.
`bash -n experiments/p7.sh` prüfte das vollständige Messskript.

Standardprüfung `./gradlew --offline clean check installDist` Exit 0
in 1.07 s. 23 JUnit-Tests ausgeführt und erfolgreich, 0 fehlgeschlagen,
0 Fehler, 0 übersprungen. `LasIoTest` 14 (0.080 s), `PipelineTest`
7 (0.057 s), `MainTest` 2 (0.001 s). Native Reports unter
`build/test-results/test/` und `build/reports/tests/test/`.
Im Installationsverzeichnis liegen Produkt, Codec und Gson samt
Annotationsabhängigkeit.

Einfachheitsprüfung: direkte Schleifen, normaler Executor mit fester
Zahl Bufferslots und zwei klare Dateizugriffe. Kein neuer Backend-
oder Schedulingvertrag im Produkt. E2, E3 und E5 sind mit gemessener
Entscheidung abgeschlossen. P7 abgenommen.

## P8 · LAZ-Chunkparallelität

23. November 2026, Apple M5/macOS und Temurin 25.0.5+8-LTS aarch64
unter Gradle 9.9.1. Bei 416 GiB freiem Platz wurde die vorhandene
83'247'722-B-Eingabe verwendet. Die Vergleichspipeline schreibt pro
Lauf eine LAS-Datei von 6'655'601 B. Der [Chunkversuch](experiments.md#e4--unabhängige-laz-chunks)
enthält die gemessenen Byte- und Punktbereiche, Reproduktion und Rohdaten.

Einen hinreichenden Chunkstart liefert die bereits validierte Tabelle.
Der Versuch verwendet pro Task Arena, Cursors und Decoderzustand.
Es wird direkt am Seed-Record gestartet. Eine geordnete Queue und
höchstens acht wiederverwendbare Chunks halten den Speicher begrenzt.
Der bestehende LAS-Writer wird mit einem Abschlussnachweis der Quelle
genutzt. Der alte Punktseek mit 32-Bit-Positionen entfällt.

Ganzkachelprüfung mit `ChunkParallel 4 .cache/test-data/so-2019.laz
.cache/experiments/e4-verified.las --verify` verglich 14'566'627 vollständige
Records ohne Abweichung. 195'738 Punkte ausgewählt und geschrieben,
Exit 0 in 3.70 s einschliesslich erneuter sequenzieller Dekodierung.
Abgleich der Ausgabe mit dem zuvor gegen PDAL bestätigten Ground-Crop
vollständig bestanden. Variable Chunkgrössen auf einer kleinen
LazPerf-Datei (128'909 Records, 5 Chunks) exakt verglichen. Die resultierenden
1'730 Format-7-Punkte las PDAL in 0.14 s erfolgreich. Ein zulässig
fehlender Tabellenzeiger aktivierte den sequenziellen Pfad bei
50'003 Punkten, eine beschädigte Tabellenversion lieferte erwartungsgemäss
Exit 1 und keine Punktdatei.

`bash experiments/e4.sh` mass je drei vollständige Läufe mit 0, 2
und 4 Workers, Exit 0 für alle Läufe mit identischen Ausgaben.
Median 3.56 / 1.96 / 1.26 s, bei vier Workers ca. 2.83-facher
Durchsatz und Peak-RSS 251'510'784 B. Der reproduzierbare Versuch
bleibt separat, der Standardlesepfad bleibt sequenziell.

`./gradlew --offline clean check installDist` Exit 0, 1.06 s.
23 Tests ausgeführt und erfolgreich, 0 fehlgeschlagen, 0 Fehler,
0 übersprungen. `LasIoTest` 14 (0.081 s), `PipelineTest` 7 (0.058 s),
`MainTest` 2 (0.002 s). JUnit-XML unter `build/test-results/test/`,
HTML unter `build/reports/tests/test/`. Opt-in-`installDist` Exit 0
in 289 ms, `bash -n experiments/e4.sh` Exit 0.

Einfachheitsprüfung: ein geteiltes validiertes Tabellenschema, direkter
Chunkstart und kleine Writer-Grenze. Dateihülle und Codec für die
Standarddistribution benötigen keine Experimentabhängigkeit. P8 abgenommen.

## P9 · JVM-Versionen, AOT und Native Image

23. November 2026, Apple M5/macOS 27.0.1 arm64. Temurin
25.0.5+8-LTS, Temurin 27.0.1.1+4 aarch64, Oracle GraalVM
25.0.4.1.1 aarch64 mit Apple clang 21.0.0. Dieselben
Java-25-Klassen aus P8 wurden auf beiden JDKs benutzt.
Die [Build- und AOT-Befehle](experiments.md#e6-und-e7--jvm-version-aot-cache-und-native-image)
sind an der ausführbaren CLI geprüft.

Der AOT-Cache wurde mit getrennten Record- und Create-Läufen
erzeugt (5.67 s und 0.29 s, beide Exit 0, 16'384'000 B Cache)
und mit strengem `AOTMode=on` geladen. Der erste Native-Build
gelang in 48.58 s und lieferte ein arm64-Binary von 19'063'824 B.
Die Mini-Pipeline darin scheiterte bei Gson-Record-Reflexion mit
Exit 2. Die mit dem Agent ermittelten Methoden der drei
Ergebnis-Records wurden gezielt registriert. Der zweite Build
erzeugte ein echtes arm64-Binary von 19'080'352 B (42.40 s,
max. RSS 1'680'949'248 B, Exit 0). Die Mini-Pipeline lief danach
direkt mit Exit 0. Deren JSON war mit dem AOT-Lauf identisch.

`python3 experiments/p9-runtimes.py .cache/jdks/temurin27/Contents/Home/bin/java`
prüfte zwei Messserien (57.43 und 47.63 s, je Exit 0). Je Serie
28 Mini- und 12 Ganzkachelläufe, alle erfolgreich.
Alle 24 Ganzkachelausgaben mit je 195'738 Records waren mit
dem unabhängigen P5/P6-Referenzstand übereinstimmend.
Alle Mini- und Ground-Crop-Statistiken entsprechen dem
Java-25-Standard. Mini-Mediane in der wechselnden Serie
70.0 ms (25), 81.0 ms (27), 45.1 ms (AOT) und 7.7 ms (Native).
Bei voller Kachel liegen die Mediane bei 3.727 / 3.651 /
3.613 / 4.050 s. Native RSS blieb unter 95 MiB.

Der erfolgreiche P8-Standardlauf mit 23 JUnit-Tests, 0 Failures,
0 Fehlern und 0 Skips wurde wegen unveränderter CPU-Quellen,
unveränderten Tests, Fixtures, Build und Java-25-Toolchain
wiederverwendet. Die neuen Realdaten-Produktvergleiche dieser
Etappe liefen mit den tatsächlichen JVM-, AOT- und Native-Artefakten.
Einfachheitsprüfung: enger Reflexionsumfang für die drei
Record-Typen, keine Native- oder AOT-Voraussetzung im
Standardbuild. E6 und E7 mit realen Artefakten abgenommen. P9 abgenommen.

## P10 · TornadoVM auf der M5-GPU

23. November 2026. TornadoVM 8.1.0-jdk22plus, Metal, Temurin
25.0.5+8-LTS aarch64, Apple M5/macOS 27.0.1. Vor dem
gezielten Abruf der optionalen Maven-Bibliotheken waren 413 GiB
frei. Installation und Geräteabfrage via `./gradlew -Pexperiments
-Ptornado :experiments:installDist` und `TornadoCrop --device`
Exit 0. Die [vollständige Reproduktion](experiments.md#e8--tornadovm-auf-apple-m5)
beschreibt die explizite GPU-Auswahl, den Kernel und die Kosten.

Die erste Swift-Probe erzeugte wegen falsch übernommener
Interpolation einen ungültigen Kontrollshader (Exit 133).
Interpolation und Fehlerausgabe korrigiert und erneut ausgeführt.
`swift experiments/probe-metal.swift` zeigte Apple M5 mit
Unified Memory und einen kompilierten Float-Kontrollshader.
Der Double-Shader wurde vom selben Compiler erwartungsgemäss
abgelehnt. Probe Exit 0.

Drei neue Prozesse mit je elf realen Metal-Geräteausführungen,
alle Exit 0. Die Laufzeiten der drei Prozesse waren 0.70 /
0.34 / 0.31 s. Der erste Ausführungsmedian inkl. Kompilierung
war 103.251 ms. Warme Geräte-Kernelzeit 26.8 µs, kompletter
warmer Batch inkl. Transfers und Kompaktierung 1.263 ms.
CPU-Kompaktierung bei gleicher Arbeit 0.176 ms im Median.
Die Host-/Device-Kosten und Vergleiche stehen im
Experimentprotokoll. Alle 4'253'997 Maskeneinträge und
57'090 kompaktifizierten Records über die 33 Ausführungen
stimmen exakt mit der skalaren Double-Referenz.
Ergebnis: kein Einsatz im Produktpfad.

`./gradlew --offline clean check installDist` ohne Experimentflags
Exit 0 in 1.00 s. `LasIoTest` 14 erfolgreich (0.084 s),
`PipelineTest` 7 (0.053 s), `MainTest` 2 (0.001 s), insgesamt
23 erfolgreich und 0 fehlgeschlagen, 0 Fehler, 0 Skips.
JUnit-XML unter `build/test-results/test/`, HTML-Report unter
`build/reports/tests/test/`. Kein TornadoVM-JAR im Produkt-Installationsbaum.
Einfachheitsprüfung: ein Filterkernel, explizite Transfers,
stabile Host-Kompaktierung und Standard-Metal-Backend ohne
Accelerator-Infrastruktur. E8 durch reale GPU-Messung
abgeschlossen. P10 abgenommen.

## P11 · Konsolidierung und Lieferung

23. November 2026, Apple M5/macOS 27.0.1 arm64. Gradle 9.9.1 mit
Temurin 25.0.5+8-LTS für Launcher, Compiler und Tests. Realdatenreferenz
PDAL 2.10.2/LazPerf. Die zwei bezogenen Kacheln belegen zusammen
157'509'563 B. Die ursprüngliche Auftragsdatei ist seit B0 unverändert.

Der Abschlusscheck fand einen Abbruchfehler. Ein SIGTERM bei
geöffneter temporärer Ausgabe führte zu Exit 143 und hinterliess
die temporäre Datei. Die CLI unterbricht jetzt den Pipeline-Thread
über einen einfachen Shutdown-Hook und wartet darauf, dass seine
Ressourcen geschlossen sind. Der direkte SIGTERM-Test im
Realdatenaufruf endete erwartungsgemäss mit Exit 143 in 0.10 s
und ohne Ziel- oder temporäre Datei.

| Prüfung | Ergebnis |
| --- | --- |
| `./gradlew clean check installDist distZip --info` | Exit 0, 1.01 s. 23 JUnit-Tests, 0 Failures, 0 Fehler, 0 Skips. |
| `./gradlew --offline clean check installDist distZip` auf dem Auslieferungsstand | Exit 0, 1.03 s. `LasIoTest` 14 (0.079 s), `PipelineTest` 7 (0.052 s), `MainTest` 2 (0.001 s). Insgesamt 23 erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 Skips. |
| ZIP und entpackte CLI | `unzip -tq`, Vergleich der enthaltenen MIT-Lizenzdatei, `--version`, `--help`, `info` und Mini-Pipeline erfolgreich. `info` meldete 5 Punkte, Format 8, 2 Extra Bytes. Mini-Metadaten identisch mit AOT und Native. |
| `java -cp '.cache/p11/dist/pointcloud/lib/*' examples/ReadBatches.java src/test/resources/las/format-3.laz` | Exit 0, 0.23 s, 5 gelesene Records. Erstes Raw X −200, World X 2'593'998.0. |
| Offline-Datencache | `downloadTestData` und `downloadTestData -Ptile=2014` Exit 0 in 0.28/0.26 s. Vollständige bestehende Dateien wiederverwendet. |
| `python3 tools/check-real-data.py "$PDAL"` | Exit 0 in 23.04 s. Alle Produktläufe mit `-Xmx512m`. Beide Kacheln und alle drei unveränderten Beispiele komplett. 353'342 ausgegebene Records gegen PDAL verglichen, 0 Abweichungen. Statistiken innerhalb der P5-Toleranz, gleiche Counts, Bounds, CRS-Inhalte, Quantisierung und Return-Zähler. |

JUnit-Berichte unter `build/test-results/test/` und
`build/reports/tests/test/`. Kompakte Prüf- und Werkzeuglogs unter
`build/reports/p11/`. Der finale Realdatenlauf benötigte für Java/PDAL
3.66/1.80 s für Ground-Crop, 3.75/2.09 s für den 2019-Statslauf,
3.53/1.67 s für die Dezimierung und 3.59/1.90 s für 2014.
Die ausgewählten Punkte entsprechen P5 (195'738, 128'909
und 28'695 geschriebene Records). Die drei Java-LAS-Dateien
wurden zusätzlich durch PDAL vollständig gelesen. Die
CRS-/VLR-Regeln und die Statistikabweichungen entsprechen P5.

Die [AOT-/Native-Befehle](experiments.md#e6-und-e7--jvm-version-aot-cache-und-native-image)
wurden mit dem aktualisierten CLI-Code erneut geprüft. AOT-Training
3.84 s, Assembly 0.17 s für 16'449'536 B Cache, Laden in
`AOTMode=on` bestätigt. Nativer Build 31.71 s, Peak-RSS
1'874'837'504 B, 19'080'384 B Mach-O-arm64-Binary. Beide
Mini-Pipelines und der Java-27-Minigegenlauf stimmen fachlich
überein. Beide vollen Ground-Crop-Läufe (AOT 3.56 s, Native
4.09 s) endeten erfolgreich mit 195'738 bytegleichen
Ausgaberecords und denselben Statistiken. Die P9-Messreihen
bleiben an ihre dokumentierte Basis gebunden.

Das JVM-ZIP (456'824 B) liegt unter `build/distributions`,
das Anwendungs-JAR (42'357 B) unter `build/libs`. Das
Installationsverzeichnis enthält die Laufzeitbibliotheken
und einen separaten Codec mit den zugehörigen Lizenztexten.
Die CI-Konfiguration baut und prüft den CPU-Standardpfad
und erzeugt das ZIP. Die Befehle wurden lokal ausgeführt.
Die erzeugten grossen LAS-Zwischenstände, JFR-Aufnahmen,
Native Binaries und AOT-Caches wurden bereinigt. Die
zwei Realdatenkacheln verbleiben im ignorierten Cache.

Die Einfachheitsprüfung behält die wiederverwendbaren vollständigen
Records und den skalaren Standardleser. Decoder-Allokationen sind
der nachgewiesene und behobene Speicherengpass. LAZ-Dekodierung
dominiert die Laufzeit. Der geordnete Chunkversuch war auf der
M5-Maschine bis zum Faktor 2.83 schneller und bleibt isoliert.
SIMD, Batchpool, I/O, JVM-Wechsel, AOT, Native und Metal-GPU
sind mit Messwerten und einem dokumentierten Entscheid
abgeschlossen. Keine Pflichtprüfung offen. P11 abgenommen.

### P11-Nachprüfung · Fehlerpfade und PDAL-Kompatibilität

24. November 2026, Apple M1/macOS 15.8.1 arm64, Gradle 9.9.1,
Temurin 25.0.3+9 und PDAL 2.10.2/LazPerf aus `libpdal-core` für
conda-forge arm64 (`h151f718_4`). Die zwei Solothurn-Kacheln wurden
über `downloadTestData` und `downloadTestData -Ptile=2014` bezogen
und belegen 157'509'563 B.

Ein Lese- oder LAZ-Endprüfungsfehler beendet den Scan dauerhaft.
Mit `--metadata` prüft die CLI die Zielpfade vor dem Schreiben, legt das
JSON temporär ab und veröffentlicht es vor der LAS-Datei. Scheitert der
LAS-Abschluss, wird der vorherige Metadatenstand wiederhergestellt. Der
zusätzliche Abschlussaufruf bleibt auf die Pipeline und die CLI begrenzt.
Die direkten Regressionstests prüfen den erneuten Leseversuch, die
verweigerte Ausgabe, den Erhalt bestehender Dateien, verschachtelte Zielpfade
und das Aufräumen. Die Tests zum LAZ-Endfehler, ungültigen Metadatenziel und
verschachtelten Ziel schlugen vor der jeweiligen Korrektur fehl.

`./gradlew --offline clean check installDist distZip` lief in 2.67 s mit
Exit 0. Alle 27 JUnit-Tests bestanden, 0 Fehler, 0 fehlgeschlagen und
0 übersprungen. `LasIoTest` 15 (0.227 s), `MainTest` 5 (0.052 s) und
`PipelineTest` 7 (0.116 s). Die Berichte liegen unter
`build/test-results/test/` und `build/reports/tests/test/`. `unzip -tq`
prüfte das 458'680 B grosse JVM-ZIP erfolgreich.

`python3 tools/check-real-data.py "$PDAL"` lief mit `-Xmx512m` in
48.13 s mit Exit 0. Das Protokoll liegt unter `build/reports/pdal-2.10.2.log`.

| Pipeline | Gelesen | Ausgewählt | Geschrieben und vollständig verglichen | Java / PDAL LAS-Bytes |
| --- | ---: | ---: | ---: | ---: |
| Ground-Crop 2019 | 14'566'627 | 195'738 | 195'738 | 6'655'601 / 6'655'515 |
| Statistik 2019 | 14'566'627 | 14'566'627 | 0 | – |
| Dezimierung 2019 | 14'566'627 | 128'909 | 128'909 | 4'383'415 / 4'383'329 |
| Solothurn 2014 | 14'347'366 | 28'695 | 28'695 | 803'689 / 803'687 |

Alle 353'342 Punktrecords stimmten bytegenau überein. Header, Bounds,
Quantisierung, Return-Zähler, Quell-VLRs und CRS bestanden die Vergleiche.
PDAL las alle drei Java-Ausgaben vollständig. Minimum und Maximum waren
exakt. Die grössten absoluten Abweichungen von Mittelwert, Varianz und
Standardabweichung waren `4.43e-4`, `3.29e-3` und `2.25e-7`, jeweils
innerhalb der P5-Toleranz. SIGTERM beendete den Lauf in 0.20 s mit Exit 143
und ohne Ziel- oder Temporärdatei. Ein zusätzlicher SIGTERM-Test mit
`--metadata --overwrite` endete in 0.23 s mit Exit 143. Die vorherigen
Metadaten blieben bytegleich. LAS-Ziel und temporäre Dateien waren
bereinigt. Protokoll unter `build/reports/cli-metadata-cancel.log`.

Kleine Fixtures bestätigten die dokumentierten
[Kompatibilitätsgrenzen](architecture.md#kompatibilität-mit-pdal-2102).
PDAL wies `+2.1e1` und `.5` mit Exit 1 zurück. `2.1e1` und `0.5`
wählten in beiden Engines dieselben Punktzahlen. Die fünf Format-8-Records
blieben in Java bei 40 B. PDAL schrieb durch das zusätzliche `Infrared`
42 B pro Record. Die Standardattribute und der `Confidence`-Wert stimmten
überein. Protokoll unter `build/reports/pdal-2.10.2-fixtures.log`.

### E4-Nachversuch · Vollständige Pipeline

24. November 2026, Apple M1/macOS 15.8.1 arm64, Gradle 9.9.1,
Temurin 25.0.3+9 und PDAL 2.10.2/LazPerf (`h151f718_4`). Ausgangspunkt
`cc31a43`. Eine interne Batch-Eingabe verbindet den Chunkleser aus dem
Experimentmodul mit der bestehenden CLI und Pipeline. Beide
Parallelvarianten verarbeiten dieselben Stages und teilen das geordnete
Pufferschema. Der Unterschied liegt in der Initialisierung pro Chunk
oder der Wiederverwendung von Tabelle, Mapping und Decoderzustand.

- `./gradlew -Pexperiments clean check installDist distZip` bestand mit
  Exit 0 in 3.55 s inklusive lokal fehlender JMH-Abhängigkeiten aus dem
  regulären Gradle-Repository. Der abschliessende Standardbuild
  `./gradlew --offline clean check installDist distZip` bestand mit Exit 0
  in 1.89 s. 27 Tests, 0 Fehler, 0 fehlgeschlagen, 0 übersprungen.
  `LasIoTest` 15 (0.144 s), `MainTest` 5 (0.034 s), `PipelineTest` 7
  (0.070 s). XML unter `build/test-results/test/`, HTML unter
  `build/reports/tests/test/`. `unzip -tq` prüfte das 462'719 B grosse ZIP.
- `python3 experiments/check-e4-pipeline.py --pdal "$PDAL"` bestand mit
  Exit 0 in 2.96 s. Feste Chunks und die gültige Datei ohne Chunktabelle
  lieferten je 16'667 geordnete Records. Format 8 behielt NIR und Extra
  Bytes bei. Fünf adaptive Chunks mit insgesamt 200'003 Records prüften
  die Wiederverwendung über mehr Chunks hinweg als Worker vorhanden sind.
  Beschädigte Layer ergaben in allen drei Modi Exit 2. SIGTERM ergab bei
  beiden Parallelvarianten Exit 143. Bei Fehler und Abbruch blieben keine
  LAS-, JSON- oder Temporärdateien zurück.
- Der bisherige Aufruf `ChunkParallel 4 ... --verify` verglich zusätzlich
  alle 14'566'627 dekodierten Eingaberecords bytegenau und in Reihenfolge
  gegen den seriellen Reader. Exit 0 in 5.30 s, 195'738 Punkte geschrieben.
- `python3 experiments/e4-pipeline.py "$PDAL" .cache/experiments/e4-pipeline.csv`
  beendete vier Vorläufe und 20 Messläufe mit Exit 0 in 69.73 s. Pro Lauf
  14'566'627 Eingabepunkte und 195'738 bytegleiche Ausgaberecords. Die
  Java-Metadaten waren identisch, PDAL innerhalb der P5-Toleranz.
  [Messwerte und Entscheidung](experiments.md#e4-nachversuch--vollständige-ground-crop-pipeline-gegen-pdal)
- `python3 tools/check-real-data.py "$PDAL"` bestand im Standardbuild
  mit Exit 0 in 34.39 s. Beide Kacheln und alle drei Beispiele liefen
  mit `-Xmx512m`. 353'342 Records waren bytegleich. Header, VLRs, CRS und
  Statistiken bestanden die Vergleiche. SIGTERM lieferte in 0.20 s Exit
  143 und hinterliess keine Ausgabe. Log unter
  `build/reports/pdal-2.10.2-e4.log`.

### P11-Nachtrag · Parallele LAZ-Dekodierung als CLI-Option

24. November 2026, Apple M1/macOS 15.8.1 arm64, Temurin 25.0.3+9,
Gradle 9.9.1 und PDAL 2.10.2/LazPerf (`h151f718_4`). Ausgangspunkt
`6dfcd0c`. Die CLI unterstützt `--laz-workers 1` bis `4` mit Standard 1.
Der Produktpfad und die E4-Vergleiche verwenden denselben Scheduler.
Die Worker teilen die validierte Chunktabelle und geben Batches in
Dateireihenfolge an die bestehende Pipeline. Die [Speichergrenzen und
seriellen Rückfälle](architecture.md#lesen-und-schreiben) gelten vor
dem Start der Worker.

Autzen wurde vollständig bezogen. Die drei gespeicherten Realdatendateien
belegen 238'632'605 B. Autzen enthält 278 adaptive Chunks für 10'653'336
Punkte in Format 7 mit 36 B pro Record. Der grösste dekodierte Chunk
belegt 2'774'232 B, der grösste komprimierte 763'258 B. Solothurn 2014
enthält 287 feste Chunks mit bis zu 50'000 Punkten in Format 1.
[Dateiquellen](provenance.md#realdaten)

- `./gradlew --offline -Pexperiments clean check installDist distZip`
  bestand mit Exit 0 in 6.54 s. Alle 30 JUnit-Tests bestanden, 0 Fehler,
  0 fehlgeschlagen, 0 übersprungen. `LasIoTest` 15 (0.340 s), `MainTest`
  8 (0.364 s), `PipelineTest` 7 (0.137 s). XML unter
  `build/test-results/test/`, HTML unter `build/reports/tests/test/`.
  `unzip -tq` prüfte das 469'766 B grosse JVM-ZIP erfolgreich.
- `python3 experiments/check-e4-pipeline.py --product --pdal "$PDAL"`
  bestand mit Exit 0 in 4.95 s. Feste Chunks und gültig fehlende Tabellen
  lieferten je 16'667 geordnete Records. Format 8 behielt NIR und Extra
  Bytes bei, auch beim LAS-Rückfall. Bei fünf adaptiven Chunks stimmten
  alle 200'003 Records. Ein Format-6-Chunktest mit 178 B pro Punkt und
  8'900'000 B dekodiertem Chunk prüfte den seriellen Rückfall. Beschädigte
  Daten und SIGTERM führten zu Exit 2 beziehungsweise 143 ohne Teilausgabe.
  Der bisherige Experimentaufruf ohne `--product` bestand in 4.72 s.
- Der Kurztest auf dem M1 verwendete die fünf Punkte der Format-8-Fixture
  mit Statistik und `writers.null`. Je neun frische Prozesse lieferten
  identische Metadaten. Median 135.93 ms (107.04–166.35) im Standard und
  118.03 ms (110.07–163.87) mit `--laz-workers 4`. Die Datei hat nur einen
  Chunk, beide Pfade lesen seriell und die Laufzeitbereiche überlappen.
- `./gradlew --offline check installDist distZip downloadTestData -Ptile=autzen`
  bestand mit Exit 0 in 0.72 s und verwendete den vollständigen Download.
  Die Produkttests stammten aus dem frischen Build oben.
- `python3 tools/check-parallel-data.py "$PDAL" .cache/experiments/parallel-real-data.csv`
  bestand mit Exit 0. Alle 18 Messläufe verglichen pro Lauf 28'695
  Solothurner oder 106'534 Autzen-Records vollständig. Gegen PDAL gab es
  null Recordabweichungen. Java-LAS und -JSON stimmten seriell und
  parallel bytegenau überein. PDAL las beide Java-LAS vollständig und
  bestätigte die CRS. Java bewahrte Autzens WKT und entfernte dessen
  COPC-Indexrecords bei LAS-Ausgabe. Die Statistiken lagen innerhalb
  der P5-Toleranz. Die Headerprüfung dokumentierte die numerisch gleichen
  Null-Offsets von Solothurn und den Unterschied von einem ULP in Autzens
  minimaler Z-Grenze. Protokoll unter `build/reports/pdal-parallel-2.10.2.log`.
  [Messwerte](experiments.md#produktabnahme--laz-workers-4)
- `python3 tools/check-real-data.py "$PDAL"` bestand im seriellen
  Standard mit Exit 0 in 33.88 s. Beide Solothurner Kacheln und die
  drei Beispiele liefen mit `-Xmx512m`. Alle 353'342 verglichenen
  Punktrecords waren bytegleich. CRS, Quell-VLRs und Statistiken bestanden
  die Vergleiche. SIGTERM ergab in 0.18 s Exit 143 ohne Teilausgabe.
  Protokoll unter `build/reports/pdal-standard-2.10.2.log`.

### P11-Nachtrag · Lokale Korrekturen auf Origin übernehmen

24. November 2026, Apple M5, macOS 27.0.1 arm64, 24 GiB RAM,
Gradle 9.9.1 und Temurin 25.0.5+8-LTS. Die Übernahme von `0a17961`
setzt auf `1c3a069` auf. Sie ergänzt die CRS-Anzeige, die Endprüfung
für LAZ ohne Chunktabelle, den Hardlink-Schutz für neue LAS-Ziele und
den Schutz der Pipeline-Konfiguration. Die Prüfung im Decoder läuft
beim Chunkabschluss und verändert die Punktschleife nicht. Die
Origin-Reader, die geordnete Worker-Ausführung und die Veröffentlichung
und Wiederherstellung der JSON-Metadaten bleiben bestehen.

Die ergänzten Tests zur CRS-Anzeige, zum Dateiende ohne Chunktabelle
und zur Pipeline-Konfiguration zeigten zunächst den jeweiligen Fehler
auf `1c3a069`. Der neue Format-8-Test bestand bereits dort und bleibt
als Nachweis für die vollständige Erhaltung des selektierten Records.
Die Unterschiede bei PDAL-Literalen waren bereits in der
Origin-Kompatibilitätsdokumentation enthalten.

| Prüfung | Ergebnis |
| --- | --- |
| `./gradlew --offline clean check installDist distZip` | Origin Exit 0 in 6.32 s. Nach Übernahme Exit 0 in 2.06 s. 34 JUnit-Tests erfolgreich, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen. `LasIoTest` 16 (0.107 s), `MainTest` 10 (0.074 s), `PipelineTest` 8 (0.030 s). |
| `unzip -tq build/distributions/pointcloud.zip` und installierte CLI | ZIP intakt. Hilfe zeigt `--laz-workers`. `info` zeigt EPSG:2056 und das GeoTIFF-Zitat der 2019-Kachel. |
| `python3 tools/check-real-data.py "$PDAL"` | Exit 0 in 23.37 s, PDAL 2.10.2/LazPerf. Beide Solothurner Kacheln vollständig mit `-Xmx512m`. 195'738, 128'909 und 28'695 Records vollständig verglichen, insgesamt 353'342 und 0 Abweichungen. Quell-VLRs, CRS, Bounds, Quantisierung und Return-Zähler stimmten. Statistikzählungen und Extrema exakt, grösste absolute Abweichungen 4.43e-4 beim Mittelwert, 3.29e-3 bei der Stichprobenvarianz und 2.25e-7 bei der Stichprobenstandardabweichung, innerhalb der P5-Toleranz. SIGTERM mit JSON in 0.11 s, Exit 143, alle Ausgaben und temporären Dateien bereinigt. |
| `python3 experiments/check-e4-pipeline.py --product --pdal "$PDAL"` | Exit 0 in 2.41 s. Default und vier Worker liefern identische LAS- und JSON-Ausgaben. Geprüft wurden feste Chunks mit und ohne Tabelle (je 16'667 ausgewählte Records), Format 8 (je 5 LAS- und LAZ-Records), adaptive Chunks (200'003) und breite Records (11). Dekodierfehler und SIGTERM ohne veröffentlichte Ausgabe. |
| `./gradlew --offline -Pexperiments :experiments:installDist` und `python3 experiments/check-e4-pipeline.py --pdal "$PDAL"` | Build Exit 0, Prüflauf Exit 0 in 2.24 s. Identische Ausgaben und bestandene Fehler- und Abbruchfälle auch im Experimentpfad. |

Der erste E4-Prüflauf fand im Fixture ohne Chunktabelle noch 15 Bytes
der ursprünglichen Tabelle hinter dem Punktdatenbereich. Die CLI
meldete Exit 2. Die Fixture-Erzeugung entfernt jetzt die Tabelle und setzt
den Abwesenheitsmarker. Die Produkt- und Experimentläufe bestanden
anschliessend vollständig. Native JUnit-Berichte unter
`build/test-results/test/` und `build/reports/tests/test/`. Prüfprotokolle
unter `.cache/port-0a17961/`.

Die getrennte JVM-Messreihe mit Rohdaten gehört zu `0a17961`. Ihr
Messhelfer wurde neu kompiliert und gegen den überarbeiteten Stand mit
`AOTMode=on` ausgeführt. Training und Erzeugung dauerten 3.83 s und
0.19 s, der Cache belegt 16'515'072 B. Vier vollständige Ground-Crop-Scans
in derselben JVM dauerten zusammen 14.29 s (Peak-RSS 158'646'272 B) und
lieferten je bytegleiche LAS- und JSON-Ausgaben. Oracle GraalVM
25.0.4.1.1 erzeugte in 36.17 s bei 1'583'071'232 B Peak-RSS ein
arm64-Mach-O-Binary von 19'163'200 B. Der Native-Lauf mit vier Workern
und `-Xmx512m` benötigte 1.67 s bei 119'767'040 B Peak-RSS und lieferte
dieselben 195'738 Records und dasselbe JSON. Beide Artefakte wurden
anschliessend entfernt.

Der eigene [Laufzeitvergleich gegen Origin](experiments.md#laufzeitvergleich-nach-übernahme-von-0a17961)
umfasst vier Vorläufe und je fünf gemessene Durchläufe pro Stand und
Workerzahl. Alle LAS-Dateien und JSON-Ausgaben waren bytegleich. Die
Gesamtzeit des Messskripts betrug 58.21 s. Die Medianzeiten lagen bei
einem Worker bei 3.59 s (Origin) und 3.60 s (Übernahme), mit vier Workern
bei 1.17 s und 1.20 s. Die Überlappung der Messbereiche und der Wechsel
der paarweisen Differenzen zeigen keine konsistente Verlangsamung in
diesem Versuch. Die beiden Eingabekacheln belegen zusammen 157'509'563 B.

Die Einfachheitsprüfung behielt die Origin-Pipeline, den bestehenden
Ausgabepfad und die vorhandenen Prüfprogramme. Der AOT-Messhelfer
bleibt ausserhalb der Distribution. Die Origin-Abnahme des
Parallelpfads auf der Solothurn-2014-Kachel und Autzen ist mit
ihren M1-Messwerten beim Basisstand dokumentiert.

## JFR der parallelen LAZ-Produktpipeline

24. November 2026, Apple M1/macOS 15.8.1 arm64, Temurin 25.0.3+9,
PDAL 2.10.2/LazPerf (`h151f718_4`), Produktcode `1c3a069`. Der
Realdaten-Runner verwendet dieselben Pipelines und Referenzprüfungen für
den CSV- und den JFR-Lauf. Die Profilauswertung verwendet das `jfr`-Werkzeug
des eingestellten JDK und die Python-Standardbibliothek.

- Der CSV-Lauf mit 18 Messungen und der JFR-Lauf mit zwölf Aufnahmen
  bestanden mit Exit 0. Abnahme je 28'695 Solothurner und 106'534
  Autzen-Records. Java-LAS und -JSON waren bytegleich mit der seriellen
  Referenz. PDAL bestätigte Record-Reihenfolge, Statistik und CRS mit
  denselben Toleranzen wie bei der Produktabnahme. Die Auswertung aller
  Aufnahmen erkannte die erwarteten Decoder-Threads und keinen JFR-Datenverlust.
  [Befehle und Messwerte](experiments.md#jfr-der-parallelen-produktpipeline)
- `./gradlew --offline clean check installDist` bestand mit Exit 0.
  Alle 30 JUnit-Tests bestanden. Das XML unter `build/test-results/test/`
  enthält 15 `LasIoTest`, 8 `MainTest` und 7 `PipelineTest` ohne Fehler
  oder übersprungene Tests.
- `python3 experiments/check-e4-pipeline.py --product --pdal "$PDAL"`
  bestand mit Exit 0. Die Fixture-Ausgaben waren seriell und parallel
  gleich. Dekodierfehler und SIGTERM veröffentlichten keine Teilausgabe.

## A/B-Versuch zur Symbolsuche

24. November 2026, Apple M1/macOS 15.8.1 arm64, Temurin 25.0.3+9,
PDAL 2.10.2/LazPerf (`h151f718_4`). `1c3a069` und die Maskenvariante
wurden als zwei installierte Distributionen gesichert. Nur `codec.jar`
unterschied sich. Die Patchdatei und SHA-256-Manifestdateien liegen
mit den Protokollen im lokalen A/B-Verzeichnis unter `.cache/experiments/`.

- Alle zehn direkten Decoder-Testfälle bestanden in A und B. Die erste
  gleichmässige 17-Symbol-Folge scheiterte auf beiden Builds am selben
  Index. Der Schritt der deterministischen Folge wurde von 53 auf 5
  geändert. Folgenlänge, Alphabetabdeckung und Assertions zu Modellupdates
  bleiben gleich. Die getrennten schiefen Folgen prüfen die Reskalierung.
  `./gradlew --offline clean check installDist` bestand mit B in allen
  40 JUnit-Fällen. Die Berichte liegen unter `build/test-results/test/`.
- `tools/check-full-records.py` verglich jeweils mit 1 und 4 Workern für
  A und B 14'347'366 Solothurner und 10'653'336 Autzen-Rohrecords über
  Punktzahl und SHA-256 der geordneten Recorddaten. Alle Hashes je
  Datensatz waren gleich. Die Prüfung hielt jeweils eine LAS-Ausgabe
  auf Platte.
- Die Produkt-Fixtures samt Korruption und SIGTERM sowie
  `tools/check-real-data.py` bestanden mit B. Die serielle PDAL-Abnahme
  für Solothurn 2019 und 2014 ergab null Abweichungen in den 353'342
  verglichenen Rohrecords. Die Statistik- und CRS-Prüfungen bestanden.
- Der A/B-Runner beendete alle 56 Messläufe mit Exit 0. Die Java-Ausgaben
  bestanden den bytegenauen Vorvergleich sowie den Vergleich nach jedem
  Lauf. PDAL bestätigte mit vier Reader-Workern die 28'695 und 106'534
  ausgewählten Rohrecords, Statistik und CRS. B gewann die Autzen-Reihe
  mit vier Workern um 5.11 % und überschritt die vereinbarte
  Regressionsgrenze von 2 % in den drei übrigen Reihen. Der Produktbuild
  wurde mit A wiederhergestellt. Alle 40 JUnit-Fälle bestanden erneut,
  und das installierte `codec.jar` war bytegleich zur Baseline.
  [Ergebnis und Messreihe](experiments.md#ab-versuch--symbolsuche-im-arithmetic-decoder)

## A/B-Versuche zu Records, Queue und Layer-Puffern

24. November 2026, Apple M1/macOS 15.8.1 arm64, Temurin 25.0.3+9,
PDAL 2.10.2/LazPerf (`h151f718_4`). Drei isolierte Builds auf dem
Produktcode `1c3a069`. Der Runner kann für die Reader-Kontrolle zwei
installierte CLIs sowie zum Profilieren eine bestimmte CLI verwenden.

- Die Offline-Gradle-Prüfungen bestanden für die drei Builds mit 40,
  40 beziehungsweise 43 JUnit-Fällen. Die drei zusätzlichen Layer-Tests
  prüfen begrenzte Sichten, Überspringen, Trunkierung und den Kopierpfad.
- Je Build und Workerzahl stimmten die SHA-256-Digests der vollständigen
  geordneten Rohrecord-Folgen mit A überein. Geprüft wurden je 14'347'366
  Solothurner und 10'653'336 Autzen-Punkte. Die bestehenden Produkt-Fixtures
  bestanden bei allen Kandidaten.
- Die Queue-Fixture prüfte alle 500'003 Records aus elf Chunks. Der
  absichtlich beschädigte Chunk am Index 8 führte bei 400'000 dekodierten
  Punkten zu Exit 2. SIGTERM nach 197'982 temporär geschriebenen
  LAS-Bytes führte zu Exit 143. Beide Prüfungen bestätigten die vollständige
  Bereinigung von LAS und Metadaten.
- Die Format-8-Layer-Fixture enthält 100'003 Punkte mit RGB-Layerlängen
  5'230/226/11 und NIR-Layerlängen 3'201/0/6. Mit einem und zwei Workern
  stimmten die gültigen LAS und JSON bytegenau überein. Ein auf drei Bytes
  verkürzter XY-Pflichtlayer führte mit beiden Workerzahlen bei Punkt
  50'000 zu Exit 2. Der LAZ-Chunkindex blieb gültig. Die Fehlerläufe
  hinterliessen keine Ziel- oder Temporärdatei.
- Die drei A/B-Serien und Reader-Kontrollen ergaben 186 unprofilierte
  Prozessläufe mit Exit 0. Die LAS- und JSON-Ausgaben entsprachen
  bytegenau der jeweiligen A-Referenz. In jedem PDAL-Vorvergleich
  stimmten je 28'695 und 106'534 Records in Dateireihenfolge überein.
  CRS und Statistik bestanden mit den bisherigen Toleranzen.
- 48 getrennte JFR-Aufnahmen erfassten für jeden Build beide Dateien.
  Alle Ausgaben bestanden den Vergleich. Die Auswertung erkannte alle
  erwarteten Decoder-Threads und meldete keinen Datenverlust. Der
  Layer-Matcher erfasst auch den Array-Puffer in `prepareLayerPayload`.
- A bleibt Produktstandard gemäss den vereinbarten Zeit- und RSS-Grenzen.
  `clean check installDist` für A bestand mit 40 JUnit-Fällen. Die
  installierten Produkt- und Codec-JARs stimmen bytegenau mit den
  eingefrorenen Ausgangsartefakten überein. Die drei Patches lassen sich
  einzeln auf A anwenden. [Versuchsbericht](experiments.md#point14rgb-gemeinsame-queue-und-layer-puffer)

## Oracle Native Image und PGO mit Serial und G1

24. November 2026, Apple M1/macOS 15.8.1 arm64, Apple clang 17.0.0,
Oracle GraalVM 25.6.5.1+4.1, Temurin 25.0.3+9 und PDAL 2.10.2/LazPerf.
Produktcode `1c3a069`. Die vorbereiteten Eingaben belegen zusätzlich
104'973'277 Bytes. Die vollständige Record-Prüfung verwendet jeweils
eine LAS-Ausgabe gleichzeitig. Toolchain und Builds liegen im lokalen
Cache. Beide GC-Probes liefen erfolgreich und meldeten tatsächliche Pausen.

- `./gradlew --offline clean check installDist` bestand mit 40 JUnit-
  Tests, ohne Fehler oder übersprungene Tests. Die SHA-256-Digests
  aller Produkt-JARs stimmten vor und nach der Messung überein.
- Vier Native Images bestanden den Mach-O-arm64-Nachweis. Acht
  Trainingsprofile sind per SHA-256 dokumentiert. Die beiden gemessenen
  PGO-Builds meldeten `PGO: user-provided`. Die instrumentierten Builds
  verwenden laut Builder Stufe 2 und `sampling+instrument` für die
  Profilerfassung. Die vier gemessenen nativen Builds melden Stufe 3.
- `check-e4-pipeline.py --product-cli` und `check-real-data.py --cli`
  bestanden für alle vier nativen Launcher. Beide SIGTERM-Prüfungen
  lieferten Exit 143 und bereinigten temporäre Ausgaben. Die historischen
  PDAL-Pipelines verglichen pro nativen Build 353'342 Rohrecords ohne
  Abweichung. Alle 30 vollständigen Rohrecord-Läufe auf den drei neuen
  Messfällen hatten je Datensatz denselben Hash.
- Die Runtime-Matrix ergab 245 Prozessläufe und drei PDAL-Vorvergleiche
  mit insgesamt 221'763 ausgewählten Records ohne Abweichung. Statistik
  und CRS bestanden. 15 separate GC-Läufe erfassten Pause-Anzahl,
  Summe und Maximum. Native Serial bleibt die GC-Empfehlung auf diesem
  Mac. Temurin bleibt Produktstandard gemäss den vereinbarten Grenzen.
  [Versuchsbericht](experiments.md#oracle-native-image-mit-pgo-und-g1-auf-dem-m1)

## Feinere Suchtabelle im Arithmetic Decoder

25. November 2026, Baseline `2b10708`. Diagnose, zwei Tabellenbreiten
und Entscheidung sind im [Versuchsbericht](experiments.md#ab-versuch--feinere-suchtabelle-im-arithmetic-decoder)
dokumentiert. Die vier betroffenen Alphabetgrössen sind 33, 64, 128 und 256.

- `./gradlew --offline clean check installDist` bestand für beide Kandidaten
  und die damalige Produktdistribution A. Je 64 JUnit-Fälle, keine
  Fehler, keine übersprungenen Tests. Direkte Decoder-Fälle prüfen
  Symbolgrenzen, Reskalierung und den maximalen Tabellenquotienten
  inklusive Byteposition und folgendem Rohbit.
- Beim Erweitern gleichverteilter Encoder-Roundtrips traten bei drei
  zusätzlichen Alphabetgrössen dieselben Fehler auf A und beim Kandidaten
  auf. Die Ursache war der Vergleich eines signierten Bytes mit 255
  in `ArithmeticEncoder.propagate_carry`. Ein temporärer Vergleich mit
  `(byte) 0xFF` bestätigte die Ursache. Die zusätzlichen Decoder-Fälle
  verwenden die direkte Grenzwertprüfung und die Schiefverteilungssequenz.
- Der vollständige Rohrecord-Vergleich bestätigte je 14'347'366 Records
  für Solothurn 2014 und 10'653'336 für Autzen. Beide Kandidaten und beide
  Workerzahlen hatten je Datei dieselbe SHA-256-Prüfsumme wie A seriell.
  In den PDAL-Vorvergleichen stimmten je Kandidat alle 28'695 bzw.
  106'534 ausgewählten Rohrecords in Reihenfolge überein. Statistik
  und CRS bestanden die bestehenden Prüfungen.
- Feste und adaptive Chunks, fehlende Chunktabelle, moderne Attribute
  inklusive Extra Bytes und NIR, breite Chunks sowie der LAS-Fallback
  bestanden für beide Kandidaten. Defekte Layer ergaben Exit 2, SIGTERM
  ergab Exit 143. Die Fehlerläufe hinterliessen keine LAS-, JSON- oder
  Teilausgaben.
- Nach der Hauptmatrix blieb die bisherige Tabellenbreite Standard.
  Beide Kandidaten blieben dort im vollständigen Vier-Worker-Lauf unter
  der ursprünglich vereinbarten 5-%-Grenze.
- Die zusätzliche [Ground-Crop-Bestätigung](experiments.md#ground-crop-bestätigung-für-2)
  für +2 bestand auf Solothurn 2019. Alle drei Java-Vorläufe und 28
  Messläufe lieferten bytegleiche LAS- und JSON-Ausgaben. PDAL 2.10.2
  bestätigte alle 195'738 ausgewählten Records, Statistik und CRS.
  Die vorgegebenen Grenzen für Wandzeit und Spitzen-RSS wurden bei
  beiden Workerzahlen eingehalten.
- Die gemessene +2-Variante ist im Produktcodec übernommen.
  `ArithmeticModel.init` ergänzt beim ersten Aufbau der Dekodiertabelle
  zwei Indexbits für 33, 64, 128 und 256 Symbole. Referenz für die
  dokumentierten Messwerte bleibt `2b10708`.
- Die Produktabnahme mit `./gradlew --offline clean check installDist`
  bestand mit 64 JUnit-Tests ohne Fehler oder übersprungene Tests. Die
  installierte Distribution stimmt bytegenau mit dem gemessenen +2-Build
  überein. Die E4-Fixtures bestätigten feste und adaptive Chunks, den
  seriellen Fallback, moderne Attribute, beschädigte Layer und SIGTERM.
  Die unabhängige Abnahme mit PDAL 2.10.2 verglich 353'342 Rohrecords in
  Dateireihenfolge ohne Abweichung. Statistik und CRS bestanden.
  SIGTERM ergab Exit 143 und entfernte die temporären Ausgaben.

## Polygonzuschnitt mit JTS

`filters.crop` unterstützt ein gültiges, nicht leeres 2D-WKT-Polygon
mit optionalen Löchern im CRS der Eingabedatei. JTS 1.20.0 liest und
validiert es einmal pro Pipeline-Ausführung. Polygon- und Lochränder
gehören zur Auswahl. `outside: true` invertiert genau diese Auswahl.

- `./gradlew --offline clean check installDist distZip` bestand mit
  74 JUnit-Fällen ohne Fehler oder übersprungene Tests. Die direkten
  Fälle prüfen Polygonkanten und Eckpunkte, Löcher, Inversion,
  fehlerhafte Einstellungen und bytegenaue Rohrecords über Batchgrenzen.
- `python3 tools/check-real-data.py /pfad/zu/pdal` bestand mit PDAL
  2.10.2. Polygon-Ground-Crop las 14'566'627 Punkte und schrieb
  143'244 Records. Die LAS- und JSON-Ausgaben mit einem und vier
  Java-Workern waren bytegleich. Gegen PDAL stimmten alle Rohrecords
  in Dateireihenfolge überein. CRS und Statistik bestanden die
  Vergleichsregeln. Die Java-Ausgabe behielt die übertragbaren VLRs.
- Ground-Crop, Statistik, Dezimierung, Solothurn 2014 und SIGTERM
  bestanden ebenfalls. Der Abbruch lieferte Exit 143 und räumte die
  temporären Ausgaben auf.

## LAS-Flags und Klassenzählungen

Die Pipeline stellt `Synthetic`, `KeyPoint`, `Withheld` und `Overlap`
aus den LAS-Rohrecords bereit. Die Flag-Auswertung und
`count: "Classification"` wurden am Polygonstand `a235c82` auf
macOS 15.8.1 (arm64) mit Temurin 25.0.3 und Gradle 9.9.1 geprüft.

- `./gradlew clean check installDist distZip` bestand mit 79 JUnit-Tests
  ohne Fehler oder übersprungene Tests. Die direkten Fälle prüfen
  Format 3 und 8, Klasse 255, Legacy-Overlap, Stagereihenfolge,
  leere Zählungen und das bestehende JSON ohne Klassenhäufigkeiten.
- `python3 tools/check-las-flags.py /pfad/zu/pdal` verglich
  Solothurn 2019 (14'566'627 gelesene, 195'738 ausgewählte Punkte)
  und Autzen (10'653'336 gelesene, 23'573 ausgewählte Punkte) mit
  PDAL 2.10.2 im Streaming-Modus mit vier Reader-Workern.
  Java-Läufe mit einem und vier Workern lieferten jeweils bytegleiche
  LAS- und JSON-Dateien. Alle Klassenhäufigkeiten vor und nach der
  Selektion und die ausgewählten Zeilen in Dateireihenfolge stimmten
  überein. Autzen selektierte 1'497 Punkte der Klasse 65 über
  `KeyPoint == 1`. Die numerische Statistik bestand innerhalb der
  bestehenden Toleranz.
- Die unabhängige Abnahme mit `tools/check-real-data.py` bestätigte
  496'586 vollständige Rohrecords, Statistik und CRS. SIGTERM ergab
  Exit 143 und entfernte die temporären Ausgaben.

## Bodenpunktraster als GeoTIFF

`writers.gdal` aggregiert die Z-Werte in einem expliziten XY-Gitter.
GeoTools 35.1 löst das horizontale CRS auf und erzeugt das GeoTIFF.
Die Rasterdaten werden nach verifiziertem Eingabeende in eine temporäre
Datei geschrieben. Ein gemeinsamer Abschluss-Callback für LAS und
GeoTIFF erhält die bestehende Metadatenpublikation und deren Rollback.

- `./gradlew clean check installDist distZip` bestand auf macOS 15.8.1
  (arm64) mit Temurin 25.0.3 und Gradle 9.9.1. Alle 86 JUnit-Fälle liefen
  ohne Fehler oder übersprungene Tests. Direkte Rastertests prüften
  GeoKeys und WKT, Zellgrenzen, leere Zellen und leere Selektionen,
  fehlendes CRS, die Gittergrenze, Abbruch und Metadaten-Rollback.
- `python3 tools/check-ground-grid.py /pfad/zu/pdal` bestand mit
  PDAL 2.10.2 und GDAL 3.13.3 aus der installierten und der entpackten
  JVM-Distribution mit `-Xmx512m`. Solothurn 2019 lieferte
  14'566'627 gelesene, 143'244 ausgewählte und 143'238 eingerasterte
  Punkte in 26'114 belegten Zellen. Sechs ausgewählte Punkte lagen
  auf der oberen oder rechten Gittergrenze. Java seriell und mit vier
  Workern lieferte identische Metadaten und Zellwerte.
- Alle 160'000 Float64-Werte (vier Bänder auf 200 × 200 Zellen)
  stimmten exakt mit PDAL überein. Der maximale absolute Fehler des
  mittleren Z-Werts je Zelle war 0 m. GDAL bestätigte EPSG:2056, die
  Nordausrichtung, die Bandreihenfolge und NoData.
- Der erneute Lauf von `tools/check-real-data.py` bestätigte 496'586
  vollständige LAS-Rohrecords, Statistik und CRS. SIGTERM ergab Exit
  143 und hinterliess keine Zieldateien oder temporären Dateien.
  Das JVM-ZIP mit GeoTools ist rund 24 MiB gross.

## Pipeline-API für die Rasterausgabe

PointCloud Java verwendet `writers.raster` für den GeoTIFF-Writer.
`writers.gdal` wird beim Parsen mit einem Migrationshinweis abgewiesen.
Das Raster-Abnahmeskript setzt den Stage-Typ für PDAL auf `writers.gdal`.

- `./gradlew clean check installDist` bestand auf macOS 15.8.1 (arm64)
  mit Temurin 25.0.3 und Gradle 9.9.1. Alle 87 JUnit-Fälle liefen ohne
  Fehler oder übersprungene Tests. Der neue Test prüft die Ablehnung
  des alten Typs vor dem Öffnen der Eingabe.
- `tools/check-ground-grid.py` bestätigte auf Solothurn 2019 mit
  PDAL 2.10.2 und GDAL 3.13.3 die Georeferenzierung, NoData und alle
  160'000 Zellwerte exakt. Java seriell und mit vier Workern lieferte
  identische Rasterwerte und JSON-Metadaten. Das Raster enthält
  143'238 Punkte in 26'114 belegten Zellen.
- `tools/check-real-data.py` bestätigte 496'586 LAS-Rohrecords,
  Statistik und CRS. SIGTERM ergab Exit 143 und entfernte die
  temporären Ausgaben.

## Lückenfüllung im Bodenpunktraster

`writers.raster` unterstützt `window_size: 1`. Nach vollständigem Lesen
der Eingabe ergänzt der Writer die drei Höhenbänder in den vorhandenen
Arrays. Die Punktzähler bestimmen die gemessenen Nachbarzellen. `count`
und `pointsBinned` zählen weiterhin tatsächliche Punkte. Der Standard
`window_size: 0` liefert die Höhenstatistik der Messzellen und NoData in
leeren Zellen.

- `./gradlew clean check installDist` bestand auf Basis von `6a508c0`
  auf macOS 15.8.1 (arm64) mit Temurin 25.0.3 und Gradle 9.9.1.
  Alle 96 JUnit-Fälle liefen ohne Fehler oder übersprungene Tests.
  Das 5×5-Fixture prüft die Nachbarschaft, die gleiche Gewichtung
  unterschiedlich dichter Messzellen, die unveränderten Punktzähler und
  NoData ausserhalb der Reichweite. Die Tests prüfen auch leere Raster,
  ungültige Optionen, Abbruch und explizites `window_size: 0`.
- `tools/check-ground-grid.py` bestätigte das Polygonraster und das
  rechteckige Raster mit und ohne Lückenfüllung gegen PDAL 2.10.2
  und GDAL 3.13.3. Java mit einem und vier Workern lieferte identische
  Rasterwerte und Metadaten. Der Rechteckausschnitt ergibt 195'738
  selektierte und 195'715 eingerasterte Punkte. Von 4'739 leeren Zellen
  wurden 4'491 gefüllt. 248 blieben NoData. Die 35'261 Messzellen und
  das Count-Band waren exakt gleich. Der maximale Höhenunterschied
  gegenüber PDAL betrug 3.41 × 10⁻¹³ m.
- Die fachliche Stichprobe blendete im ungefüllten Raster 363 gemessene
  Innenzellen im Abstand von je zehn Rasterzellen aus. Alle 363 liessen
  sich aus gemessenen Nachbarn rekonstruieren. Für `mean` ergaben sich
  MAE 0.0714 m und RMSE 0.1139 m gegenüber den ausgeblendeten Rasterwerten.
- `tools/check-real-data.py` bestätigte 496'586 LAS-Rohrecords,
  Statistik und CRS. SIGTERM ergab Exit 143 und entfernte die
  temporären Ausgaben.
- Der [Performancevergleich](experiments.md#bodenpunktraster-mit-lückenfüllung-gegen-pdal)
  verifizierte alle 64 Messläufe. Mit Füllung und vier Workern lag
  der Wandzeit-Median bei 2.045 s für Java und 2.942 s für PDAL.
  Die gepaarte Zusatzzeit durch die Füllung lag bei allen vier
  Kombinationen im Median bei höchstens 0.60 %. Die Einzelwerte
  streuten in beide Richtungen.

## Raster-Release mit Bandwahl, Intensity und Deflate

Der Writer verarbeitet `Z` oder `Intensity` und wählbare Bänder
in der Reihenfolge `min,max,mean,count,stdev`. Für die Populations-
standardabweichung verwendet er Welfords laufendes zweites Moment.
Die nichtnegativen Füllradien werden an der Rasterausdehnung begrenzt.
Nur Messzellen liefern Stützwerte. Der erste Stützwert geht exakt in
den gewichteten Mittelwert ein, auch bei grosser Distanz. Freies
NoData und optionales Deflate werden in den GeoTIFF-Tags gespeichert.
Kollisionen mit gültigen Werten werden vor dem Schreiben erkannt.

- `./gradlew --offline clean check installDist distZip` bestand auf
  Basis von `624ef94` mit Temurin 25.0.3, Gradle 9.9.1 und macOS arm64.
  Alle 110 JUnit-Fälle bestanden. Die neuen Fälle prüfen unter anderem
  Einzel- und Fünfbanddateien, Faktoren in zwei Ringen, grosse Radien,
  Intensität bis 65'535, den exakten Einzelspenderwert und NoData-
  Kollisionen bei Mess- und Füllwerten. Das ZIP bestand `unzip -tq`
  und beide Rasterbeispiele liefen aus der entpackten Distribution
  mit `-Xmx512m`.
- Die Raster-Realdatenabnahme mit PDAL 2.10.2 und GDAL 3.13.3 bestätigte
  das bestehende Polygon und die Fenster 0 und 1 im Rechteckausschnitt.
  Das Releaseprofil für `Z` mit Fenster 2 füllte 4'735 Zellen. Vier
  blieben NoData. Der maximale Z-Unterschied zu PDAL betrug
  4.55 × 10⁻¹³ m. Java mit und ohne Deflate lieferte identische
  Rasterwerte und Metadaten. Das einbandige Raster enthielt exakt
  dasselbe `mean`-Band. Für `Intensity` mit Fenster 5 wurden alle
  4'739 Lücken gefüllt, bei maximal 5.09 × 10⁻¹¹ Intensitätseinheiten
  Unterschied. Die Rasterwerte und Metadaten waren für einen und vier
  Java-Worker identisch.
- `tools/check-real-data.py` bestätigte 496'586 LAS-Rohrecords,
  Statistik und CRS. SIGTERM ergab Exit 143, ohne Ziel- oder
  temporäre Ausgaben zu hinterlassen.
- Der [Performancevergleich](experiments.md#raster-release-gegen-pdal)
  umfasst 96 verifizierte Läufe. Bei Fenster 2 mit Deflate und vier
  Workern lagen die Mediane bei 2.241 s für Java und 2.996 s für PDAL.
  Die Testdatei schrumpfte mit Deflate in Java um 48.86 %.
  In der separaten Schachbrettprobe mit einer Million Zellen und Fenster
  20 dauerten die Einzelläufe 3.773 s in Java und 1.741 s in PDAL.
  Beide füllten 500'000 Zellen und stimmten innerhalb (6.03 × 10⁻¹² m)
  überein.

## TIN-Terrainmodell aus Bodenpunkten

26. November 2026, Basis `9617f6b`, Apple M1 mit macOS 15.8.1,
Temurin 25.0.3+9, Gradle 9.9.1, PDAL 2.10.2/LazPerf und GDAL 3.13.3.
`writers.dtm` verwendet Tinfour 2.1.9 für das Delaunay-Netz, Rasterung
über begrenzte Zeilenabschnitte und die gemeinsame GeoTIFF-Ausgabe.
Die separate Double-Höhe vermeidet die Float-Konvertierung in Tinfour.
Eine einzige GeoTIFF-Transaktion bedient beide Raster-Writer. Das TIN
hält den selektierten Punktbestand im Speicher.
Die Diagnose der Stützflächen läuft als separates Abnahmewerkzeug.

- `./gradlew downloadTestData -Ptile=2019-urban` lud 52'116'409 Bytes
  der amtlichen Kachel. Der Waldtest verwendet die vorhandene
  Kachel `so-2019.laz` mit 83'247'722 Bytes.
- `./gradlew clean check installDist distZip` endete in 4 s mit Exit 0.
  Alle 122 JUnit-Fälle bestanden, davon 12 im neuen `TerrainWriterTest`.
  Der native XML-Report unter `build/test-results/test/` weist 0 Fehler,
  0 Fehlschläge, 0 übersprungene Fälle und 1.535 s aufsummierte
  Klassenlaufzeit aus. Die HTML-Ausgabe steht in
  `build/reports/tests/test/index.html`. Ein kollidierender lokaler
  Variablenname beim Umstellen des bisherigen Writers wurde vor dem
  vollständigen Lauf korrigiert.
- `unzip -tq build/distributions/pointcloud.zip` bestand.
  Die entpackte CLI verarbeitete beide LAZ-Dateien mit `-Xmx512m`
  und vier Workern. Beide Läufe endeten mit 40'000 gültigen Zellen.
  Die Einzelläufe der Paketprüfung dauerten 3.60 s für Stadt und
  3.37 s für Wald. Das ZIP umfasst 25'820'421 Bytes samt Tinfour und
  Lizenztexten.
- `python3 tools/check-real-data.py /pfad/zu/pdal` verglich 496'586
  vollständige LAS-Records in Ground-Crop, Polygon-Crop, Decimation und
  dem Solothurn-2014-Crop mit 0 Abweichungen. Die bestehenden Toleranzen
  für Statistik und CRS bestanden. Die Abbruchprobe endete mit
  Exit 143 nach 0.18 s ohne Ziel- oder temporäre Ausgabe.
- `python3 tools/check-ground-grid.py /pfad/zu/pdal --release`
  bestätigte die unveränderte Ausgabe für Z, Intensity, die Release-
  Füllfenster sowie Deflate. Die maximale Z-Abweichung lag bei
  4.55 × 10⁻¹³ m, die maximale Intensity-Abweichung bei 5.09 × 10⁻¹¹.
- `python3 tools/check-dtm.py /pfad/zu/pdal` bestätigte die gleichen
  NoData-Masken für Java und PDAL in vier Fällen mit je 40'000 Zellen.
  Die 728'768 selektierten Stadt- und 428'321 Waldpunkte ergeben
  728'751 beziehungsweise 428'313 eindeutige XY. Alle sechs Zellen
  mit einer Höhendifferenz über 1 µm wurden anhand der tatsächlichen
  TIN-Dreiecke beider Engines erklärt. Die Java-Metadaten und
  Float64-Werte bei einem und vier LAZ-Workern waren bitgleich.
  [Realdatenergebnisse und Schummerungen](experiments.md#tin-terrainmodell-gegen-pdal)
- Die CLI-Abbruchprobe beobachtete mit `jcmd Thread.print` den
  Tinfour-Masseneinbau der städtischen Bodenpunkte. SIGTERM beendete
  den Prozess nach 1.20 s mit Exit 143 ohne GeoTIFF, Metadaten oder
  temporäre Ausgabe.
- `tools/check-dtm.py --benchmark-csv .cache/dtm-pdal.csv` schloss
  alle 84 Prozessmessungen ab. Die 64 vollständigen LAZ-Läufe und
  20 Läufe auf vorbereiteter Boden-LAS wurden gegen die Abnahme geprüft.
  Bei vier Readern lagen die Wandzeitmediane bei 2.937 s / 2.646 s
  (Java / PDAL) in der Stadt und 2.916 s / 3.040 s im Wald.
  [Messaufbau, Streuung, CPU-Zeit, RSS und Durchsatz](experiments.md#tin-terrainmodell-gegen-pdal)

Die Produkt- und Referenzprüfungen sind abgeschlossen. Die
Einfachheitsprüfung bestätigte die direkte Stage samt gemeinsamem
GeoTIFF-Pfad. Der Referenzvergleich bleibt ausserhalb der Produkt-CLI.

## TIN-Performance-Experimente

26. November 2026, Basis `8021112`, MacBook Air M1, macOS 15.8.1,
Temurin 25.0.3+9, Gradle 9.9.1. Patches und Messskripte bleiben
in der Versuchsumgebung. Beide Solothurn-Pipelines verwenden je
40'000 Rasterzellen. Die kanonischen Boden-LAS-Dateien umfassen
728'768 und 428'321 selektierte Punkte. Referenz ist PDAL 2.10.2
mit LazPerf und GDAL 3.13.3.

- `./gradlew --offline clean check installDist` bestand für Basis,
  Kombination und Delaunator mit 122, 122 und 128 JUnit-Fällen.
  Die fokussierten Testläufe für Hash-Mischung, primitive Tabelle,
  Standard-TIN, Hilbert, gemeinsame Prüfung und GeoKeys bestanden mit
  13, 14, 12, 13, 13 und 48 Fällen. Keine Fehler, Fehlschläge oder
  übersprungenen Fälle. Die nativen
  Reports liegen je Worktree unter `build/test-results/test/`.
- Die zwei Tinfour-Varianten verarbeiteten bei `-Xmx512m` alle
  eine Million Stützpunkte und erzeugten 40'000 gegen eine
  analytische Ebene geprüfte Rasterwerte. SemiVirtual erreichte
  448 MiB, Standard 516 MiB Spitzen-RSS. Die Einzelprobe dauerte
  4.716 beziehungsweise 3.237 s.
- `experiments/check-tin.py` prüfte die Raster ohne Kantenlimit
  und mit 20 m in der Stadt sowie 5 m im Wald vor dem Zeitvergleich.
  Hashing, gemeinsame Stützpunktprüfung und direkte GeoKeys waren
  bitgleich. Standard-TIN und Hilbert blieben bei 1.14 × 10⁻¹³ m.
  Delaunator hat dieselben NoData-Masken und nachgewiesene alternative
  Diagonalen über kozyklischen XY für alle Differenzen über 1 µm.
- `experiments/tin-runtime.py` verglich frischen JVM-Start,
  weitere Pipeline-Läufe im selben Prozess und AOT. Die AOT-Nutzung
  ist mit `AOTMode=on` und dem Mapping im Startlog belegt.
- Das Kombinieren von primitiver Tabelle und gemeinsamem Rasterdurchlauf
  erforderte eine Auflösung im Importblock. Der kombinierte Build und
  der volle Rastervergleich gegen Basis und PDAL bestanden danach.
  Die Einfachheitsprüfung bestätigte den gemeinsamen Raster- und TIFF-Pfad
  sowie getrennt anwendbare Patches.
- Das Delaunator-Experiment bestand die vollständige LAZ-Abnahme
  mit je einem und vier Readern. Die unabhängige Prüfung
  `tools/check-real-data.py` im Delaunator-Worktree verglich
  496'586 LAS-Records ohne Abweichung, einschliesslich der Polygon-
  und Decimation-Pfade. In der DTM-Abbruchprobe während `RawDelaunay`
  ergab SIGTERM nach 0.38 s Exit 143 ohne GeoTIFF, JSON oder Part-Datei.
- Die Arbeit umfasst 480 A/B- und 56 Laufzeit-Einzelmessungen.
  Alle Messreihen und Prüfungen sind abgeschlossen. Auf originaler
  LAZ mit vier Readern liegen die Delaunator-Mediane bei 1.967 s
  und 2.202 s für Stadt und Wald. Die gepaarten PDAL-Mediane liegen
  bei 2.651 s und 2.998 s. Der Delaunator-Prototyp bleibt als Patch
  zur fachlichen und betrieblichen Bewertung verfügbar.
  [Messaufbau, Ergebnisse und Reproduktion](experiments.md#tin-optimierungsversuche)

## Bitgleiche DTM-Optimierungen

Basis `8021112`, Apple M1, macOS 15.8.1, Temurin 25.0.3+9,
Gradle 9.9.1, PDAL 2.10.2 und GDAL 3.13.3. Die primitive
XY-Tabelle erhält den ersten ausgewählten Z-Wert in
Dateireihenfolge. Der Rasterdurchlauf prüft die ursprünglichen
Vertex-Indizes vor dem Clip und dem Kantenlimit. Das kompakte
SemiVirtual-Netz bleibt der TIN-Kern. Eindeutige LV95-GeoKeys
werden nach Prüfung des Modelltyps und der VLR-Verweise direkt
geschrieben. WKT behält Vorrang. Der sonstige CRS-Pfad läuft
über GeoTools.

- `tools/check-real-data.py` verglich 496'586 vollständige
  LAS-Records mit PDAL. Alle Records und geprüften Attribute
  stimmten überein. SIGTERM ergab Exit 143 ohne Teilausgabe.
- `tools/check-ground-grid.py --release` bestätigte die
  Höhen-, Intensitäts-, NoData- und Deflate-Regressionsfälle.
  Die höchsten Abweichungen zu PDAL lagen bei 4.55 × 10⁻¹³ m
  für Z und 5.09 × 10⁻¹¹ für Intensity.
- Die Million-Punkte-Probe unter `-Xmx512m` ergab in beiden
  Builds 40'000 analytisch geprüfte Höhen. Das Spitzen-RSS
  sank in der Probe von 453 auf 320 MiB.
- `./gradlew --offline clean check installDist` endete in 4 s mit
  Exit 0. Alle 131 JUnit-Fälle bestanden, ohne Fehler, Fehlschläge
  oder übersprungene Fälle. XML unter `build/test-results/test/`.
- Stadt und Wald waren für Boden-LAS und vollständige LAZ mit
  1/4 Readern in sämtlichen Profilen mit und ohne Kantenlimit
  bitgleich. Die 96 Einzelmessungen umfassen acht gepaarte Runden.

| Gebiet | Eingabe | Reader | Median Basis / Produkt (s) | Gepaarter Gewinn | Peak-RSS Max (MiB) |
| --- | --- | ---: | ---: | ---: | ---: |
| Stadt | ground-las | 1 | 1.895 / 1.211 | 36.15% | 386 / 283 |
| Stadt | laz | 1 | 5.489 / 4.807 | 12.20% | 441 / 339 |
| Stadt | laz | 4 | 2.844 / 2.331 | 19.06% | 458 / 356 |
| Wald | ground-las | 1 | 1.367 / 0.866 | 36.48% | 341 / 244 |
| Wald | laz | 1 | 6.201 / 5.646 | 8.90% | 431 / 325 |
| Wald | laz | 4 | 2.837 / 2.325 | 17.19% | 447 / 344 |

[Einzelmessungen mit Wandzeit, CPU und RSS](measurements/tin-product-exact.csv)

## Vollständige DTM-Raster mit dem nächsten Bodenpunkt

`writers.dtm` unterstützt `fill_nodata: "nearest"`. Der JTS-Index
entsteht erst für Restzellen nach Freigabe des TIN. Er verwendet die
ersten Roh-XY-Vorkommen und die ursprünglichen Double-Höhen. Bestehende
TIN-Werte bleiben erhalten. Im vollständigen Modus wird kein NoData-Tag
geschrieben. Die JSON-Metadaten zählen die Ergänzungen separat.

- `./gradlew --offline clean check installDist` endete in 4 s mit
  Exit 0. Alle 142 JUnit-Fälle bestanden ohne Fehler, Fehlschlag oder
  übersprungenen Fall. Die native XML-Summe beträgt 1.747 s.
- `tools/check-dtm.py --complete` verglich Stadt und Wald auf dem M1
  mit PDAL 2.10.2 für das reine TIN. Die vollständigen Java-Ausgaben
  mit einem und vier LAZ-Workern waren bitgleich. Beide offenen Raster
  benötigten 0 Ergänzungen. Die Kantenlimits 20 m und 5 m ergaben
  7'227 Ergänzungen in der Stadt und 249 im Wald. Sämtliche Raster
  enthielten 40'000 endliche Höhen ohne NoData-Tag. Je 13 ergänzte
  Stadt- und Waldzellen stimmten mit der unabhängigen Distanzsuche
  überein. Alle vom TIN abgedeckten Werte blieben bitgleich.
- JFR mass die Restzellensuche einschliesslich Indexaufbau auf der
  vorbereiteten Boden-LAS mit Kantenlimit. Drei frische Durchläufe
  je Gebiet ergaben Mediane von 471 ms in der Stadt und 250 ms im
  Wald. Messung mit `experiments/tin-runtime.py --phase-cli ... --fill-nearest`.
- Die zusätzliche Speicherprobe mit einer Million Stützpunkten und
  einem Kantenlimit von 0.1 m ergänzte alle 40'000 Zellen. Der
  Einzellauf unter `-Xmx512m` benötigte 4.216 s und 393 MiB
  Spitzen-RSS. `tools/check-real-data.py` bestätigte erneut die
  496'586 vollständigen LAS-Records und den Abbruch ohne Teilausgabe.

## Delaunator als Kandidat für vollständige DTM-Raster

26. November 2026, Apple M1 mit 16 GiB, macOS 15.8.1 arm64,
Temurin 25.0.3+9, PDAL 2.10.2 und GDAL 3.13.3. Der Kandidat
auf `codex/tin-delaunator-candidate` baut auf `aad5f70` auf.
Der Ganzzahlpfad ist auf gleiche positive XY-Skalen und je
35'000 rohe Koordinateneinheiten Spannweite begrenzt. Der
Orientierungs- und der Umkreistest arbeiten in diesem Bereich
exakt mit Ganzzahlen. Für übrige Eingaben übernimmt SemiVirtual-Tinfour.

- `./gradlew --offline clean check installDist` endete in 3 s
  mit Exit 0. 149 JUnit-Fälle bestanden. Keine Fehler,
  Fehlschläge oder übersprungenen Fälle. Summe der XML-Suitezeiten
  1.679 s, Berichte unter `build/test-results/test/`.
- Die JVM-Distribution enthält den ISC-Lizenztext. Der Download
  mit `./gradlew --offline downloadTestData -Ptile=2019-height`
  bestätigte die 79'958'069 Bytes der amtlichen Höhenkachel.
- `tools/check-real-data.py` bestätigte erneut 496'586 vollständige
  LAS-Records und SIGTERM mit Exit 143 ohne Teilausgabe.
- Die erste Prüfung der vorbereiteten Boden-LAS verglich dieselben
  Statistikwerte an verschiedenen Stage-Positionen. Der Prüfer
  berücksichtigt die ausgelagerten Crop- und Expression-Stages.
  Für den Autzen-Referenzlauf liest der Prüfer EPSG:2992 aus der
  horizontalen Komponente des zusammengesetzten PDAL-CRS.
- Der Realdatenlauf mit `--mode both --complete --extended
  --allow-mesh-differences --include-pdal` endete mit Exit 0.
  30 Ergebnisse aus der vorherigen, ungemessenen Abnahme wurden bei
  gleichen CLIs und Eingaben erneut geprüft. Die Zeitmessungen
  starteten 189 frische Prozesse und prüften deren Resultate.
- Alle fünf Profile lieferten in beiden Java-Versionen und bei beiden
  Kantenlimit-Einstellungen vollständige Raster. Der Tinfour-Fallback im
  breiten Wald war bitgleich. Je 13 Stichproben pro Profil mit Restzellen
  bestätigten die Ersatzhöhen für beide Java-Versionen. Die abweichenden
  Höhen zum Produkt und zu PDAL wurden aus den Stützdreiecken reproduziert.
- Bei vier LAZ-Readern gewann der Kandidat gepaart 14.16 % in der Stadt
  und 9.10 % im Wald. Der Fallback verlor 0.46 % und blieb innerhalb der
  2-%-Grenze. Die ergänzten Zellen blieben in allen Profilen gleich.
  [Vollständige Messwerte und separate Restzellensuche](experiments.md#delaunator-kandidat-für-vollständige-dtm-raster)

Einfachheitsprüfung mit `simplify-change`. Die Engine-Grenze
liegt direkt im Terrain-Writer. Deduplizierung, Rasterisierung,
Restzellensuche und GeoTIFF-Ausgabe bleiben gemeinsam. Die
Backend- und Dreiecksdiagnose liegt im vorhandenen Abnahmewerkzeug.
