# Codex-Auftrag: PointCloud Java – eine moderne LiDAR-Engine in Java

**Empfohlener Repositoryname:** `pointcloud-java`  
**Produktname:** PointCloud Java  
**CLI:** `pointcloud`  
**Stand des Auftrags:** 23. November 2026, überarbeitete Fassung mit verbindlichen Etappenabnahmen und vorgelagertem AGENTS-/Skills-Bootstrap

## 1. Auftrag und Zielbild

Implementiere eine eigenständige, brauchbare Pointcloud-Engine in Java. Sie soll LAS- und LAZ-Dateien mit begrenztem Speicher verarbeiten und eine klar definierte Teilmenge von PDAL-JSON-Pipelines ausführen können. Verwende **Gradle mit Groovy DSL**.

Der Auftrag umfasst die tatsächliche Implementierung, Tests, Realdatenabnahme, Performance-Experimente und Dokumentation. Ein Architekturpapier, ein Gerüst mit Platzhaltern oder eine Sammlung isolierter Benchmarks erfüllt ihn nicht.

Das verbindliche Endziel ist:

> Eine vollständige Solothurner LAZ-Kachel in reinem Java lesen, räumlich und nach Attributen filtern, Statistiken berechnen und eine gültige LAS-Datei schreiben. Dieselbe Pipeline muss auch mit einem festgehaltenen PDAL-Referenzstand ausführbar sein und fachlich übereinstimmende Ergebnisse liefern.

Daneben untersuchst du systematisch, ob moderne Java-Techniken für diesen konkreten Anwendungsfall Vorteile bringen. Ein negatives Experiment ist ein ordentliches Ergebnis. Die Produktqualität hängt nicht davon ab, dass SIMD, GPU oder Native Image gewinnen.

### 1.1 Verbindliches Produkt und verbindliche Untersuchungen

| Bereich | Verbindliches Ergebnis |
| --- | --- |
| Produkt | Java-25-Bibliothek und CLI, LAS/LAZ-Eingabe, LAS-Ausgabe, definierte Pipeline-Teilmenge, begrenzter Speicher, verständliche Fehler |
| Codec | Benötigter `laszip4j`-Quellcode nach Untersuchung im eigenen Repository; keine externe `laszip4j`-Abhängigkeit im endgültigen Produkt |
| Daten | Vollständige Verarbeitung der Pflichtkachel; ergänzende Abdeckung älterer und moderner Punktformate mit wenigen Dateien |
| Referenz | Vergleich mit PDAL und einer tatsächlich unabhängigen Dekodierung; kein reiner Selbstvergleich zweier Adapter um denselben Java-Port |
| Experimente | Batchgrössen/Allokationen, Vector API, begrenzte Parallelität, LAZ-Chunkparallelität, MemorySegment/I/O, JDK-Vergleich, AOT, GraalVM Native Image, TornadoVM |
| Ergebnis jedes Experiments | Vollständig durchgeführte Untersuchung mit nachvollziehbarer Entscheidung; ein Blocker ist ein offener Arbeitsstatus und keine erfolgreiche Etappenabnahme |
| Lieferung | Quellcode, lokale Commits je Etappe, normale JVM-Distribution, Beispiele, Tests, Messdaten und ehrlicher Abschlussbericht |

### 1.2 Arbeitsweise

Arbeite zuerst **B0**, anschliessend **P0–P11**, vollständig autark und in genau dieser Reihenfolge ab. **Jede Etappe muss zwingend erfolgreich abgeschlossen sein, bevor die nächste begonnen wird.** Das gilt auch für vorgezogene Recherche, Downloads, Implementierung und Subagent-Aufträge einer späteren Etappe.

Eine Etappe ist erst erfolgreich abgeschlossen, wenn alle folgenden Bedingungen erfüllt sind:

1. Ihr verpflichtender Arbeitsumfang ist umgesetzt; es gibt keine ausgelagerten Restarbeiten, die zur aktuellen Etappe gehören.
2. Alle vorgeschriebenen Abnahmekriterien sind erfüllt und die erforderlichen Prüfungen tatsächlich erfolgreich durchgeführt.
3. Code, Tests, Struktur und Build sind auf unnötige Komplexität geprüft und gegebenenfalls vereinfacht.
4. Ergebnisse, Testmetriken, Entscheidungen und zulässige Umfangsgrenzen sind nachvollziehbar dokumentiert.
5. Die zugehörigen Änderungen sind committed. Ein Commit allein ist kein Nachweis einer erfolgreichen Abnahme.

Bei einem Fehler bleibst du in derselben Etappe, behebst die Ursache selbständig und wiederholst die durch die Korrektur betroffenen notwendigen Prüfungen. Pflichtprüfungen nicht ausblenden, deaktivieren, zu optionalen Prüfungen erklären oder durch schwächere Kriterien ersetzen. Keine Assertions abschwächen, um einen grünen Build zu erhalten. Keine Folgeetappe beginnen, solange die aktuelle Abnahme offen, fehlgeschlagen oder blockiert ist.

**Keine Rückfragen, Zwischenfreigaben oder Auswahlaufforderungen an den Auftraggeber für die normale Umsetzung.** Triff technische Routineentscheidungen innerhalb dieses Auftrags selbst, dokumentiere ihre Gründe, behebe Fehler und führe die nächste Etappe nach bestandener Abnahme automatisch aus. Das gilt auch für die bereits erlaubten gezielten Installationen. Fortschrittsmeldungen sind erlaubt, aber keine Voraussetzung für das Weiterarbeiten. Nach einer Kontextunterbrechung an der letzten noch nicht erfolgreich abgenommenen Etappe weiterarbeiten; bereits gültige Ergebnisse erhalten.

Ein korrekt ausgeführtes Experiment darf belegen, dass eine Variante langsamer ist oder keinen praktischen Nutzen hat. Das kann seine Untersuchungsaufgabe erfolgreich abschliessen. Eine fehlende Referenzinstallation, ein nicht gebautes Native Binary, nicht vorhandene GPU-Ausführung oder eine ausgelassene Pflichtmessung sind dagegen kein negatives Messergebnis und kein bestandenes Abnahmekriterium. Die ausdrücklich als Fähigkeitsprüfung definierte LAZ-Writer-Untersuchung bleibt davon zu unterscheiden: Ihr belegtes Ergebnis darf sein, dass kein verwendbarer Encoder vorhanden ist.

Wenn eine notwendige externe Voraussetzung trotz geeigneter zulässiger eigener Massnahmen objektiv nicht verfügbar gemacht werden kann, sichere den Arbeitsstand und dokumentiere Ursache, Abhilfeversuche sowie den konkret fehlenden Nachweis. Bleibe an dieser Etappe stehen; beginne keine spätere und behaupte keinen vollständigen Abschluss. Routineprobleme dürfen nicht als solcher Ausnahmefall deklariert werden. Stelle dabei keine neue Freigabeschleife für die reguläre Arbeit her.

**OpenCode, DeepSeek oder vergleichbare externe Reviews sind momentan weder vorgesehen noch Abnahmevoraussetzung.** Führe die eigene technische Prüfung und die fachlichen Produkttests durch. Gezielte Subagent-Gegenprüfungen sind erlaubt, wenn sie einen konkreten Befund klären. Sie sind keine Pflichtzeremonie. Parallele Unteraufgaben sind ausschliesslich innerhalb der aktuell zulässigen Etappe erlaubt und dürfen keine konkurrierenden Änderungen an denselben Dateien verursachen. Performance-Messungen laufen ohne konkurrierende Builds, Tests oder andere Benchmarks. Der unabhängige PDAL-Vergleich ist ein Produkttest und bleibt trotz entfallender externer Reviews verpflichtend.

Lies vorhandene `AGENTS.md`-Dateien und beachte bestehende Repositoryregeln. Prüfe Branch, Index und Arbeitsbaum, verwende einen passenden Arbeitsbranch und erhalte fremde Änderungen. **Der Auftraggeber erstellt das Repository.** Arbeite im bereitgestellten Repository; lege kein anderes GitHub-Repository an. Veröffentlichung, Release, Push und Merge sind nicht Teil dieses Auftrags.

### 1.3 B0: AGENTS.md und drei kleine Skills zuerst

Bevor Buildlogik, Produktcode, Codecversuche oder Benchmarks implementiert werden, lege die Projektanweisungen und genau drei kleine projektspezifische Skills an beziehungsweise ergänze bereits vorhandene passende Dateien. Prüfe sie auf Widersprüche und committe diesen Bootstrap separat. Erst nach erfolgreichem B0 darf P0 starten.

Verwende die in [Q16] und [Q17] genannten Repositories als Inspiration. Übernimm ihre Prinzipien zu Einfachheit, direkter Testausführung und nachvollziehbaren Ergebnissen. Ihre Modulstruktur, Publikationsregeln und projektspezifischen Referenzsuites sind keine Vorlage für zusätzliche Pointcloud-Infrastruktur. Die dortige externe Reviewpflicht wird für diesen Auftrag ausdrücklich nicht übernommen.

**Verbindlicher Inhalt der root-`AGENTS.md`:**

- Produktziel, klare Umfangsgrenzen und kanonischer Verweis auf diesen Auftrag im Repository.
- Java 25, Gradle Groovy, lokaler Codecquellcode und ein normaler CPU-Build ohne Experimentvoraussetzungen.
- KISS und Ockham's Razor als Entwurfsregeln: zuerst vorhandene Lösungen prüfen; direkte verständliche Implementierung und Standardwerkzeuge bevorzugen.
- Jede neue Klasse, Schnittstelle, Modulgrenze, Abhängigkeit oder Buildaufgabe braucht einen aktuellen Verbraucher oder eine konkret notwendige Grenze. Keine Architektur für bloss denkbare spätere Anwendungsfälle.
- Die verbindliche Reihenfolge B0 → P0 → … → P11 samt erfolgreicher Abnahme, Dokumentation und Commit vor Fortsetzung; vollständig autarke Ausführung.
- Die Testphilosophie aus Abschnitt 8: echte Produktresultate und Fehler prüfen, keine Tests von Tests, keine eigene Test-/Gateverwaltung, keine geschwächten Assertions.
- Präzision, Reihenfolge, Attribute, Metadaten, Fehlerweitergabe, Ressourcen und Datenbudget als unverzichtbare Produkteigenschaften.
- Dokumentations- und Metrikpflicht nach Abschnitt 8.4; vorhandene gültige Prüfungen wiederverwenden, wenn die relevanten Eingaben unverändert sind.
- Explizite lokale Pfade und Auslöser der drei folgenden Skills. Nach B0 müssen sie von Beginn an für die passenden Arbeiten angewendet werden.

Halte `AGENTS.md` kurz und verbindlich. Verweise für Details auf die kanonischen Fach-/Abnahmedokumente; kopiere nicht den gesamten Auftrag hinein. Dokumentiere tatsächliche Gradle-Aufgaben, sobald sie existieren, und kennzeichne vorher notwendige geplante Befehle als geplant.

| Lokaler Skill | Auslöser | Verbindlicher Inhalt |
| --- | --- | --- |
| `.agents/skills/simplify-change/SKILL.md` | Vor einer neuen Abstraktion, grösseren Strukturänderung und vor jeder Etappenabnahme | Tatsächlichen Bedarf/Verbraucher benennen; mit der einfachsten direkten Lösung vergleichen; unnötige Wrapper, Factories, Doppelmodelle, Kompatibilitätspfade und eigene Build-/Testmechanismen entfernen; notwendige Produktverträge und echte Regressionstests erhalten |
| `.agents/skills/pointcloud-correctness/SKILL.md` | Änderungen an Codec, LAS/LAZ-I/O, Batchlayout, Pipeline-Semantik, Writer oder Fehlerpfaden | Betroffenen Vertrag und Risiken feststellen; kleinste geeignete Fixture/Produkttests wählen; Raw-/World-Koordinaten, Flags, Extra Bytes, Metadaten, Reihenfolge, EOF und Ressourcen berücksichtigen; erforderliche unabhängige Referenzprüfung durchführen; tatsächliche Resultate und Grenzen dokumentieren |
| `.agents/skills/pointcloud-performance/SKILL.md` | Performanceänderungen sowie SIMD-, Speicher-, Parallelitäts-, JDK-, AOT-, Native- und GPU-Versuche | Hypothese und Vergleich begrenzen; Korrektheit vor Geschwindigkeit; Kernel, Decoder und End-to-End unterscheiden; Runtime/Hardware/Warm-up festhalten; keine konkurrierenden Messungen; Metriken und Streuung dokumentieren; begründete Übernahme-/Verwerfungsentscheidung und unnötigen Versuchscode bereinigen |

Jede `SKILL.md` erhält ein gültiges YAML-Frontmatter mit `name` und einer konkreten `description`, die erklärt, wann der Skill anzuwenden ist. Ordner- und Skillname stimmen überein. Danach folgen wenige direkte Arbeitsschritte und Verweise auf die kanonischen Projektverträge. Keine Platzhalter und keine wiederholten vollständigen Test-/Etappenregeln in jedem Skill.

Dies sind **versionierte Repository-Skills**. Keine persönliche globale Installation, keine neue Skill-Plattform, keine Generatoren und keine eigene Validierungssoftware dafür aufbauen. Die Anweisungen selbst inhaltlich lesen und prüfen. In B0 keine automatisierten Tests erfinden, die nur Vorhandensein, Text oder Anzahl von `AGENTS.md`, Skills oder anderen Tests bestätigen. Ihre praktische Anwendung beginnt mit P0; konkrete Mängel anschliessend direkt korrigieren.

### 1.4 Einfachheit ist ein Abnahmekriterium

Code, Tests, Build und Struktur müssen möglichst einfach und transparent bleiben. Das ist bei jeder Etappe zu beurteilen, nicht erst beim Schlussrefactoring.

- Suche zuerst nach vorhandenem Code. Verwende Standardfunktionen von Java, Gradle, JUnit und JMH, bevor du einen eigenen Mechanismus baust.
- Keine trivialen Delegationsklassen, unnötigen Einmal-Factories, parallelen Modelle, spekulativen Erweiterungspunkte, Service-Locator oder reflexionsbasierten Registrierungen.
- Keine Legacy-Aliase, Migrationswerkzeuge oder Kompatibilitätsschichten für unveröffentlichte interne Zwischenstände. Ersetzte Mechanismen mit Aufrufern, Tests und Dokumentation im selben Änderungssatz entfernen.
- Keine Hashes, Fingerprints, Belegketten oder zusätzlicher Statusformate ohne konkret nachgewiesenen Bedarf. Git, native Testberichte und eine kleine Ergebnisdokumentation genügen für die Nachvollziehbarkeit dieses Auftrags.
- Keine Klassen-, Zeilen- oder Coverage-Zahl als Ersatz für Verständnis. Kleine, klare Aufteilung ist erwünscht; künstliche Zerstückelung ebenso vermeiden wie eine unübersichtliche Sammelklasse.
- Bei einem grösseren Entwurf kurz festhalten: Welche einfachere Variante wurde geprüft, und welche konkrete Anforderung macht die gewählte zusätzliche Struktur nötig? Für triviale Änderungen keinen eigenen Entscheidungsbericht erzeugen.

## 2. Bewusste Grenzen

Implementiere einen überschaubaren produktiven Kern. Der Umfang dieses Auftrags endet vor:

- vollständiger PDAL-Kompatibilität, Pipelinegraphen mit mehreren Ein-/Ausgängen und Plugins;
- COPC-/EPT-Raumsuche, einem HTTP-Range-Reader für die Engine und einem Cloudkatalog;
- CRS-Reprojektion, PROJ, eine GDAL-Laufzeitabhängigkeit und Rasterverfahren ausserhalb des in Abschnitt 6.2 definierten Gitters;
- Bodenklassifikation, Normalen, k-NN, Octrees, allgemeine Mesh-Verarbeitung und räumlichen Datenbanken;
- GUI, Webserver, Spring Boot, REST-API und verteiltem Rechnen;
- vollständiger Neuentwicklung eines LAZ-Encoders;
- einem allgemeinen Backend-/Accelerator-Framework und automatischer Hardwareoptimierung.

Eine affine Rechenoperation in einem Performance-Experiment ist keine CRS-Reprojektion. Das sequenzielle Lesen einer lokalen COPC-Datei belegt keine COPC-Unterstützung. Solche Fähigkeiten nicht aus Klassennamen oder zufällig erfolgreichen Dateien ableiten.

Neue Funktionen ausserhalb dieser Grenzen nur als kurzen Folgepunkt notieren. Den Auftrag nicht während der Umsetzung beliebig erweitern.

## 3. Technische Leitplanken

### 3.1 Java, Build und Abhängigkeiten

1. **Produktbaseline Java 25**, ohne Preview-Features und ohne Inkubator-Modul im Standardprodukt. Moderne stabile Sprachmittel sind willkommen, wenn sie den Code vereinfachen.
2. Gradle Wrapper, `build.gradle` und `settings.gradle` in **Groovy DSL**. Eine konkrete passende stabile Gradle-Version festhalten. Keine zweite Maven-/JBang-Buildstrecke und kein `buildSrc` ohne konkreten Bedarf.
3. Installierte JDKs zuerst unter `~/.sdkman/candidates/java` und den üblichen lokalen Installationsorten prüfen. Das tatsächliche Home-Verzeichnis verwenden; `~./sdkman` aus der Beschreibung ist kein wörtlicher Pfad.
4. Für Gradle, Java-Compiler, Tests, Benchmarks und `native-image` die tatsächlich verwendeten Versionen und Architekturen feststellen. SDKMAN-`current` oder der gerade aktive Shell-Java-Befehl genügen nicht als Nachweis.
5. Java Toolchains nutzen. Globale SDKMAN-Defaults nicht für einen einzelnen Versuch umstellen. Zusätzliche JDKs nur installieren, wenn eine konkrete Versuchsfrage damit beantwortet wird. Vorhandene Installationen bevorzugen.
6. CPU-Produktpfad ohne JNI, native PDAL-/LASzip-Bibliothek oder Aufruf externer Konverter. PDAL ist ein Testwerkzeug, keine Laufzeitabhängigkeit.
7. Wenige zweckmässige Abhängigkeiten sind erlaubt, etwa eine kleine etablierte JSON-Bibliothek, JUnit und JMH. JSON nicht mit selbstgebastelten String-Splits parsen. Für die Pipeline genügt ein einfacher JSON-Baum; kein umfassendes Mapping-/DI-Framework einführen.
8. Test-, Benchmark-, Referenz- und GPU-Abhängigkeiten nicht in die normale Laufzeitdistribution ziehen. Versionen festhalten; kein `+`, `latest` oder ungeprüftes Snapshot-Upgrade.
9. Normales `./gradlew clean check installDist` funktioniert ohne GPU, TornadoVM, Native Image, besondere JVM-Flags und Testdaten-Download. Ein initialer Bezug von Build-Abhängigkeiten ist davon zu unterscheiden. Mit vorbereitetem Cache müssen die normalen Tests offline laufen.

Die Vector API ist bereits in Java 25 verfügbar. Ein neueres JDK ist für den ersten SIMD-Versuch nicht nötig. Die Gradle-Kompatibilität der tatsächlich gewählten JDKs prüfen, statt sie vorauszusetzen. [Q8, Q9]

### 3.2 Kleine Projektstruktur

Eine normale Gradle-Anwendung mit Bibliotheks-API genügt als Hauptprojekt. Für den übernommenen Codec ist genau ein lokaler Quellbereich beziehungsweise ein kleines Gradle-Unterprojekt zulässig. Für Benchmarks und Experimente reicht ein zusätzlicher, ausdrücklich aktivierter Bereich.

Mögliche Aufteilung:

| Bereich | Inhalt |
| --- | --- |
| `AGENTS.md` und `.agents/skills/` | Kurze verbindliche Projektregeln und die drei Skills aus B0 |
| Hauptprojekt `src/main/java` | Engine, eigene öffentliche API, Pipelineparser und CLI in wenigen fachlichen Packages |
| `codec/` | Übernommener `laszip4j`-Quellcode, Herkunft, Originalheader und Lizenztexte |
| `src/test` | Kleine lokale Fixtures und normale Tests |
| `experiments/` | JMH, alternative Kernel und explizite GPU-/Speicherversuche; nicht Teil des normalen Builds |
| `examples/` | Tatsächlich ausführbare Pipeline-Dateien |
| `docs/` | Architektur, Unterstützung, Herkunft, Realdatenabnahme und zusammengefasste Messresultate |

Ein lokales `implementation project(':codec')` ist mit dem Auftrag vereinbar: Der Quellcode liegt dabei im eigenen Repository. Unzulässig ist eine externe Maven-/JAR-Abhängigkeit auf `laszip4j` im finalen Produkt oder ein Build, der den Codec erst aus GitHub nachlädt.

Vermeide eine eigene Klasse oder ein Interface für jede technische Kleinigkeit. Schnittstellen nur an tatsächlich benötigten Grenzen. Keine reflektive Stage-Registry, keine Plugin-Erkennung und keine zusätzlichen Konfigurationsebenen. Ein expliziter `switch` über die wenigen Stage-Typen ist ausreichend.

### 3.3 Datenhaltung und Präzision

Verarbeite wiederverwendbare **Batches mit primitiven Arrays**. Keine `List<Point>` mit einem dauerhaft gehaltenen Objekt je Punkt und keine `Map<String,Object>` je Messpunkt.

Wähle nach der ersten Messung ein konkretes Layout. Zusammenhängende Spalten für die häufig verarbeiteten Felder sind sinnvoll. Nicht benötigte Attribute müssen trotzdem erhalten bleiben: entweder in vollständigen primitiven Spalten oder in einem begrenzten zusammenhängenden Rohdatenbereich pro Batch. Eine kleine kontrollierte Duplizierung ist akzeptabel, wenn sie eine wesentlich einfachere verlustfreie Verarbeitung ermöglicht. Keine austauschbare Storage-Abstraktion vorab entwickeln.

Verbindlich:

- LAS-XYZ als rohe signed 32-Bit-Integer samt Scale und Offset verstehen und erhalten.
- Fachliche Koordinaten als `double` berechnen: `raw * scale + offset`.
- `LASPoint.getX()/getY()/getZ()` aus `laszip4j` liefern Rohwerte. Eine Zuweisung an `double` führt noch keine Skalierung aus. [Q3]
- Globale LV95-Koordinaten nicht in `float` speichern. GPU-Experimente mit lokalen Float-Koordinaten haben einen separaten Genauigkeitsnachweis.
- Bei reinen Auswahlfiltern keine erneute Quantisierung der unveränderten Koordinaten durchführen.
- Unsigned-Felder korrekt behandeln, insbesondere Intensität/RGB/NIR und Klassifikations-/Return-Bits. Klassenkennung und Klassifikationsflags nicht vermischen.
- Anzahl Punkte, Dateioffsets und globale Positionszähler mit `long` führen. Die Grenzen des übernommenen Seek-Codes trotzdem ausdrücklich prüfen; `long` in der eigenen API behebt keinen internen 32-Bit-Cast.
- Lebensdauer und Besitz eines Batches definieren. Ein Reader darf Daten wiederverwenden; ein Worker oder Writer darf dann keine bereits überschriebenen Arrays behalten.
- Normale speicherintensive Arbeit auf die Zahl gleichzeitig offener Batches begrenzen. Keine Warteschlange proportional zur Gesamtpunktzahl.
- Für Floating-Point-Berechnungen die Operationsreihenfolge beachten. FMA oder GPU-`fastmath` nicht stillschweigend einführen, wenn dadurch Filterentscheidungen an Grenzen wechseln.

## 4. Realdaten und Speicherbudget

### 4.1 Pflichtdatei

Die folgende Datei muss vollständig funktionieren:

<https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/2594000_1227500.ch.so.agi.lidar_2019.pointcloud.laz>

Für diesen Auftrag wurden am 23. November 2026 nur HTTP-Bereichsabrufe für Header und VLRs ausgeführt. **Es wurde keine vollständige Kachel heruntergeladen oder dekodiert.** Die folgenden Werte sind Headerangaben und eine HTTP-Dateigrösse, keine vorweggenommene Realdatenabnahme:

| Eigenschaft | Beobachteter Wert |
| --- | --- |
| Dateigrösse | 83'247'722 Bytes |
| LAS-Version / Punktformat | 1.2 / 3 |
| Punktzahl laut Header | 14'566'627 |
| Punktdatensatzlänge | 34 Bytes |
| XYZ-Scale | jeweils 0.01 |
| XYZ-Offset | jeweils 0.0 |
| Beginn der Punktdaten | Byte 615 |
| LASzip-VLR | Kompressor 2, Coder 0, deklarierte Chunkgrösse 50'000 |
| LASzip-Items | `(6,20,2)`, `(7,8,2)`, `(8,6,2)` |
| Weitere beobachtete VLRs | `LASF_Projection`, Record IDs 34735, 34736, 34737 |

Die Chunkinformation macht diese Datei zu einem konkreten Kandidaten für den späteren Parallelversuch. Sie beweist noch nicht, dass die Java-API unabhängige Chunkstarts korrekt zugänglich macht.

Die Datei nicht nur bis zum ersten Batch lesen. Headerpunktzahl, tatsächlich dekodierte Punkte, Endzustand und nachfolgende Ausgabe gegeneinander prüfen. Die Header-Bounds nicht ungeprüft als exakt berechnete Punktstatistik ausgeben.

### 4.2 Gezielte Ergänzungen

Die Screenshots zeigen drei Datenbestände. Verwende sie selektiv:

| Datensatz | Datei / Verzeichnis | Vorprüfung und Verwendung |
| --- | --- | --- |
| Solothurn 2019 | Pflichtdatei oben | Verbindlicher vollständiger Integrationstest |
| Solothurn 2014 | [2592000_1228000.ch.so.agi.lidar_2014.pointcloud.laz](https://files.geo.so.ch/ch.so.agi.lidar_2014.pointcloud/aktuell/2592000_1228000.ch.so.agi.lidar_2014.pointcloud.laz) | Header: LAS 1.2, Format 1, 14'347'366 Punkte, 74'261'841 Bytes. Verbindliche zweite Realdatenvariante nach funktionierender Pflichtkachel |
| Basel-Landschaft 2018 | [2590500_1253500.ch.bl.agi.lidar_2018.pointcloud.laz](https://files.geo.so.ch/ch.bl.agi.lidar_2018.pointcloud/aktuell/2590500_1253500.ch.bl.agi.lidar_2018.pointcloud.laz) | Header: LAS 1.2, Format 3, 6'797'875 Punkte, 25'716'059 Bytes. Nur herunterladen, wenn ein zusätzlicher Test oder Vergleich daraus einen konkreten Nutzen hat |

Verzeichnisse zur gezielten Auswahl, nicht zum Spiegeln:

- <https://files.geo.so.ch/ch.so.agi.lidar_2019.pointcloud/aktuell/>
- <https://files.geo.so.ch/ch.so.agi.lidar_2014.pointcloud/aktuell/>
- <https://files.geo.so.ch/ch.bl.agi.lidar_2018.pointcloud/aktuell/>

Für LAS 1.4, zusätzliche Attribute und kleine Grenzfälle lokale Mini-Fixtures verwenden. Die bereits untersuchten grossen Kacheln ersetzen diese Abdeckung nicht.

### 4.3 Verbindliche Budgetregeln

1. Höchstens **drei vollständige Realdatenkacheln**, zusammen höchstens **250 MiB komprimierte Downloads**. Die drei genannten Dateien liegen zusammen bei rund 183.23 MB.
2. Mit der Pflichtkachel beginnen. Weitere Dateien erst laden, wenn sie für eine Etappe gebraucht werden. Keine rekursiven Downloads und keine automatische Übernahme der Upstream-Downloadtests.
3. Testdaten und temporäre Punktdateien zusammen standardmässig auf **1.5 GiB** begrenzen. Dieser Deckel betrifft Eingabekacheln, LAS-Ausgaben und Punktvergleichsdaten. Buildverzeichnisse, SDKs, AOT-Caches und Profilingdateien separat auf ihren Platzbedarf prüfen. Vor Downloads, vollständigen LAS-Ausgaben und Native-Builds freien Platz sowie erwartete Zusatzbelegung abschätzen. Mindestens 1 GiB freien Platz als Reserve erhalten. Platzprobleme selbständig durch gezielte Bereinigung eigener reproduzierbarer Zwischenprodukte oder eine platzsparende Ausführung lösen. Ein weiterhin nicht ausführbarer Pflichtversuch blockiert die aktuelle Etappenabnahme; die nächste Etappe beginnt nicht.
4. Den unkomprimierten Umfang aus Punktzahl, Recordlänge und Metadaten abschätzen. Eine vollständige LAS-Repräsentation der Pflichtdatei braucht allein für die Punktdatensätze ungefähr 495 MB. Nicht gleichzeitig mehrere solche Kopien anlegen.
5. Realdaten in einem ignorierten Projektcache, beispielsweise `.cache/test-data`. Einfache feste Aliasnamen wie `so-2019.laz` und `so-2014.laz` sind erlaubt. Herkunft, Originalname, Grösse und Abrufdatum in einer kleinen Datensatzübersicht festhalten.
6. Downloads explizit über eine dokumentierte Gradle-Aufgabe anfordern. Bestehende vollständige Dateien wiederverwenden; in eine temporäre Datei laden und erst nach erfolgreicher Prüfung umbenennen. HTTP-Fehler oder unvollständige Antworten nicht als vorhandene Testdaten behandeln.
7. Die Datenbeschaffung gehört zu den Entwicklungswerkzeugen. Die produktive CLI liest zunächst lokale Dateien. Kein allgemeiner Downloadmanager, kein eigenes Cache-Protokoll, keine umfangreiche Fingerprint-Infrastruktur.
8. Grosse Daten, JFR-Aufzeichnungen, AOT-Caches, Native-Buildverzeichnisse und vollständige PDAL-Ausgaben nicht committen. Kleine lizenzierte Fixtures und kompakte Messergebnisse dürfen ins Repository.
9. SDK-/Containerinstallationen haben ebenfalls einen Platzbedarf. Keine grosse Conda-Installation oder mehrere Containerimages nur für einen leichtgewichtigen Referenzvergleich aufbauen, wenn ein vorhandenes PDAL genügt.

## 5. Codec untersuchen und anschliessend integrieren

### 5.1 Geprüfter Ausgangspunkt

Untersuchter `laszip4j`-Stand:

`29a01a31607f1029f1330d1a09f19b17ea587005` vom 2. November 2026.

Repository: <https://github.com/mreutegg/laszip4j>

Die README beschreibt einen unvollständigen Java-Port von LASzip und Teilen von LAStools. LAS-Schreiben ist vorhanden. Mehrere komprimierte Writer, darunter `LASwriteItemCompressed_POINT10_v2`, sind ausdrücklich Dummies. Deshalb ist **LAS-Ausgabe verbindlich; LAZ-Ausgabe ist eine begrenzte Fähigkeitsprüfung**. [Q1, Q2]

Der Auftrag setzt nicht voraus, dass alle genannten Klassen und Versionen vollständig funktionieren. Nutze den festgehaltenen Stand für die erste Baseline oder einen nachvollziehbar begründeten neueren Stand. Dokumentiere die tatsächlich verwendete Revision.

### 5.2 Vorgehen

1. Zunächst unveränderten Upstream-Code in einem isolierten Versuch verwenden. Eine vorübergehende externe Abhängigkeit ist in dieser Untersuchungsphase erlaubt.
2. Pflichtdatei lesen und einen gültigen LAS-Ausschnitt schreiben. Hotspots, Allokationen, Ressourcenfreigabe, Fehlerweitergabe, Punktformate und Metadatenpfade untersuchen.
3. Öffentliche Komfort-API und Low-Level-Reader vergleichen. Keine Punkt-Wrapper dauerhaft sammeln. Der Adapter soll Werte unmittelbar in wiederverwendbare Batches übernehmen.
4. Danach den tatsächlich benötigten Java-Quellcode physisch ins Projekt übernehmen. Kein Submodule, kein zur Buildzeit heruntergeladenes Archiv und kein verbleibendes Upstream-JAR in der finalen Runtime.
5. Pakete, Algorithmen und Kernstruktur zunächst möglichst erhalten. Eine schmale eigene Adaptergrenze verhindert, dass Upstream-Typen Teil der öffentlichen Engine-API werden.
6. Die benötigte transitive Quellmenge aufnehmen. Keine ganze LAStools-CLI und keine fachfremden Reader bloss wegen einer bequemen Factory importieren. Bei Bedarf direkt an `LASreaderLAS`/`LASwriterLAS` anbinden. Keine zeitaufwendige Minimalisierung auf jede letzte Hilfsklasse erzwingen.
7. Import und notwendige Anpassungen getrennt nachvollziehbar committen. Entropiedecoder, Prädiktoren und Kontextmodelle nicht im Importcommit grossflächig umschreiben.
8. Den temporären externen Codec-Bezug anschliessend entfernen, auch aus Experiment-Builds. Vergleichsnachweise behalten; normale Versuche verwenden ab dann den integrierten Code.
9. Fehlerpfade des Low-Level-Codes in eindeutige Engine-Fehler übersetzen. `false` kann EOF oder Fehler bedeuten; stderr allein ist kein belastbarer Fehlervertrag.

### 5.3 Herkunft und Lizenzen

Die Lizenzangaben sind nicht durchgängig identisch: Die Root-Lizenz nennt LGPL 2.1, verschiedene Java-Dateien nennen LGPL 3 oder neuer, weitere Dateien tragen Apache-2.0-Header. [Q1, Q3]

Führe einen kurzen Herkunftsnachweis mit Repository, Commit, übernommenen Bereichen, lokalen Änderungen und den tatsächlich vorgefundenen Lizenzangaben. Erhalte Copyright- und Lizenzheader und lege die notwendigen Lizenztexte bei. Eine Paketumbenennung ändert die Lizenz nicht. Keine pauschale Deklaration, der gesamte übernommene Code sei MIT, Apache oder einheitlich LGPL 2.1.

Bei unklaren Angaben die konkrete betroffene Datei identifizieren und die Übernahme innerhalb der aktuellen Etappe klären oder durch eine zulässige, funktional ausreichende Alternative vermeiden. Andere Arbeiten derselben Etappe dürfen weiterlaufen; eine notwendige ungeklärte Übernahme blockiert deren Abschluss. Keine pauschale Lizenzfreigabe erfinden. Auch bei übernommenen Testdaten deren Herkunft und Nutzbarkeit festhalten. Vor einer späteren Distribution eines Native Binary die für die konkrete Kombination geltenden Bedingungen dokumentieren.

### 5.4 Verbindlicher Formatumfang

| Gruppe | Ziel in diesem Auftrag |
| --- | --- |
| LAS 1.2, Punktformate 0–3 | Lesen und LAS-Ausgabe; insbesondere Formate 1 und 3 mit den Realdaten |
| LAS 1.4, Punktformate 6–8 | Lesen und LAS-Ausgabe; Nachweis durch kleine Fixtures einschliesslich moderner Flags und Extra Bytes |
| LAZ für diese Formate | Dekodierung der konkret nachgewiesenen Codec-Varianten; genaue Matrix mit Item-/Codec-Versionen |
| Waveform-Formate 4/5/9/10 | In dieser Version vor Beginn der Verarbeitung verständlich zurückweisen |
| Weitere LAS-/LAZ-Versionen | Nur als unterstützt ausweisen, wenn tatsächlich implementiert und geprüft; sonst eindeutige Ablehnung |
| LAZ-Ausgabe | Keine Pflichtfunktion; Fähigkeitsprüfung, kein Portieren sämtlicher fehlender Encoder |

Die Formatzuordnung einer Upstream-Factory genügt nicht als Testnachweis. Für jedes als unterstützt ausgewiesene Format mindestens einen repräsentativen Reader-/Writer-Nachweis liefern. Fehlende Mini-Fixtures gezielt erzeugen, beispielsweise mit einem unabhängigen Werkzeug; keine weiteren grossen Kacheln dafür laden.

Solange komprimiertes Schreiben nicht implementiert und abgenommen ist, `.laz`-Ausgabe verständlich ablehnen. Weder einen Writer-Stub ansteuern noch unkomprimiertes LAS unter einer irreführenden `.laz`-Endung als Erfolg ausgeben.

## 6. Definierte PDAL-Pipeline-Teilmenge

### 6.1 Syntax und Geltungsbereich

Unterstütze eine lineare Pipeline mit **einem Reader, null oder mehr Filtern und genau einem terminalen Writer**. Akzeptiere sowohl `{"pipeline":[...]}` als auch das entsprechende JSON-Array. Die erste Datei darf als Reader-Kurzform angegeben werden. Für die Ausgabe verwende einen expliziten Writer mit den unterstützten Optionen.

Für die unterstützten Stages entsprechen die JSON-Optionen und ihre fachliche
Wirkung dem dokumentierten PDAL-Referenzstand. Der GeoTIFF-Writer trägt in
PointCloud Java den Typ `writers.raster`. Für den PDAL-Referenzlauf wird
dieser Typ auf `writers.gdal` gesetzt. Die [Pipeline-Dokumentation](docs/architecture.md#pipelines)
definiert die unterstützte JSON-Eingabe und die Ausgabemetadaten.

Unbekannte Stage-Typen, unbekannte Optionen und Syntax ausserhalb der Teilmenge **vor dem Start der eigentlichen Verarbeitung** zurückweisen. Fehlermeldung mit Stage-Position, Typ und problematischer Option. Nichts stillschweigend ignorieren.

Relative Dateipfade werden wie beim Referenzlauf relativ zum Arbeitsverzeichnis ausgewertet. Beispiele vom Repositorywurzelverzeichnis aus ausführen. Keine versteckte, von PDAL abweichende Pfadauflösung einführen.

### 6.2 Stages und Optionen

| Stage | Unterstützter Vertrag |
| --- | --- |
| `readers.las` | `filename`; lokale `.las` und `.laz` im geprüften Formatumfang. Dateisignatur/Header prüfen, nicht nur die Endung |
| `filters.crop` | Genau eine achsenparallele 2D- oder 3D-Box in PDAL-`bounds`-Syntax; optional `outside` als Boolean. Inklusive Grenzen entsprechend Referenz; keine Polygone, Mehrfachboxen oder CRS-Transformation |
| `filters.expression` | Genau ein Ausdruck als String in der unten definierten Teilgrammatik |
| `filters.decimation` | Ganzzahliges `step >= 1`, `offset >= 0`, optional exklusives `limit`. Positionszählung über den gesamten dieser Stage zugeführten Strom, nicht je Batch |
| `filters.stats` | Explizite `dimensions` als kommaseparierte Liste; Anzahl, Minimum, Maximum, Mittelwert und Stichprobenvarianz/-standardabweichung nach überprüfter Referenzsemantik. Keine globalen Mediane oder vollständigen Wertelisten |
| `filters.hag_dem` | Höhe über Grund aus einem lokalen, nach Norden ausgerichteten GeoTIFF im Eingabe-CRS. Ein Band mit bis zu vier Millionen Zellen. `HeightAboveGround` steht nach der Stage für Ausdrücke, Statistik, Raster und den optionalen LAS-Export zur Verfügung. |
| `writers.las` | `filename`, ausdrücklich `forward: "all"`, `compression: false` als Standard. `extra_dims: "all"` übernimmt die Originalrecords. Nach `filters.hag_dem` hängt `extra_dims: "HeightAboveGround=double"` oder `"HeightAboveGround=float64"` acht Bytes an jeden ausgewählten Originalrecord an. |
| `writers.null` | Terminierung ohne Punktdatei; für Statistik- und Dekodiermessungen |
| `writers.raster` | Lokales Float64-GeoTIFF für `Z`, `Intensity` oder nach `filters.hag_dem` für `HeightAboveGround`. Verlangt `binmode: true` und ein explizites Gitter. Wählbare Bänder `min,max,mean,count,stdev`, nichtnegatives `window_size`, endliches `nodata` und optionale Deflate-Kompression. `p50` und `p90` liefern exakte Zellperzentile für bis zu 4'000'000 eingerasterte Werte. Mit HAG und `count` ergänzt `height_thresholds` bis zu acht Anteile direkt beobachteter Echos strikt über einer Schwelle. |
| `writers.dtm` | Lokales Float64-GeoTIFF aus einem XY-Delaunay-Netz über den ausgewählten LAS-Punkten. Explizites Gitter im projizierten Eingabe-CRS, höchstens eine Million ausgewählte Stützpunkte vor Deduplizierung, lineare Z-Interpolation und optionales XY-Kantenlimit. Mit `fill_nodata: "nearest"` ergänzt der nächste ausgewählte Punkt alle unbelegten Zellen. Die vollständige Ausgabe setzt mindestens einen ausgewählten Punkt voraus. `support_filename` exportiert optional ein deckungsgleiches UInt8-GeoTIFF mit Codes für unbelegte, interpolierte und ergänzte Zellen. |

Die Rastererweiterung verarbeitet höchstens eine Million Zellen. Das Gitter
verwendet die skalierten XY-Koordinaten und das horizontale CRS der LAS-Eingabe.
GeoTools liest die CRS-Definition und schreibt das GeoTIFF in der normalen
JVM-Distribution. GDAL und PDAL sind Referenzwerkzeuge für die Abnahme.
Die [Pipeline-Dokumentation](docs/architecture.md#pipelines) führt die
Rasteroptionen, Zellgrenzen und NoData-Werte auf.

Bei `writers.las` darf `compression` mit Standard `false` entfallen.
`forward: "all"` und die Wahl von `extra_dims` werden ausdrücklich
angegeben. Der HAG-Export übernimmt die LAS-Version, die Quantisierung
und die ursprünglichen Attribute. [Q6]

`filters.range` gehört zunächst nicht zur Teilmenge. Für neue Pipelines empfiehlt PDAL `filters.expression`. Damit entfällt eine zweite parallele Filtersyntax. [Q5]

### 6.3 Kleine Ausdrucksgrammatik

Unterstütze:

- Dimensionsnamen `X`, `Y`, `Z`, `Intensity`, `Classification`, `ReturnNumber`, `NumberOfReturns`;
- zusätzliche Standarddimensionen `GpsTime`, `Red`, `Green`, `Blue`, `Infrared` nur, wenn sie im geöffneten Punktformat vorhanden und korrekt zugeordnet sind;
- einen Dimensionsnamen links und ein endliches numerisches Literal rechts eines Vergleichs;
- `==`, `!=`, `<`, `<=`, `>`, `>=`;
- `&&`, `||`, `!` und Klammern mit definierter üblicher Priorität;
- vorzeichenbehaftete Dezimalzahlen, sinnvollerweise auch Exponentialschreibweise.

Beispiele:

```text
Classification == 2
(Z >= 650 && Z <= 850) && Classification == 2
Classification == 2 || Classification == 6
!(Intensity < 10)
```

Keine Funktionen, arithmetischen Teilausdrücke, Dimensionsvergleiche gegeneinander, Zuweisungen oder Skriptengine. Ein kleiner Lexer und rekursiver Parser genügen. Den Ausdruck einmal vor der Punktverarbeitung parsen; keine Strings oder Reflection pro Punkt auswerten.

Dimensionsnamen am Schema prüfen. Fehlende Dimensionen nicht als Null oder 0 erfinden. Die unterstützte Grammatik in der Dokumentation angeben; komplexere syntaktisch gültige PDAL-Ausdrücke ausdrücklich als ausserhalb der Teilmenge ablehnen.

### 6.4 Verbindliches Pipelinebeispiel

Lege `examples/ground-crop.json` mit diesem Inhalt an:

```json
{
  "pipeline": [
    {
      "type": "readers.las",
      "filename": ".cache/test-data/so-2019.laz"
    },
    {
      "type": "filters.crop",
      "bounds": "([2594100,2594300],[1227600,1227800])"
    },
    {
      "type": "filters.expression",
      "expression": "Classification == 2"
    },
    {
      "type": "filters.stats",
      "dimensions": "X,Y,Z,Intensity"
    },
    {
      "type": "writers.las",
      "filename": "build/results/ground-crop.las",
      "forward": "all",
      "compression": false,
      "extra_dims": "all"
    }
  ]
}
```

Der Ausschnitt liegt innerhalb der aus dem Pflichtdateiheader ersichtlichen Kachel. Ob und wie viele Bodenpunkte darin vorkommen, muss die Realdatenprüfung feststellen. Kein erwartetes Ergebnis aus dem Dateinamen erfinden.

Ergänze genau zwei weitere Beispiele:

1. **`stats.json`:** Pflichtdatei → Statistik → `writers.null`, damit die gesamte Kachel ohne grosse Ausgabe untersucht wird.
2. **`decimate.json`:** Pflichtdatei → ganzzahlige Dezimierung → LAS-Writer. Zum Nachweis der Positionierung über Batch- und Chunkgrenzen.

Die Pipeline-Dateien selbst müssen unverändert von PDAL akzeptiert werden. Produktspezifische Optionen wie Batchgrösse oder Experimentauswahl gehören in CLI-/Benchmarkparameter, nicht als erfundene PDAL-Stage-Optionen in diese JSON-Dateien.

### 6.5 CLI und Bibliotheks-API

Die normale Distribution stellt mindestens bereit:

```bash
pointcloud --help
pointcloud --version
pointcloud info .cache/test-data/so-2019.laz
pointcloud pipeline examples/ground-crop.json --metadata build/results/java-metadata.json
```

`info` zeigt mindestens Format, Punktzahl laut Header, Scale/Offset, Bounds laut Header, vorhandene Standardattribute und die CRS-/VLR-Informationen, die ohne neue CRS-Interpretationsbibliothek zugänglich sind. Headerinformation klar von berechneter Statistik unterscheiden.

`pipeline` verarbeitet die Datei vollständig, schreibt die Punktdatei und optional ein dokumentiertes kleines Metadaten-JSON. Statistiken werden an der Position der jeweiligen Stage erhoben. Eine Statistik vor einer Filterung ist nicht dieselbe wie eine danach. Mehrere Stats-Stages getrennt über ihre Position identifizieren.

Die Bibliotheks-API erlaubt denselben Ablauf ohne CLI. Ein kleines Java-Beispiel aus einem Test oder einer Demo zeigt das Lesen von Batches oder Ausführen einer Pipeline. Kein separater Framework-Layer dafür.

Erfolgreiche Verarbeitung liefert Exitcode 0, Eingabe-/Konfigurations-/Verarbeitungsfehler einen Nichtnull-Exitcode. Für fachliche Fehler verständliche Meldungen; Stacktraces nur im ausdrücklich gewählten Diagnosemodus. Logs nicht in maschinenlesbare JSON-Ausgaben mischen.

## 7. Korrektheit und Dateierhaltung

### 7.1 Punktdaten

Für reine Auswahl- und Dezimierungspipelines müssen alle behaltenen Punkte in ursprünglicher Reihenfolge und mit unveränderten Werten erhalten bleiben:

- rohe XYZ und die ursprüngliche Quantisierung;
- Intensität, Return Number und Number of Returns;
- Classification, zugehörige Flags, Scan Direction und Edge of Flight Line;
- Scan Angle in der jeweiligen Formatsemantik, User Data und Point Source ID;
- GPS-Zeit, RGB, NIR und Scanner Channel, soweit vorhanden;
- Extra Bytes einschliesslich unbekannter Nutzdaten.

Nicht nur XYZ und Classification wieder wegschreiben. Solche scheinbar erfolgreichen Dateien wären Datenverlust.

### 7.2 Header, VLR und EVLR

Ausgaben benötigen korrekte Version, Format, Recordlänge, Offsets, Punktanzahlen, Return-Zähler und aus den ausgegebenen Punkten berechnete Bounds. LAS-1.4-Zähler und die Regeln für Legacy-Zähler richtig behandeln. Einen leeren gültigen Output sowie den Einpunktfall ausdrücklich testen.

CRS-Informationen und passende Extra-Bytes-Beschreibungen erhalten. Unbekannte gewöhnliche VLR-/EVLR-Nutzdaten soweit unverändert übernehmbar opaque erhalten. Bekannte kompressions- oder indexabhängige Metadaten dürfen nicht blind kopiert werden: Ein LAS-Output bekommt keinen gültig aussehenden alten LASzip-/COPC-/LAX-Index. Die konkrete Regel je bekanntem Metadatentyp dokumentieren. Bei Metadaten, die eine nicht unterstützte externe Referenz oder Interpretation erfordern, die Grenze offenlegen und gegebenenfalls die Datei zurückweisen.

Prüfe, welche Metadaten der übernommene Reader beim Öffnen verändert oder entfernt. Eine bereits normalisierte Headerstruktur genügt nicht als Erhaltungsnachweis. Erfasse erforderlichenfalls originale VLR-/EVLR-Bereiche vor dieser Normalisierung und kopiere geeignete Nutzdaten begrenzt aus der Quelle.

Grosse EVLR-Nutzdaten nicht allein zur Weitergabe komplett in den Heap laden. Begrenztes Kopieren aus der Quelldatei ist ausreichend. Keine Behauptung einer vollständigen inhaltlichen Validierung beliebiger fremder VLR-Daten.

### 7.3 Fehler und Ressourcen

- Trunkierte Header, zu kurze Records, ungültige Offsets/Längen, strukturell erkennbare Chunkbeschädigungen, ungültige Chunkgrenzen und unerwartetes EOF liefern einen Fehler. Keine Erkennung beliebiger weiterhin formal dekodierbarer Bitveränderungen versprechen; eine solche Integritätsgarantie ist nicht Teil dieses Auftrags.
- Angaben aus Headern dürfen keine unkontrollierten Arraygrössen, Integerüberläufe oder vollständige Vorallokation für die Punktwolke auslösen.
- Eine unvollständige Verarbeitung darf nicht als erfolgreiche kleinere Punktwolke ausgegeben werden.
- Ausgabe zunächst in eine temporäre Datei im Zielverzeichnis schreiben; Header abschliessen, schliessen und erst bei Erfolg als Ziel sichtbar machen.
- Vorhandene Ziele standardmässig nicht überschreiben. Ein ausdrückliches `--overwrite` darf existieren. Quelle und Ziel dürfen auch über Alias/Symlink nicht dieselbe Datei sein.
- Reader, Writer, Channels, Arenen und Worker werden bei Erfolg, Fehler und Abbruch beendet. Keine Threads, Mappings oder temporären Outputs unbeabsichtigt zurücklassen.
- Keine globale GC-Anforderung, um reguläres Schliessen zu ersetzen, und keine privaten Cleaner-/Unsafe-Aufrufe als neue Lösung.

Der vorhandene Codec verwendet bereits Memory Mapping. Seine Ressourcenpfade bei der Integration tatsächlich prüfen; einen Vorteil durch `MemorySegment` nicht allein aus dem Begriff „mmap“ ableiten. [Q3]

## 8. Tests und unabhängige Abnahme

### 8.1 Normale Tests

Die normale Testsuite ist klein, offline und deterministisch. Sie deckt tatsächliche Risiken ab:

- Format-/Codec-Fixtures für den ausgewiesenen Umfang und mindestens ein Extra-Bytes-Beispiel;
- skalierte Koordinaten mit LV95-Grössen, negativen Rohwerten, unterschiedlichen Scale/Offsets;
- inklusive Crop-Grenzen, `outside`, Boolesche Priorität, unbekannte/fehlende Dimensionen;
- leerer Strom, ein Punkt, Batchreste, verschiedene Batchgrössen;
- Dezimierung über Batch-/Chunkgrenzen und nach einem vorgeschalteten Filter;
- Metadatenerhaltung, Flags und LAS-Headerkorrektheit;
- beschädigte und abgeschnittene Eingaben, Outputfehler und Ressourcenfreigabe;
- erneute unabhängige Ausführung einer Pipeline, ohne Zustand aus dem vorigen Lauf.

Numerische Statistiktests müssen auch grosse Koordinaten mit kleinen Streuungen enthalten. Eine instabile Berechnung über `sum(x*x) - sum(x)^2/n` nicht unbesehen verwenden. Eine stabile laufende Berechnung und, soweit nötig, ein überprüfter Merge reichen.

**Keine Tests von Tests und keine Tests von Tests von Tests.** Tests prüfen direkt ein Produktverhalten, einen Fehlerzustand, die Isolation oder einen echten Auslieferungsvertrag. Unzulässig sind Tests, die lediglich Existenz, Namen, Anzahl oder Implementierung anderer Tests prüfen, sowie eigene Prüfschichten, die Testberichte nochmals als angeblich unabhängigen Funktionstest auswerten. Das gewöhnliche Lesen eines JUnit-Berichts zur Dokumentation seiner Metriken ist selbstverständlich erlaubt.

JUnit-Discovery, Gradle-Testselektion und native Reports verwenden. Keine zusätzlichen JSON-/YAML-/XML-/Properties-Testregistrierungen, Norm-Test-Mappings, generierten Testpläne oder gleichwertigen Registries in Java. Fachliche Pipeline-JSON-Dateien, kleine Golden-Dateien, echte binäre Fixtures und notwendige Build-Lockdateien bleiben zulässig; sie sind keine Testverwaltung.

Wähle die kleinste ausreichende Testauswahl für die konkrete Änderung. Breitere Produkt-/Referenzläufe an den vorgesehenen Integrations- und Abnahmepunkten ausführen. Ein gültiger früherer Lauf darf verwendet werden, wenn Produktcode, Tests, Fixtures, relevante Buildoptionen und Toolchain unverändert dazu passen; Herkunft und Wiederverwendung kenntlich machen. Nicht denselben Lauf allein für einen zweiten Nachweis wiederholen. Nach einer relevanten Änderung die betroffenen Prüfungen erneut ausführen. Keine neue Hash-/Fingerprint-Infrastruktur zur Beurteilung dieser Wiederverwendung.

Nicht jeden Getter und jedes Implementierungsdetail mit einem eigenen Test spiegeln. Keine grossen zufälligen Dateigeneratoren oder zweite Workflow-Orchestrierung nur für Tests aufbauen. Ersetzte Testinfrastruktur einschliesslich Aufrufern und Dokumentationsvorgaben entfernen. Konkrete Regressionen erhalten. Ein Exitcode 0, eine hohe Testanzahl oder ein erfolgreich erzeugter Report allein belegt keine fachliche Korrektheit.

### 8.2 Referenz und Vergleich

Vorhandenes PDAL suchen und seine Version samt verfügbarem LAS/LAZ-Backend festhalten. Eine zusätzliche Installation ist zulässig, wenn sie gezielt nötig ist und ins Platzbudget passt. Der Produktpfad bleibt unabhängig davon.

Verwende drei Belege mit unterschiedlicher Aussagekraft:

1. **Bekannte kleine Fixtures:** erwartete rohe Punktwerte und Randfälle sind festgelegt.
2. **Unveränderter Port gegen integrierten Port:** prüft Fehler durch Import und lokale Anpassungen, ist aber kein unabhängiger Codec-Nachweis.
3. **PDAL beziehungsweise ein unabhängiges natives Werkzeug:** liest dieselben Eingaben und die Java-Ausgabe. Nach Möglichkeit PDAL mit einer getrennten Decoderimplementierung, etwa LazPerf. Zwei Adapter um denselben Java-Decoder sind kein unabhängiger Nachweis. Beim Vergleich mit nativem LASzip die gemeinsame Herkunft des C++-Codes und Java-Ports offenlegen.

Mindestens die drei Beispielpipelines in Java und PDAL ausführen. Gleiche Eingabe, gleiche Pipelineoptionen und gleicher Arbeitsumfang. Zuerst den Java-Lauf abschliessen und seine Punktdatei sowie Metadaten unter einem eindeutigen Vergleichsnamen sichern. Danach PDAL mit derselben JSON-Datei auf das wieder freie Originalziel ausführen. Ergebnisse streamend vergleichen und grosse Zwischenprodukte anschliessend entfernen. Eine kleine feste Testsequenz genügt; kein neuer Workflowmanager. Vergleich:

| Eigenschaft | Erwartung |
| --- | --- |
| Anzahl und Reihenfolge ausgewählter Punkte | Exakt gleich |
| Unveränderte rohe Integerattribute, Flags und Nutzbytes | Exakt gleich, soweit beide Referenzpfade sie abbilden; opaque Daten zusätzlich gegen die Quelle prüfen |
| XYZ bei gleicher Quantisierung | Exakt gleiche Rohwerte; fachliche Koordinaten daraus konsistent |
| Bounds, Minimum, Maximum | Mit den ausgewählten Punkten konsistent; gleiche fachliche Werte |
| Mittelwert, Varianz, Standardabweichung | Vorher begründete absolute/relative Toleranzen; Stichprobe/Population nicht verwechseln |
| CRS und Metadaten | Inhaltlich passend, unabhängig von erlaubter Serialisierungsreihenfolge |
| Gesamte LAS-Datei | Kein pauschaler Bytevergleich; legitime Header-/Generator-/Metadatenunterschiede sind möglich |

Für nicht endliche Attributwerte sowie Statistik mit 0/1 Punkten die konkrete Referenzsemantik prüfen und dokumentieren. Keine grosse Toleranz wählen, um echte Fehler zu verdecken.

Grosse Vergleiche streamen. Nicht beide vollständigen Outputs als CSV oder Punktobjektliste materialisieren. Für `ground-crop.json` und `decimate.json` sämtliche ausgegebenen Punktrecords vergleichen. Den Dezimierungsschritt so wählen, dass auch beide Vergleichsoutputs ins Budget passen. Sampling darf ergänzende Untersuchungen des ungefilterten Bestands erleichtern, ersetzt aber nicht den vollständigen Ausgabevergleich dieser zwei Pipelines. Mindestens ein vollständiger Pflichtdatei-Scan bleibt ebenfalls verbindlich.

Eine nicht verfügbare notwendige Referenzinstallation ist ein **offener Abnahmeblocker**. Stelle sie innerhalb der erlaubten Mittel selbständig bereit oder behebe die konkrete Ursache. Solange der vorgeschriebene Referenzvergleich nicht erfolgreich ausgeführt ist, bleibt die aktuelle Etappe offen; spätere Implementierungs- oder Experimentetappen dürfen nicht beginnen.

### 8.3 Speicherabnahme

Die Pflichtkachel muss im normalen sequenziellen JVM-Produktpfad mit **`-Xmx512m`** vollständig durch die Beispielpipeline laufen. Das ist ein Ziel für den Java-Heap, keine Behauptung, dass der gesamte Prozess nur 512 MiB braucht.

Maximales RSS, gemappte/off-heap Bereiche und Zahl offener Batches beobachten. Die Engine darf die vollständige Punktwolke nicht zur Verarbeitung im Heap halten. `writers.dtm` hält bis zu einer Million ausgewählte Punkte zur Triangulation. Den grösseren Eingabefall durch Wiederholung oder kontrollierte synthetische Batches prüfen, ohne eine riesige zusätzliche Datei zu erzeugen. Sehr grosse Zähler-/Offsetgrenzen gezielt mit kleinen Tests prüfen.

### 8.4 Verbindliche Dokumentation und Testmetriken je Etappe

Lege bereits in B0 **ein fortlaufendes Etappenprotokoll** an, beispielsweise `docs/implementation-log.md`. Es enthält für B0 und jede Etappe P0–P11 einen kompakten Abschnitt. Dieses Dokument ist die kanonische Stelle für Etappenstatus, Abnahme und Testmetriken. Architektur-/Formatverträge und ausführliche Experimenttabellen werden verlinkt, nicht mehrfach kopiert.

Jeder Etappenabschnitt enthält:

1. Ziel, umgesetztes Verhalten und die tatsächlichen Änderungen; bei Strukturentscheidungen die kurze Begründung der gewählten einfachen Lösung.
2. Geprüfte Codebasis, Datum, Toolchain/Plattform, relevante Eingaben und tatsächlich verwendete Prüfkommandos. Das Protokoll gehört zum Etappencommit und ist damit über Git zuordenbar; keine selbstreferenziellen Commit-Hashes oder eigene Versionsbelege erzwingen.
3. Ergebnisse und Metriken aus den echten Prüfungen nach der folgenden Tabelle. Keine geschätzten, erfundenen oder nur geplanten Werte.
4. Erkannte Fehler, ihre konkrete Korrektur und das Ergebnis der erforderlichen Wiederholungsprüfung. Kein vollständiges Gesprächs- oder Terminaltranskript.
5. Ergebnis der Einfachheitsprüfung und der Etappenabnahme, einschliesslich des Nachweises, dass keine Pflichtprüfung offen ist. Erst danach Abschlusscommit und Beginn der Folgeetappe.

| Prüfart | Mindestens zu dokumentierende Metriken/Nachweise |
| --- | --- |
| Gradle-/JUnit-Läufe | Ausgewählte Aufgabe/Klassen, tatsächlich gelaufene Tests, erfolgreich, fehlgeschlagen, Fehler und übersprungen nach dem nativen Report; Laufzeit; Exitstatus; Reportpfad |
| CLI-/Integrationstest | Tatsächlicher Aufruf, erwartetes und beobachtetes fachliches Ergebnis, Exitstatus, Laufzeit, relevante Ein-/Ausgabegrössen |
| LAS-/LAZ-/PDAL-Abnahme | Punkte gelesen/ausgewählt/geschrieben, vollständig verglichene Records, Zahl der Abweichungen, geprüfte Attribut-/Metadatenbereiche, Referenzversion und Decoder, numerische Toleranzen und beobachtete Abweichungen |
| Speicher-/Performanceversuch | Wandzeit, Punkte/s, Batchgrösse, Workerzahl, Heapgrenze, maximaler Prozessspeicher soweit messbar, Wiederholungszahl sowie Streuung; JDK/Hardware, Cold-/Warm-Situation und Messumfang |
| AOT/Native/GPU | Zusätzlich tatsächlich erzeugtes/verwendetes Artefakt oder Gerät, Build-/Setupzeit, Artefakt-/Cachegrösse beziehungsweise Transferkosten, Korrektheitsvergleich; keine JVM-/CPU-Fallbacks als Native-/GPU-Erfolg |
| B0 oder reine Dokumentationsprüfung | Konkret gelesene/geprüfte Anweisungen, Verweise und beseitigte Widersprüche; nicht anwendbare Laufzeitmetriken mit Begründung statt künstlicher Tests |

Erhebe die für die Etappe relevanten Werte. Nicht jede reine Headerkorrektur benötigt einen GPU-Benchmark. Verwende `nicht anwendbar` nur begründet für sachlich unzutreffende Metriken; eine fehlende Pflichtmessung ist weiterhin offen. Ein Pflichtlauf mit leerer Testauswahl, übersprungenen Pflichtfällen oder Abbruch gilt nicht als erfolgreich. Die bloße Angabe „Tests grün“ genügt nicht.

Ein `UP-TO-DATE`-/Cache-Ergebnis ist kein frisch ausgeführter Test. Wenn ein gültiger früherer Lauf verwendet wird, genau das und seine passende Code-/Toolchaingrundlage dokumentieren. Durch Parameterisierung oder mehrere Reports keine Tests doppelt zählen.

**Keine zusätzliche Infrastruktur zur Metrikerhebung bauen.** Native JUnit-XML-/HTML-Berichte, JMH-Ergebnisse, kurze Prozessmessungen und vorhandene Gradle-Funktionen reichen. Die kompakten Testmetriken und fachlichen Abnahmeergebnisse werden im Etappenprotokoll committed. Umfangreiche Rohlogs und normale Testreports bleiben unter `build/`; ihre Pfade und Erzeugungsbefehle sind dokumentiert, aber sie werden nicht als nach einem `clean` weiterhin vorhandenes Archiv ausgegeben. Kleine tragende Benchmark-CSV-/JSON-Dateien gezielt aufbewahren und im Experimentbericht erläutern.

Keine eigene Etappen-Engine, Testregistry, Report-Projektion, Datenbank, Metrikplattform oder automatische Vollständigkeitsprüfung der Dokumentation. Saubere Dokumentation entsteht durch klare, echte Ergebnisse und konsistente Aktualisierung im selben Änderungssatz.

## 9. Performance-Experimente mit Entscheidungsregeln

### 9.1 Gemeinsame Regeln

Für jedes Experiment vor Beginn kurz festhalten: Hypothese, Vergleichspfad, Datensatz/Kernel, Korrektheitskriterium und maximal untersuchte Varianten. Danach Messaufruf, Rohresultate, Interpretation und Entscheidung dokumentieren.

Ein erfolgreich abgeschlossenes Experiment endet mit einer dieser Entscheidungen:

- **übernommen:** gemessener praktischer Vorteil bei erhaltener Korrektheit und vertretbarer Komplexität;
- **Experiment behalten:** reproduzierbar interessant, aber kein sinnvoller Produktstandard;
- **verworfen:** kein ausreichender Nutzen; unnötigen Code wieder entfernen;

**Blockiert, fehlgeschlagen und nicht ausgeführt sind offene Arbeitszustände, keine Abschlussentscheidungen.** Sie erlauben keinen Beginn der nächsten Etappe. Die Unterscheidung aus Abschnitt 1.2 gilt für alle Versuche: Eine korrekt gemessene langsamere Variante kann verworfen werden; eine mangels Hardware gar nicht ausgeführte Variante wurde nicht gemessen.

Die vorgeschriebenen Experimente tatsächlich im festgelegten Umfang durchführen. Sie nicht pauschal wegen Zeitbedarf auf „später“ verschieben. Bei Setupfehlern die Ursache ermitteln und gezielt selbständig beheben; keine blinde Wiederholung derselben Installation. Führt ein normaler Versuch samt gezielter Korrektur nicht weiter, den verbleibenden Blocker präzise untersuchen und nur begründete weitere Abhilfen versuchen. Ein objektiv unauflösbarer externer Blocker hält die aktuelle Etappe gemäss Abschnitt 1.2 an. Fehler im eigenen Code selbst beheben. Fehlende Umgebungsfähigkeit niemals nachträglich zum erfolgreichen Abnahmekriterium erklären.

Eine Optimierung wird nicht allein wegen eines einzelnen schnellsten Laufs, einer schönen API oder eines theoretischen Potenzials Produktstandard. Kein Mindest-Speedup versprechen. Ein überschaubarer klarer CPU-Pfad bleibt die Referenz.

Vor jeder produktiven Übernahme zusätzlich mindestens eine vollständige Pflichtpipeline End-to-End gegen den skalaren Ausgangsstand messen und fachlich vergleichen. Ein reiner Kernelgewinn rechtfertigt keine Produktübernahme. Für spätere Vergleiche den betreffenden Baseline-Commit und seine Messwerte festhalten.

### 9.2 E1 – Batching, Datenlayout und Allokationen

Vergleiche höchstens zwei nachvollziehbare Batchlayouts und beispielsweise 32'768, 131'072 und 524'288 Punkte pro Batch. Ein kleines zusätzliches realistisches Profil mit JFR ist ausreichend.

Miss Dekodierung, Batchbefüllung, Filterung und Gesamtlauf separat genug, um den Engpass zu erkennen. Prüfe insbesondere Wrapper-Allokationen und Kopien zwischen Codec und Engine. Verbessere zuerst vermeidbare Allokationen oder unnötige vollständige Kopien, bevor du den Codec algorithmisch veränderst.

Ergebnis: ein einfacher Standardwert und ein konkretes Produktlayout. Kein automatisch lernender Batchmanager und keine Sammlung dauerhaft gepflegter gleichwertiger Layouts.

### 9.3 E2 – Vector API

Baue ein getrenntes Experiment mit `jdk.incubator.vector`; die notwendigen `--add-modules`-Flags gelten nur für die betreffenden Compile-/Run-Aufgaben. Ein neues JDK erfordert gegebenenfalls erneutes Kompilieren des Experiments.

Zwei Aufgaben genügen:

1. Crop-/Classification-Auswahl auf bereits dekodierten Batches, einschliesslich Auswahlmaske **und** stabiler Übernahme der gewählten Punkte.
2. Ein numerischer Kernel, beispielsweise quadrierte Distanzen zu einem festen lokalen Bezugspunkt oder eine klar definierte affine Operation.

Vergleiche normale Schleifen mit aktivem JIT gegen explizite Vector API. Die automatische Vektorisierung des JIT gehört zur Baseline; sie nicht künstlich abschalten, um SIMD besser aussehen zu lassen.

Längen unterhalb, genau auf und knapp oberhalb einer Vector-Species-Breite prüfen. Keine Lesefehler am Tail. Die Auswahlmaske muss exakt zur Referenz passen. Ein schnellerer Vergleich mit langsamerer Kompaktierung ist kein Pipelinegewinn.

Wenn die Inkubator-Variante nützlich ist, darf sie als ausdrücklich aktivierbares Zusatzartefakt erhalten bleiben. Der normale Java-25-Build und die normale Distribution bleiben ohne Inkubator-Modul verwendbar. Keine automatische Backend-Erkennung dafür entwickeln.

### 9.4 E3 – Begrenzte Parallelität nach der Dekodierung

Untersuche die Verarbeitung unabhängiger bereits dekodierter Batches mit einem kleinen Platform-Thread-Pool. Teste höchstens 1, 2, 4 und eine zur Maschine passende weitere Workerzahl.

Jeder Worker besitzt seinen Zustand. Rückstau, begrenzte offene Batches und Ausgabe in Originalreihenfolge sind verbindlich. Statistikzusammenführung deterministisch in definierter Reihenfolge durchführen. Die globale Dezimierungsposition nicht pro Worker neu beginnen.

Zuerst klar festlegen, welche Stages tatsächlich unabhängig parallelisierbar sind. Nicht blind die gesamte Pipeline je Batch kopieren. Falls die Arbeit zu klein ist oder Dekodierung dominiert, darf der Versuch einen negativen Befund liefern.

Keine `parallelStream()`-Verwendung auf einem gemeinsamen veränderlichen Reader und kein Virtual Thread pro Punkt. Virtual Threads beschleunigen CPU-Arbeit nicht. Ein Netzwerkexperiment mit Virtual Threads gehört für die lokale erste Produktversion nicht zum Pflichtumfang. [Q10]

### 9.5 E4 – LAZ-Chunkparallelität

Untersuche am integrierten Codec, ob die in der Pflichtdatei deklarierten Chunks unabhängig und effizient erschlossen werden können. Der Port hat Chunkzustand und Seek-Logik, aber keine fertige öffentliche Chunk-Task-API. [Q3]

Verbindliche Teilaufgaben:

1. Chunkstarts/-längen und Punktbereiche aus dem tatsächlichen Dateiformat ermitteln; feste und variable letzte Chunklänge beachten.
2. Pro Worker getrennten Stream-/Decoder-/Modellzustand verwenden.
3. Startpunkt und Prädiktorzustand am Chunkanfang korrekt initialisieren.
4. Eine begrenzte Zahl Chunks vorhalten und in Originalreihenfolge weitergeben.
5. Gesamtaufwand einschliesslich Tabelle, Seek, I/O, Dekodierung, Zusammenführung und Writer messen.
6. Fehlende zulässige Random-Access-Information von beschädigten Tabellen unterscheiden. Nur im ersten Fall ist ein ausdrücklich dokumentierter sequenzieller Pfad zulässig.

Nicht an beliebigen komprimierten Bytes teilen. Nicht jeden Worker vom Dateianfang bis zu seinem Ziel dekodieren lassen und das als effiziente Chunkparallelität bezeichnen. Kein vollständiger Codecumbau: Ein korrekt ausgeführter begrenzter Prototyp darf zeigen, dass der Aufwand oder Nutzen keine produktive Übernahme rechtfertigt. Ergebnisse und Gründe belegen; das Produkt darf dann sequenziell bleiben. Ein bloss vermuteter Architekturblocker oder ein nicht funktionsfähiger Prototyp ersetzt die vorgeschriebene Untersuchung und Abnahme nicht.

### 9.6 E5 – MemorySegment und I/O

Vergleiche den bestehenden tatsächlichen I/O-Pfad mit genau einer Alternative auf Basis öffentlicher `MemorySegment`-/`Arena`-APIs oder sinnvoll gepufferter I/O. Da der Codec schon Mapping nutzt, geht es um konkrete Zugriffskosten, Kopien, Lebensdauer und Ressourcenfreigabe.

Eine kleine unkomprimierte LAS-Datei beziehungsweise ein begrenzter Bytezugriff reicht für den isolierten Versuch. Einen realen LAZ-Gesamtlauf zusätzlich messen, falls der Adapter dort tatsächlich eingesetzt wird. Keine vollständige zweite Speicherarchitektur schreiben.

Threadzugriff passend zur Arena wählen; eine confined Arena nicht an Worker weiterreichen. Geschlossene Segmente nicht verwenden. Heap, RSS und Mappingumfang getrennt berichten. Off-heap bedeutet nicht automatisch weniger Gesamtspeicher. [Q11]

### 9.7 E6 – JDK-Versionen und AOT

Führe dieselben CPU-Produktartefakte auf Java 25 und höchstens zwei weiteren sinnvoll verfügbaren neueren Laufzeiten aus. Java 21 ist kein Ziel für Java-25-Classfiles. Keine Mehrfachimplementierung zur Unterstützung älterer JDKs hinzufügen.

Untersuche auf einem geeigneten JDK dessen dokumentierten AOT-Cache. Trainings-/Erstellungszeit und Cachegrösse separat ausweisen. Cache, Anwendung, JDK und Architektur müssen zusammenpassen. Keinen AOT-Cache als eigenständiges Native Binary bezeichnen. [Q12]

Jeweils kurze CLI-Aufgabe (`info` oder Mini-Pipeline) und vollständige Kachelverarbeitung vergleichen. Startzeit, Warm-up und dauerhaften Durchsatz unterscheiden. Für jedes Ergebnis genaue Runtime und Flags angeben.

### 9.8 E7 – GraalVM Native Image

Baue mit einer geeigneten vorhandenen GraalVM zuerst die normale CPU-CLI als **echtes natives Binary**. Keine JVM-Fallback-Datei als Native-Erfolg akzeptieren. Die kleine Beispielpipeline und mindestens die Pflichtkachel-Pipeline damit ausführen und Ergebnisse prüfen.

Erfasse Native-Buildzeit, maximalen Buildspeicher soweit verfügbar, Binarygrösse, Startzeit, RSS und Gesamtdurchsatz. Native Image muss nicht schneller als die JVM sein. Reflexionskonfiguration nur für tatsächliche Anforderungen hinzufügen, keine pauschalen Registrierungen aller Klassen.

Vector API plus Native Image ist höchstens eine begrenzte Zusatzmessung, sofern die konkrete GraalVM dies unterstützt. Die aktuellen GraalVM-25-Release-Notes beschreiben initiale Vector-API-Unterstützung; weder uneingeschränkte Unterstützung noch pauschale Unmöglichkeit behaupten. TornadoVM plus Native Image ist kein Ziel. [Q13]

### 9.9 E8 – TornadoVM

Prüfe die konkrete lokale Hardware, Runtime, Treiber und das tatsächlich verfügbare Backend. Die aktuelle Dokumentation nennt unter anderem Apple Metal; trotzdem muss ein funktionierendes Gerät auf der ausführenden Maschine nachgewiesen werden. GPU-Ausführung benötigt zusätzliche native Treiber-/Laufzeitkomponenten und bleibt ausserhalb des reinen CPU-Produkts. **Optional ist die spätere Produktübernahme; die hier beauftragte Untersuchung einschliesslich tatsächlicher Geräteausführung ist verbindlich.** [Q14]

Portiere höchstens zwei bereits definierte Kernel aus E2. Java-Quellcode, Bibliothekstypen, Geräteauswahl und Datenübertragung müssen explizit nachvollziehbar sein. Ein Arraylayout, das für die CPU gut ist, ist nicht automatisch ohne Kopie TornadoVM-kompatibel.

Messe:

- erste Ausführung einschliesslich Kernelkompilierung;
- wiederholte Kernelzeit;
- Datenbereitstellung, Host-/Device-Transfer oder gemeinsame Speicherzugriffe;
- Rückübertragung, Kompaktierung und Gesamtlauf;
- optional mehrere Operationen auf denselben am Gerät verbleibenden Daten.

Auf Unified-Memory-Systemen ebenfalls tatsächliche Kopien/Synchronisation untersuchen. Keine Nullkosten voraussetzen. Echte Geräteausführung nachweisen; CPU-Fallback nicht als GPU-Messung melden.

FP64-Unterstützung und Leistung pro Gerät prüfen. Wenn der gewählte Backendpfad keine passende Double-Verarbeitung bietet, einen geeigneten Integer-Kernel verwenden oder eine lokale Float-Variante mit eigener Fehlergrenze untersuchen. Produktive Filterentscheidungen und LAS-Koordinaten dürfen dadurch nicht unbemerkt verändert werden. Für die Referenzprüfung kein `fastmath`.

Der gemessene Befund „GPU vorhanden, für diesen Kernel nach Transfers langsamer“ kann die Untersuchungsaufgabe erfolgreich abschliessen. „Kein passendes Gerät verfügbar“ ist hingegen ein offener Ausführungsblocker; P10 ist damit nicht abgenommen und P11 beginnt nicht. Vorhandene oder im zulässigen Umfang installierbare Backends selbständig prüfen. Kein vollständiges Treiber-/Compilerbauprojekt und keinen unbezeichneten CPU-Ersatz daraus machen.

## 10. Benchmarkmethodik und 1BRC

### 10.1 Was aus 1BRC übernommen werden soll

Untersuche insbesondere die lesbaren Grundideen aus `CalculateAverage_shipilev.java` und für SIMD/MemorySegment ausgewählte Teile von `CalculateAverage_merykitty.java`. Der geprüfte 1BRC-Stand ist `db064194be375edc02d6dbcd21268ad40f7e2869`. [Q15]

Übertragbare Prinzipien:

- grosse zusammenhängende Datenbereiche und begrenzte Arbeitspakete;
- worker-lokaler Zustand und spätes Zusammenführen;
- keine Objekt-/String-Erzeugung pro Datenrecord ohne Bedarf;
- einfacher korrekter Restpfad neben optimiertem Hauptpfad;
- erst messen, dann den tatsächlich dominierenden Aufwand verändern.

Nicht übernehmen: `Unsafe`, private Cleaner-Reflexion, Überlesen von Pufferrändern, feste Wettbewerbsannahmen über Eingaben oder ein Unterprozess-Trick, der Arbeit nach dem sichtbar gemeldeten Ende erledigt. LAZ-Chunks sind keine frei teilbaren Textzeilen. Kein SIMD-Textparser für ein Problem bauen, das binäre Punktdaten verarbeitet.

1BRC ist Literatur, keine zweite zu integrierende Codebasis. Wenn einzelne kleine Codeteile übernommen werden, Herkunft und Lizenz erhalten. Die dortigen Zahlen auf einer anderen Hardware und RAM-Disk sind kein Leistungsziel für diese Engine.

### 10.2 Vier getrennte Messebenen

1. **Header/I/O:** reine Informationsermittlung und gegebenenfalls Bytezugriff.
2. **Dekodierung:** LAZ vollständig lesen, Ergebnisse in kleinem überprüfbarem Zustand konsumieren, ohne grosse Ausgabe.
3. **Rechenkern:** bereits dekodierte Batches im Speicher; CPU-Kernel vorzugsweise mit JMH.
4. **End-to-End:** reale Pipeline vom Prozessstart bis abgeschlossener Ausgabe und Ressourcenfreigabe.

Ein Mikrobenchmark darf nicht als End-to-End-Beschleunigung ausgegeben werden. Grenzen der Parallelität und Anteil der Dekodierung in der Interpretation berücksichtigen.

### 10.3 Messregeln

- Gleiche Arbeit und gleiche Daten vergleichen. PDAL-Ausgabe nach LAS nicht mit Java-Ausgabe nach Null vergleichen.
- JMH mit Warm-up, mehreren Messiterationen, mindestens zwei Forks für tragende Kernelvergleiche und konsumiertem Ergebnis. Einstellungen klein beginnen; nur bei einer konkreten offenen Entscheidung erweitern.
- End-to-End ausserhalb von `gradle run` messen, damit Gradle-Start und Build die CLI-Zeit nicht überlagern.
- Vor Wiederholungen das jeweilige Outputziel kontrolliert freimachen beziehungsweise Vergleichsergebnisse sichern. Diese Vorbereitung ausserhalb der Messzeit ausführen; der eigentliche Lauf umfasst Schreiben, Abschluss und Schliessen. Keine verglichenen Dateien versehentlich überschreiben.
- Für volle Kacheln zunächst mindestens drei Wiederholungen pro ausgewählter Variante. Median und Streuung beziehungsweise Minimum/Maximum berichten. Ein einzelner Setup-/Fehlschlagversuch ist keine belastbare Geschwindigkeitsaussage.
- Erste und spätere Ausführung getrennt berichten. Warmer Dateisystemcache ist kein kalter Datenträgerlauf. Kein rootpflichtiges Cache-Leeren verlangen.
- Keine endlose Kombination aller JDKs, GCs, Batchgrössen, Threads und Geräte. Zuerst eine Dimension untersuchen, dann eine begründete Konfiguration weiterverwenden.
- CPU-Modell/Architektur, Betriebssystem, RAM, JDK-Vendor/-Version, Commit, Flags, Batchgrösse, Threads und Eingabe angeben.
- Punkte pro Sekunde, Wandzeit und maximalen Prozessspeicher berichten; Byte/s nur mit klarer Bezugsgrösse komprimiert/unkomprimiert.
- Korrektheitsvergleich vor Leistungsschlussfolgerung. Keine Prüfschritte nur bei einer Variante in den Messbereich legen.
- JFR nur bei gezielten Profilingläufen, nicht dauerhaft in einer verglichenen Variante.
- Ergebnis als kleine CSV-/JSON-Datei plus lesbare Tabelle. Kein eigenes Benchmarkportal oder umfangreicher Orchestrator.

## 11. Umsetzungsplan mit Etappenabnahme

Die folgenden Etappen sind zwingend in der Reihenfolge **B0, P0, P1, …, P11** auszuführen. Für jede Etappe gilt vollständig die Abnahmeregel aus Abschnitt 1.2. Startbedingung ist der erfolgreich geprüfte, dokumentierte und committete Abschluss des Vorgängers. Innerhalb der aktuell zulässigen Etappe dürfen unabhängige Recherche-, Fixture- oder Prüfaufgaben parallel laufen. Keine Aufgabe einer späteren Etappe vorziehen, auch nicht über Subagents.

Jede Etappe wendet die jeweils passenden Skills an und dokumentiert ihre Testmetriken nach Abschnitt 8.4. Erst die erfolgreiche Abnahme berechtigt zum Abschlusscommit und anschliessenden automatischen Beginn der nächsten Etappe. Zusätzliche nachvollziehbare Zwischencommits, etwa zur Trennung von Codecimport und Anpassungen, sind erlaubt; sie ersetzen den erfolgreichen Etappenabschluss nicht.

### B0 – Verbindliche Projektregeln und Skills einrichten

**Ziel:** Die Arbeits-, Einfachheits-, Prüf- und Dokumentationsregeln gelten vom ersten Implementierungsschritt an.

**Arbeit:** Bereitgestelltes Repository, Branch und vorhandene Anweisungen lesen; den Auftrag an einem kanonischen Ort, beispielsweise `docs/implementation-plan.md`, referenzieren beziehungsweise ablegen; root-`AGENTS.md` und die drei kleinen Repository-Skills aus Abschnitt 1.3 erstellen oder passend ergänzen; ein einziges Etappenprotokoll mit Metrikkonvention anlegen. Keine Produktimplementierung, Buildlogik oder Datenexperimente in B0 vorziehen.

**Abnahme:** Anweisungen, Skill-Frontmatter, Pfade und gegenseitige Verweise inhaltlich geprüft; keine widersprüchlichen Regeln zu Reihenfolge, Autonomie, externen Reviews oder Tests; KISS/Ockham und das Verbot von Tests von Tests ausdrücklich festgehalten. Alle drei Skills sind konkret anwendbar und in `AGENTS.md` mit Auslösern verlinkt. Keine Platzhalter und kein zusätzliches Verwaltungsframework. B0-Prüfergebnis im Etappenprotokoll dokumentiert.

**Danach:** Den Bootstrap separat committen. Damit ist B0 erfolgreich abgeschlossen; erst jetzt P0 beginnen.

### P0 – Umgebung und Fundament

**Ziel:** Ein echter, kleiner Java-25-/Gradle-Groovy-Build.

**Arbeit:** Die in B0 eingerichteten Regeln und Skills anwenden; JDK-/GraalVM-/PDAL-/Hardware-/Platzinventur; Wrapper pinnen; Hauptprojekt und nötige Teststruktur anlegen; README mit Ziel/Scope; ignorierte Cache-/Buildpfade festlegen. Für die bereits festgelegten späteren Pflichtläufe früh die verfügbaren Werkzeuge feststellen, ohne deren Implementierung oder Messung vorwegzunehmen.

**Abnahme:** `./gradlew clean check` und eine minimale CLI mit `--help`/`--version` funktionieren. Keine experimentelle Installation ist dafür erforderlich. Maschinenpfade nicht in den Projektbuild hardcoden.

### P1 – Unveränderten Codec mit echten Daten verstehen

**Ziel:** Belegter Ausgangspunkt vor der Integration.

**Arbeit:** Festgehaltenen Upstream-Stand isoliert verwenden; Pflichtkachel innerhalb des Budgets herunterladen; Header, komplette Dekodierung und kleinen LAS-Ausschnitt prüfen; Allokationen und Fehlerpfade stichprobenweise untersuchen; Reader-/Writer-/Lizenzmatrix erstellen. Komprimiertes Schreiben anhand tatsächlicher Implementierung kurz prüfen.

**Abnahme:** Ganze Pflichtdatei dekodiert; erwartete Headerpunktzahl mit tatsächlicher Zahl verglichen; Output mit dem unabhängigen Werkzeug erfolgreich gelesen; notwendige Testmetriken und konkrete Codecgrenzen dokumentiert. Keine Behauptung von LAZ-Schreiben aufgrund eines Stubs. Ein fehlender Referenzlauf blockiert P1 und damit den Beginn von P2.

### P2 – Codecquellen übernehmen

**Ziel:** Eigene reproduzierbare Codecquelle.

**Arbeit:** Benötigten Quellumfang übernehmen; Herkunft und Lizenztexte; dünnen Adapter vorbereiten; externe Codecabhängigkeit entfernen; Importregression gegen die P1-Befunde prüfen.

**Abnahme:** Build und Dekodierversuch arbeiten mit eigenen Quellen; Dependency-Report enthält kein externes `laszip4j`; keine nativen I/O-Fallbacks; keine vollständige LAStools-Anwendung im Produkt.

### P3 – Verlustfreien Batchpfad bauen

**Ziel:** Begrenzter Speicher und vollständige Datenerhaltung.

**Arbeit:** Batchlayout, Reader, Writer und Lebensdauer; Raw-/World-Koordinaten sauber unterscheiden; unterstützte Formate und Metadaten; Fehler-/EOF-Vertrag; temporäre Ausgabe und Abschlussheader.

**Abnahme:** Kleine unkomprimierte und komprimierte Fixtures der Pflichtformate laufen durch; beibehaltene Punktwerte/Extras stimmen; beschädigte Eingaben scheitern eindeutig. Keine gesamte Punktwolke im Heap.

### P4 – Filter und PDAL-Teilmenge implementieren

**Ziel:** Die drei Beispielpipelines sind ausführbar.

**Arbeit:** Crop, begrenzte Expression-Grammatik, Decimation, Stats, Null-Writer; JSON-Validierung; CLI und Bibliotheksbeispiel; Stage-Zustände und globale Indizes; Options-/Unterstützungsdokumentation.

**Abnahme:** Beispiele mit kleinen Fixtures; Reihenfolge/Statistikposition; unbekannte Optionen vor Verarbeitung abgelehnt; identische Ergebnisse bei verschiedenen Batchgrössen. PDAL akzeptiert dieselben unterstützten Pipeline-Dateien.

### P5 – Vollständige Realdaten- und PDAL-Abnahme

**Ziel:** Ein brauchbares Produkt vor den Optimierungsversuchen.

**Arbeit:** Ganze Pflichtkachel sowie gezielte 2014-Kachel verarbeiten; die drei Pipelines gegen PDAL prüfen; Ausgabedateien unabhängig lesen; Genauigkeit, Metadaten und Heapziel prüfen. Kleine LAS-1.4-/Extra-Bytes-Fixtures vervollständigen.

**Abnahme:** Reproduzierbarer Bericht mit Versionen/Befehlen/Punktzahlen/Vergleichsergebnis; Pflichtpipeline mit `-Xmx512m`; keine still verlorenen Attribute; alle vorgeschriebenen Referenzläufe erfolgreich durchgeführt. Ein fehlender oder nicht bestandener Pflichtnachweis verhindert den Abschluss von P5 und den Beginn von P6.

### P6 – Baseline messen und einfache Engpässe beheben

**Ziel:** Belastbare skalare Baseline.

**Arbeit:** Vier Messebenen, kleine JMH-Konfiguration, E1, gezieltes JFR; unnötige Allokationen/Kopien entfernen; sinnvolle Default-Batchgrösse wählen; PDAL-End-to-End-Ausgangswert messen.

**Abnahme:** Kompakte Baseline mit Speicher/Durchsatz; jede Änderung fachlich geprüft. Keine Entropiecodec-Neuschreibung und kein Zahlentuning ohne Hypothese.

### P7 – SIMD, Speicher und Batchparallelität untersuchen

**Ziel:** E2, E3 und E5 mit klaren Ergebnissen.

**Arbeit:** Isolierte Vector-Kernel, begrenzte Batchparallelität und eine Speicher-/I/O-Alternative; Messungen nacheinander; Ergebnisse und Aufwand abwägen.

**Abnahme:** Korrekte Auswahl/Restpfade/Reihenfolge; produktive Übernahmen begründet; keine experimentellen Runtime-Anforderungen im Standardbuild. Nicht nützliche Varianten entfernen oder eng isoliert belassen.

### P8 – LAZ-Chunkparallelität untersuchen

**Ziel:** E4 am tatsächlich dominierenden Decoderpfad.

**Arbeit:** Chunkstart-/Seek-Verträge prüfen; begrenzten korrekten Prototyp mit getrennten Decodern umsetzen; tatsächliche vollständige Kachel vergleichen; Aufwand und Nutzen einer Übernahme anhand des Versuchs entscheiden.

**Abnahme:** Funktionierender korrekt begrenzter Versuch, vollständiger Ergebnisvergleich und dokumentierte Messwerte/Entscheidung. Ein negatives Nutzenresultat ist zulässig; ein ungelöster Architekturblocker ist keine bestandene Abnahme. Kein willkürliches Bytechunking und kein unbemerkter Verlust der ursprünglichen Reihenfolge.

### P9 – JVM-Versionen, AOT und Native Image

**Ziel:** E6 und E7 mit einem vollständigen CLI-Anwendungsfall.

**Arbeit:** Ausgewählte Laufzeiten, AOT-Cache und echtes Native Binary; kurze und lange Aufgabe getrennt vergleichen; Start/Build/Grösse/Speicher/Durchsatz dokumentieren.

**Abnahme:** Vorgeschriebene JVM-/AOT-/Native-Läufe tatsächlich ausgeführt, dieselbe Semantik nachgewiesen und Messwerte dokumentiert. Ein extern blockierter Build-/Laufpfad bleibt offen und verhindert P10. Keine feste Abhängigkeit des Produktbuilds von einem bestimmten lokalen GraalVM-Pfad.

### P10 – TornadoVM-Versuch

**Ziel:** E8 beantwortet die konkrete GPU-Frage.

**Arbeit:** Geräte-/Backendprüfung; höchstens zwei Kernel; FP64/Integer/lokale-Float-Eignung; Transfer-/Setupkosten; Vergleich mit skalarem und gegebenenfalls SIMD-CPU-Pfad.

**Abnahme:** Echte gemessene Geräteausführung, dokumentierte Gesamtkosten und Ergebnisvergleich; Genauigkeitsvertrag eingehalten; keine generische Accelerator-Infrastruktur; Produkt funktioniert weiter ohne GPU. Eine langsamere GPU-Variante darf verworfen werden. Fehlende Geräteausführung ist ein offener Pflichtpunkt und verhindert P11.

### P11 – Konsolidierung und Lieferung

**Ziel:** Verständliches Endprodukt und abgeschlossener Experimentbericht.

**Arbeit:** Unnötige Versuchsreste entfernen; Dokumentation an tatsächliche Unterstützung anpassen; normalen Gradle-Build und installierte Distribution prüfen; nach den letzten relevanten Änderungen Pflichtpipeline nochmals validieren; kompakte Mess-/Abnahmeergebnisse sichern; temporäre grosse Ausgaben bereinigen.

**Abnahme:** Alle Produktpflichten erfüllt und alle vorangegangenen Etappen erfolgreich abgenommen; jedes Experiment mit durchgeführter Untersuchung und einer begründeten Entscheidung abgeschlossen; keine offene Pflichtprüfung; keine positive Performancebehauptung ohne Messbeleg; vollständige Testmetriken und konsistente Dokumentation; sauberer Arbeitsstand und nachvollziehbare Etappencommits. Ein Bericht über noch offene Pflichtpunkte ist ein Unterbrechungsbericht, keine erfolgreiche P11-Abnahme.

## 12. Lieferumfang und finale Definition of Done

### 12.1 Dateien und Dokumentation

Liefere mindestens:

- die zu Beginn eingerichtete und fortlaufend gepflegte `AGENTS.md` sowie die drei kleinen versionierten Repository-Skills;
- funktionierenden Java-25-Quellcode und integrierten Codec;
- Gradle Wrapper/Groovy-Build, normale Tests, CI für den CPU-Standardbuild;
- JVM-Distribution via `installDist`/`distZip` und ein nutzbares Bibliotheks-JAR samt dokumentiertem Klassenpfad;
- die drei ausführbaren Beispielpipelines;
- kleine lokale Fixtures und einen expliziten Realdatenbeschaffungs-/Integrationstest-Aufruf;
- README mit Installation, Schnellstart, echter Beispielausgabe und Umfangsgrenzen;
- kompakte Architektur-/Datenlayoutbeschreibung und Unterstützungsmatrix;
- Herkunfts-/Lizenznachweis für Codec und übernommene Testdaten;
- Realdatenabnahme und experimentelle Messergebnisse mit reproduzierbaren Aufrufen;
- ein durch die tatsächlichen Läufe geprüftes Native-/AOT-Buildbeispiel; die erzeugten grossen Binärdateien/Caches nicht committen;
- ein vollständiges Etappenprotokoll mit echten Testmetriken, begründeten Entscheidungen und erfolgreicher Abnahme jeder Etappe.

Dokumentation nach Inhalt zusammenfassen; keine Datei für jeden kleinen Entscheid. README, Architektur/Unterstützung, Herkunft, das eine Etappenprotokoll mit Abnahme sowie die ausführlichen Experimente reichen neben dem kanonischen Auftrag. Keine zusätzlichen Statusdateien mit denselben Informationen in verschiedenen Formen. Die Skills beschreiben nur ihre spezifischen Arbeitsschritte und verweisen auf diese kanonischen Inhalte.

### 12.2 Abschlusscheckliste

- [ ] B0 ist vor P0 erfolgreich abgeschlossen und separat committed: `AGENTS.md`, drei anwendbare Skills und Dokumentations-/Metrikkonvention sind vorhanden.
- [ ] Java 25 und Gradle Groovy sind die normale Grundlage.
- [ ] Standardprodukt benötigt weder experimentelle APIs noch native LiDAR-Bibliotheken.
- [ ] Codecquellen liegen im Repository; externe `laszip4j`-Abhängigkeit ist vollständig entfernt.
- [ ] Vollständige Pflichtdatei und die gezielte zweite Realdatenvariante funktionieren.
- [ ] Unterstützte alte/moderne Formate sind mit kleinen Fixtures nachgewiesen.
- [ ] Die drei PDAL-kompatiblen Pipeline-Dateien laufen mit Java und der unabhängigen Referenz.
- [ ] Punktwahl, Reihenfolge, Attribute, Quantisierung und relevante Metadaten stimmen.
- [ ] Fehlerhafte Eingaben werden nicht als erfolgreiche Teilergebnisse ausgegeben.
- [ ] Heap-/Speicherziele sind überprüft; Datenbudget wird eingehalten.
- [ ] E1–E8 sind im festgelegten Umfang tatsächlich durchgeführt und erfolgreich als Untersuchung abgeschlossen; kein Pflichtversuch ist blockiert, fehlgeschlagen oder ausgelassen.
- [ ] Optimierungsentscheidungen enthalten Nutzen, Kosten und Grenzen.
- [ ] Normale Distribution funktioniert ohne Experimentinstallation.
- [ ] Dokumentation beschreibt den tatsächlichen Stand und keine bloss geplanten Fähigkeiten; Testmetriken jeder Etappe sind aus echten Ergebnissen festgehalten.
- [ ] Code, Tests, Build und Struktur erfüllen KISS/Ockham; keine Tests von Tests und keine eigene Test-/Gate-/Metrikverwaltung sind entstanden.
- [ ] Jede Etappe wurde erfolgreich geprüft, dokumentiert und committed, bevor die nächste begann; keine übersprungene oder vorgezogene Etappe.
- [ ] Alle Etappen wurden ohne Zwischenfreigaben oder Routineinput des Auftraggebers bearbeitet; kein OpenCode-/DeepSeek- oder vergleichbarer externer Reviewprozess wurde eingeführt.

Der erfolgreiche Abschlussbericht beantwortet direkt: **Was funktioniert? Wie wurde es nachgewiesen? Welche Tests und Messungen wurden tatsächlich ausgeführt? Wo liegt der Engpass? Welche Java-Techniken haben auf welcher Hardware geholfen? Was bleibt bewusst experimentell oder wurde begründet verworfen?** Nenne konkrete Testmetriken, Messwerte und die vereinbarten Umfangsgrenzen. Keine beliebige Prozentzahl für „Zielerreichung“. Bei einer objektiv nicht lösbaren Pflichtblockade stattdessen einen klar bezeichneten Unterbrechungsbericht an der betroffenen Etappe liefern; keinen erfolgreichen Gesamtabschluss behaupten.

## 13. Quellen und bei der Umsetzung erneut zu prüfende Referenzen

Die Quellen begründen die Ausgangsannahmen. Unterstützung und Messwerte des neuen Projekts entstehen erst durch seine eigenen Tests. Auf veränderlichen Dokumentationsseiten bei Umsetzung die tatsächlich benutzte Version festhalten.

- **Q1 – laszip4j:** [Repository/README am untersuchten Commit](https://github.com/mreutegg/laszip4j/tree/29a01a31607f1029f1330d1a09f19b17ea587005), [Root-Lizenz](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/LICENSE).
- **Q2 – tatsächliche Encodergrenze:** [POINT10-v2-Writer-Dummy](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/laszip/LASwriteItemCompressed_POINT10_v2.java), [POINT14-v3-Writer-Dummy](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/laszip/LASwriteItemCompressed_POINT14_v3.java).
- **Q3 – Reader, Rohkoordinaten, Lizenzheader und Chunkzustand:** [LASReader](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/LASReader.java), [LASPoint](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/LASPoint.java), [LASreadPoint](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/laszip/LASreadPoint.java), [LASreaderLAS](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/laslib/LASreaderLAS.java), [ByteStreamInFile](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/main/java/com/github/mreutegg/laszip4j/laszip/ByteStreamInFile.java).
- **Q4 – vorhandene kleine Fixtures und ungeeignete automatische Downloads:** [Testressourcen](https://github.com/mreutegg/laszip4j/tree/29a01a31607f1029f1330d1a09f19b17ea587005/src/test/resources), [DataFiles.java](https://github.com/mreutegg/laszip4j/blob/29a01a31607f1029f1330d1a09f19b17ea587005/src/test/java/com/github/mreutegg/laszip4j/DataFiles.java).
- **Q5 – PDAL-Pipelines und Filter:** [Pipeline](https://pdal.io/en/stable/pipeline.html), [Expression](https://pdal.io/en/stable/stages/filters.expression.html), [Range und Empfehlung für Expression](https://pdal.io/en/stable/stages/filters.range.html), [Crop](https://pdal.io/en/stable/stages/filters.crop.html), [Decimation](https://pdal.io/en/stable/stages/filters.decimation.html), [Stats](https://pdal.io/en/stable/stages/filters.stats.html).
- **Q6 – PDAL LAS-I/O:** [Reader](https://pdal.io/en/stable/stages/readers.las.html), [Writer, Forwarding und Quantisierung](https://pdal.io/en/stable/stages/writers.las.html).
- **Q7 – LAS-/LAZ-Referenzen:** [offizielles ASPRS-LAS-Repository](https://github.com/ASPRSorg/LAS), [veröffentlichte LAS-Spezifikationen](https://lasformat.org), [LASzip-Referenzprojekt](https://github.com/LASzip/LASzip), [LazPerf](https://github.com/hobuinc/laz-perf). Die für den implementierten Formatstand passende Spezifikation verwenden.
- **Q8 – Gradle/JDK-Kompatibilität:** [offizielle Matrix](https://docs.gradle.org/current/userguide/compatibility.html).
- **Q9 – Vector API auf Java 25:** [DoubleVector](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/DoubleVector.html), [IntVector](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/IntVector.html).
- **Q10 – Virtual Threads:** [Oracle Java 25 – Virtual Threads](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html).
- **Q11 – öffentliche Speicher-API:** [Arena](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html), [Memory Segments and Arenas](https://docs.oracle.com/en/java/javase/25/core/memory-segments-and-arenas.html).
- **Q12 – Java-AOT-Cache:** [Java-25-Launcher-Dokumentation](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html), Abschnitt Ahead-of-Time Cache.
- **Q13 – GraalVM:** [JDK-25-Release-Notes](https://www.graalvm.org/release-notes/JDK_25/), [Native Image](https://www.graalvm.org/jdk25/reference-manual/native-image/).
- **Q14 – TornadoVM:** [Übersicht](https://tornadovm.readthedocs.io/en/latest/), [Installation](https://tornadovm.readthedocs.io/en/latest/installation.html), [Programmierung](https://tornadovm.readthedocs.io/en/latest/programming.html), [Einschränkungen](https://tornadovm.readthedocs.io/en/latest/unsupported.html), [Flags](https://tornadovm.readthedocs.io/en/latest/flags.html).
- **Q15 – 1BRC:** [README und Messbedingungen](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/README.md), [Shipilëv](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_shipilev.java), [Merykitty](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_merykitty.java), [Thomas Würthinger](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_thomaswue.java).
- **Q16 – GRETL Runtime als Vorbild für Einfachheit und Skills:** [AGENTS.md am geprüften Commit](https://github.com/edigonzales/gretl-runtime/blob/aeba63d8418fb867149f879baa6a5d8fb07b2eca/AGENTS.md), [simplify-change](https://github.com/edigonzales/gretl-runtime/blob/aeba63d8418fb867149f879baa6a5d8fb07b2eca/.agents/skills/simplify-change/SKILL.md). Die projektspezifischen Veröffentlichungsvorgaben und die dortige externe Reviewpflicht werden hier nicht übernommen.
- **Q17 – INTERLIS Java als Vorbild für direkte Tests:** [AGENTS.md am geprüften Commit](https://github.com/edigonzales/interlis-java/blob/2581322d261853b24e87fa4ead0177f50cebe35b/AGENTS.md), [Testphilosophie und direkte Aufrufe](https://github.com/edigonzales/interlis-java/blob/2581322d261853b24e87fa4ead0177f50cebe35b/docs/development/testing.md). Im geprüften Stand sind keine Repository-Skills vorhanden; die neuen Pointcloud-Skills werden aus den beschriebenen Prinzipien und dem konkreten Produktbedarf entwickelt.
