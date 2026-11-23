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
