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
