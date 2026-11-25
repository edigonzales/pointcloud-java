# Performance-Experimente

Wandzeiten für CLI-Läufe umfassen Dateizugriff, Prozessstart und Abschluss.
Kernelmessungen beginnen bei vorliegenden Punktdaten. Die Messreihen laufen
seriell. Alle Dateiläufe verwenden den warmen Betriebssystemcache nach
erfolgreicher Realdatenabnahme. Es wurde kein Datenträgercache geleert.

## E1 · Baseline und Batchlayout

Ausgangsbasis `8f0f95c` (P5). Die Hypothese ist, dass die Entropiedekodierung
überwiegt und die Kosten zusätzlicher Spalten deren Kernelvorteil aufzehren.
Verglichen werden vollständige Records und daraus erzeugte X/Y-/Klassen-Spalten.
Die Testpunkte stammen aus der gleichmässigen Dezimierung der 2019-Kachel.
Kriterium sind exakt gleiche, stabile Punktindizes und bei Produktänderungen
der vollständige PDAL-Vergleich. Batchgrössen 32'768, 131'072 und 240'000
passen mit 34-B-Records in die 8-MiB-Grenze. Ein gezieltes JFR des
Ground-Crop-Laufs identifiziert gegebenenfalls vermeidbare Decoderallokationen.

Das Ausgangsprofil weist 42.10 % des beobachteten Allokationsdrucks
`POINT10_v2.read`, 29.21 % `RGB12_v2.read` und 27.14 %
`GPSTIME11_v2.read` zu. Die erste Variante ersetzt dort temporäre
Records durch Decoder-lokale Records. Die Aufnahme in das Byte-Array
erfolgt unmittelbar nach jedem Decoderschritt. Vor einem Übernahmeentscheid
werden vollständige Ausgaben und Statistiken gegen die Baseline und PDAL
geprüft sowie drei Ground-Crop-Läufe und ein weiteres JFR erfasst.
Das Zwischenprofil zeigt danach den kurzlebigen Lesekontext als grösste
Allokationsstelle. Die zweite Variante setzt denselben Decoder-lokalen
Kontext vor jedem Punkt auf null zurück und wird mit denselben
Korrektheits- und Messkriterien geprüft.

Aus 1BRC kommen die Ideen begrenzter zusammenhängender Arbeitspakete,
worker-lokalen Zustands und der getrennten Messung von Datentransfer und
Kernel. Die Texteingabe, private JVM-Schnittstellen und Resultatzahlen
des Wettbewerbs sind für LAZ ungeeignet.
[Shipilëv](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_shipilev.java) ·
[Merykitty](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_merykitty.java)

### Messung und Ergebnis

Apple M5 arm64, 10 CPU-Kerne, macOS 27.0.1, 24 GiB RAM. Temurin
25.0.5+8-LTS aarch64, `-Xmx512m` und Standard-GC. Die JVM läuft für jede
Wiederholung frisch an. PDAL 2.10.2/LazPerf verarbeitet dieselbe Pipeline
mit dem 10'000-Punkte-Streamtable und dem Reader-Standard von 7 Workers.
[PDAL Reader](https://github.com/PDAL/PDAL/blob/2.10.2/io/LasReader.cpp) ·
[PDAL PipelineManager](https://github.com/PDAL/PDAL/blob/2.10.2/pdal/PipelineManager.hpp)

Kommando für die Basisreihe mit installiertem Standard- und Experimentartefakt:

```sh
./gradlew -Pexperiments installDist :experiments:installDist
bash experiments/e1-baseline.sh "$PDAL"
```

Für die Baseline werden die Produktquellen von `8f0f95c` mit dem
`experiments`-Verzeichnis und `settings.gradle` des P6-Etappencommits
gebaut. Dazu genügt ein getrennter Git-Worktree mit einem Symlink auf die
vorhandene Testkachel. Die Profildateien und Punktdateien liegen unter
`.cache/experiments/`. Die Vergleichsziele in `build/results/` sind vor
der Ausführung freizuhalten. Das Skript verwendet für RSS das macOS-`time`.

| Messung | Batch / Variante | Wandzeit, Median (Min–Max) | Mio. Punkte/s | Grösstes RSS |
| --- | --- | ---: | ---: | ---: |
| Header-CLI | – | 0.06 (0.05–0.10) s | – | 46.41 MiB |
| Codec mit Extraktion | 1 Record | 4.55 (4.18–4.76) s | 3.20 | 375.72 MiB |
| Batch-Scan mit Extraktion | 32'768 | 4.61 (4.58–4.79) s | 3.16 | 379.06 MiB |
| Batch-Scan mit Extraktion | 131'072 | 3.87 (3.70–4.38) s | 3.76 | 379.11 MiB |
| Batch-Scan mit Extraktion | 240'000 | 3.78 (3.63–3.82) s | 3.85 | 382.73 MiB |
| Ground-Crop, JVM-Baseline | 32'768 | 3.79 (3.70–3.94) s | 3.84 | 381.55 MiB |
| Ground-Crop, JVM-Baseline | 131'072 | 4.23 (3.93–4.84) s | 3.44 | 366.80 MiB |
| Ground-Crop, JVM-Baseline | 240'000 | 3.96 (3.89–4.21) s | 3.68 | 378.88 MiB |
| Ground-Crop, PDAL | 10'000 | 2.49 (2.34–2.58) s | 5.85 | 162.44 MiB |
| Ground-Crop, wiederverwendete Records | 32'768 | 3.58 (3.55–3.91) s | 4.07 | 318.22 MiB |
| Ground-Crop, wiederverwendete Records und Kontext | 32'768 | 3.58 (3.57–3.68) s | 4.07 | 154.92 MiB |

Je 3 Prozessläufe. Der Durchsatz bezieht sich auf die vollständigen
14'566'627 Eingabepunkte. Alle Scans prüften Punktanzahl und rohe XYZ-Summen
gegen P1. Alle Java-Ground-Crop-Läufe lieferten identische 195'738
Punkte, 6'655'601 B als LAS und dieselben Statistiken. Die Messreihen
der verschiedenen Batchgrössen zeigen Prozessstreuung und enthalten
unterschiedliche JIT- und GC-Verläufe. Das isolierte Scantiming kann
deshalb grösser als die End-to-End-Wandzeit eines anderen Laufs sein.
[Einzelmessungen](measurements/e1-runtime.csv)

JMH 1.37, 1 Thread, 2 Forks, 3 × 300 ms Warm-up und 5 × 300 ms
Messung pro Fork, normale JIT-Optimierungen und GC-Profiler. Beide
Selektoren lieferten exakt dieselben stabilen Indizes im Sample mit
128'909 Punkten. JMH meldet intern eine Unsafe-Deprecation. Die
Experimentbibliothek ist ausserhalb des Produktartefakts.

| Kernel | Mittel ± JMH-Fehlerschätzung | Allokation/Operation |
| --- | ---: | ---: |
| Vollständige Records auswählen | 168.911 ± 23.513 µs | 3.878 B |
| Vorgerechnete Spalten auswählen | 92.272 ± 5.947 µs | 2.119 B |
| Spalten zunächst befüllen | 155.197 ± 8.101 µs | 3.555 B |

Das Befüllen und anschliessende Selektieren kostet zusammen etwa 247 µs,
gegenüber etwa 169 µs für die direkte Auswahl. Ein Spaltenlayout als
Produktstandard wurde verworfen. Die Spalten bleiben als isolierte
Versuchsvariante verfügbar. Der Produktstandard bleibt bei 32'768 Punkten.
[JMH-Einzeliterationen](measurements/e1-kernel.json)

Profile mit `settings=profile` und einem vollen Ground-Crop-Durchlauf.
Summierte JFR-Sample-Gewichte für `jdk.ObjectAllocationSample` betrugen
1'447.19 MiB (1'140 Samples) in der Baseline, 244.22 MiB
(443 Samples) nach der Record-Wiederverwendung und 21.50 MiB
(11 Samples) nach zusätzlicher Kontext-Wiederverwendung.
Diese Gewichte sind Stichprobenschätzungen. In den drei
unprofilierten Läufen mit beiden Änderungen lag der Median des
maximalen RSS bei 159'203'328 B gegenüber 398'508'032 B in der Baseline.
Die Laufzeiten deuten einen kleineren Vorteil an. Die belastbare
Entscheidungsgrundlage ist die geringere Speicherbelegung mit
unveränderten Resultaten. Beide kleinen Änderungen wurden übernommen.

Reproduktion der drei optimierten Prozessläufe mit
`JAVA_OPTS=-Xmx512m /usr/bin/time -lp build/install/pointcloud/bin/pointcloud
pipeline examples/ground-crop.json`. Zwischen Läufen das Ausgabeziel
freimachen. Die JFR-Variante verwendet zusätzlich
`-Xlog:jfr+startup=off -XX:StartFlightRecording=filename=.cache/experiments/e1-final.jfr,settings=profile,dumponexit=true`.
Das Profil mit `jfr view allocation-by-site` auswerten.

## E2, E3 und E5 · Vektoren, Batchparallelität und I/O

Basis ist das Produkt nach P6 (`8261661`), dieselbe Maschine und dasselbe
JDK. Die Experimente sind getrennt vom CPU-Standardbuild.

- **E2.** Kann SIMD auf den bereits dekodierten Spalten durch schnellere
  Auswahl oder Distanzrechnung helfen? Vergleich mit normalem JIT, zwei
  Kernel und je eine Vector-API-Implementierung. Der Auswahlkernel
  kompaktifiziert vollständige Records stabil. Distanz zu einem festen
  lokalen Bezugspunkt verwendet getrennte Multiplikation und Addition.
  Auswahl und Distanzwerte müssen exakt auch bei 1, 2 und 3 Punkten
  um die verwendete Zwei-Lane-Vektorbreite übereinstimmen.
- **E3.** Kann ein fester Pool von 1, 2 oder 4 Workers die Arbeit nach
  dem sequenziellen Decoder beschleunigen? Serieller Vergleich mit
  denselben Stages und Statistiken. Maximal zwei Worker-Batches pro
  Worker plus der Reader-Batch. Ein Workerslot hält Auswahlindizes
  und Statistik selbst. Schreiben und Welford-Merge erfolgen in
  Eingabereihenfolge. Der Versuch parallelisiert Crop, Klassenauswahl
  und Z-Statistik.
- **E5.** Kann eine explizit schliessbare Zuordnung mit MemorySegment den
  unkomprimierten LAS-Scan gegenüber dem verwendeten FileChannel-Pfad
  vereinfachen oder beschleunigen? Genau eine Alternative, gleicher
  Header, gleiche Punktanzahl und rohe XYZ-Summen auf dem 4.38-MB-Sample.
  Beide Varianten schliessen sämtliche Ressourcen nach jedem Scan.

```sh
./gradlew --offline -Pexperiments -Pvector installDist :experiments:installDist
bash experiments/p7.sh
```

### E2 · Vector API

Das JMH-Setup prüfte die komplette Kompaktierung der 1'730 ausgewählten
Records aus 128'909 Punkten und alle berechneten Distanzen. Skalare
Ergebnisse und Vector-API-Ergebnisse stimmten exakt überein. Die Cases
der Längen 1, 2 und 3 verwenden Treffer auf inklusiven Grenzen und einen
Punkt ausserhalb. Verwendet wird `DoubleVector.SPECIES_128` mit zwei
Lanes und eine gleich breite Klassenmaske aus zwei Integer-Lanes.

| Kernel | Skalar, µs/Batch | Vector API, µs/Batch |
| --- | ---: | ---: |
| Crop/Klasse mit Record-Kompaktierung | 128.269 ± 7.116 | 109.826 ± 3.946 |
| Quadrierte Distanz | 23.912 ± 0.982 | 23.961 ± 1.702 |

Zwei Forks, je 3 × 300 ms Warm-up und 5 × 300 ms Messung. Ein Thread,
512 MiB Max-Heap, Temurin 25.0.5+8-LTS, Inkubator-Flag ausschliesslich
für den Versuch. JMHs GC-Profiler meldet für die vier Operationen
0.55–2.94 B/Batch. Die Auswahl im Vector-Kernel erreicht ca.
1.17 Mrd. Punkte/s statt 1.00 Mrd. Punkte/s. Die Distanz liegt bei
beiden ungefähr 5.39 Mrd. Punkte/s. Beide Kernel starten mit warmen
Spalten; die 155 µs für das Befüllen aus E1 kommen für einen
einmaligen Produktlauf zusätzlich hinzu. **Isoliertes Experiment
behalten.** Ein Einbau in die CPU-Pipeline ist durch die Messung
des einmaligen Einsatzes nicht gerechtfertigt.
[JMH-Einzeliterationen](measurements/e2-vector.json)

### E3 · Parallelität nach dem Decoder

Die ganze Kachel wurde pro Prozess gelesen. Ein Reader-Batch enthält
32'768 Punkte, der Pool erhält unabhängige Recordkopien. Die
Pipeline-Arbeit umfasst eine Box, die Bodenklasse, Z-Statistik und
das Schreiben von 195'738 Records (6'655'601 B).

| Pfad | Worker | Max. gleichzeitige Batches | Median (Min–Max) | Mio. Punkte/s | Max. RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Serieller Versuch | 0 | 1 | 4.35 (4.12–4.43) s | 3.35 | 144.27 MiB |
| Pool | 1 | 3 | 3.62 (3.53–3.92) s | 4.02 | 151.39 MiB |
| Pool | 2 | 5 | 4.06 (3.98–4.12) s | 3.59 | 154.20 MiB |
| Pool | 4 | 9 | 3.70 (3.50–4.02) s | 3.94 | 159.97 MiB |
| Produkt-CLI mit denselben Stages | 0 | 1 | 3.60 (3.58–3.74) s | 4.05 | 152.36 MiB |

Je drei Prozessläufe, warmer Dateisystemcache, 512 MiB Heapgrenze.
Alle Versuchsausgaben und die drei CLI-Ausgaben waren mit der
schon gegen PDAL geprüften E1-Ausgabe bytegleich, einschliesslich
vollständiger Records und Reihenfolge. Die zusammengeführten
Z-Statistiken waren über alle Workerzahlen identisch.
Die absolute Differenz zur CLI war beim Mittel 7.96e-12 und bei
der Varianz 4.10e-11, bei Zählung und Extrema null.
Der einzelne Worker überlappt Rechen- und Schreibarbeit des
Prototyps, der Median der bestehenden CLI bleibt mindestens
gleichauf. **Pool als Produktvariante verworfen.** Der
Versuchsquellcode dokumentiert das begrenzte, geordnete Vorgehen.
[Einzelmessungen](measurements/e3-batches.csv)

### E5 · Unkomprimiertes LAS über MemorySegment

FileChannel liest wie im Produkt in einen 1'114'112-B-Recordpuffer.
Die Alternative mappt 4'382'906 B Punktdaten in eine pro Scan
geschlossene Arena und benutzt Little-Endian-Int-Zugriff. Beide
lesen 128'909 Records aus dem 4'383'415-B-Sample, prüfen dabei
rohe XYZ-Summen und schliessen die Datei. Keine gleichzeitig
laufenden Tests oder anderen Benchmarks. Jede der drei gestarteten
JVMs je Variante führte den ersten Zugriff und 20 Wiederholungen aus.

| Pfad | Erster Zugriff, Median (Min–Max) | Warmer Scan, Median der Laufmediane | Streuung aller warmen Scans | Grösstes RSS |
| --- | ---: | ---: | ---: | ---: |
| FileChannel | 11.999 (11.325–12.143) ms | 0.451 ms | 0.366–6.085 ms | 82.48 MiB |
| MemorySegment | 18.210 (17.347–21.159) ms | 0.285 ms | 0.236–1.379 ms | 58.80 MiB |

Der warme Median entspricht ca. 286 bzw. 452 Mio. Punkten/s
und umfasst Lesen, XYZ-Summen und Ressourcenschluss. Alle 126
Scans liefern identische Abmessungen und rohe XYZ-Summen
33'442'197'602'816 / 15'826'912'317'139 / 9'544'108'888.
Der Versuch misst einen begrenzten unkomprimierten Zugriff. Der
MemorySegment-Pfad bleibt **isoliertes Experiment**. Die Produkt-LAZ-
Dekodierung verwendet bereits schliessbare Mappings und war von
dieser Änderung unberührt.
[Einzelmessungen](measurements/e5-io.csv)

## E4 · Unabhängige LAZ-Chunks

Hypothese auf Basis P7 (`c0d7409`): Da die Entropiedekodierung dominiert,
kann unabhängige Arbeit an validierten Chunks mehr Gewinn liefern als
Worker hinter einem seriellen Decoder. Verglichen werden die sequenzielle
Dekodierung und ein Pool mit 2 oder 4 Workers. Alle Pfade führen dieselbe
Box-/Klassenauswahl aus und schreiben mit dem Produktwriter. Die gemessene
Zeit umfasst die Chunk-Tabelle, Aufbau von Stream und Decodern, I/O,
Auswahl und Ausgabe. Ein separater Prüflauf vergleicht sämtliche Records
der Ganzkachel bytegenau und geordnet gegen den sequenziellen Reader.

Der Experimentpfad liest Startoffset, Bytebereich und Punktbereich aus
derselben validierten Chunktabelle wie der Codec. Jeder Task hat eine
eigene Arena, einen Streamcursor, Prädiktoren und Modelle. Der erste
Record am Chunkstart initialisiert den Decoder. Je Worker sind höchstens
zwei Bufferslots unterwegs. Ein ordentlicher Abwesenheitsmarker einer
Tabelle mit fester Chunkgrösse erlaubt den sequenziellen Pfad. Eine
beschädigte Tabelle bleibt ein Eingabefehler.
[LAZ-Spezifikation 1.4 R1, rapidlasso](https://downloads.rapidlasso.de/doc/LAZ_Specification_1.4_R1.pdf)

```sh
./gradlew --offline -Pexperiments installDist :experiments:installDist
bench_cp='experiments/build/install/experiments/lib/*'
"$JAVA_HOME/bin/java" -Xmx512m -cp "$bench_cp" pointcloud.ChunkParallel \
  4 .cache/test-data/so-2019.laz .cache/experiments/e4-verified.las --verify
bash experiments/e4.sh
```

Die 2019-Datei hat 292 Chunks. Erster Chunk Byte 623–282'305
(Ende exklusiv), Punkte [0,50'000). Letzter Chunk Byte
83'134'681–83'247'146, Punkte [14'550'000,14'566'627). Der
Prüflauf mit vier Workers verglich die vollständigen 14'566'627
ungekürzten Records (495'265'318 B) bytegenau in Originalreihenfolge,
0 Abweichungen. Die resultierende Ausgabe war vollständig identisch
mit dem gegen LazPerf geprüften Ground-Crop. Exit 0, 3.70 s
für die zusätzliche sequenzielle Dekodierung und den Vergleich,
Peak RSS 313'704'448 B.

Für variable Chunks wurde mit `pdal translate .cache/experiments/kernel.las
.cache/experiments/e4-adaptive.copc.laz --writer writers.copc
--writers.copc.forward=all` ein kleines externes LAZ-Sample erzeugt.
Es besitzt fünf adaptive Chunks (1'384'445 B, Format 7,
Kompressor 3, variabler Chunkmarker). Der erste Chunk enthält
12'747 Punkte, der letzte 74'196. Derselbe Versuch mit 2 Workers
und `--verify` verglich alle 128'909 Records exakt. Die 1'730 Punkte
der Ausgabe wurden mit `pdal info --stats` gelesen (0.14 s, Exit 0).
Die Ausgabe (63'419 B) enthält das Projektions-VLR und keine
übernommenen COPC-Indexdaten. Die Verarbeitung nutzt die LAZ-Tabelle.

Am kleinen Fixchunk-Fixture wurde die Unterscheidung geprüft.
Mit absolutem Tabellenzeiger gleich Punktdatenoffset und ohne
Tabellenbytes greift der sequenzielle Pfad. Der Regressionstest
`tablelessLazRequiresTheFullDeclaredStream` vergleicht alle 50'003
rohen X-Werte und weist widersprüchliche Headerpunktzahlen zurück.
Wird an der vorhandenen Tabellenposition die vier Byte lange
Tabellenversion auf 1 gesetzt, meldet der Versuch einen Eingabefehler
und erzeugt keine Ausgabedatei.

| Pfad | Vorgesehene Batches bzw. Chunks | Median (Min–Max) | Mio. Punkte/s | Grösstes RSS |
| --- | ---: | ---: | ---: | ---: |
| Seriell | 1 × 32'768 Punkte | 3.56 (3.53–3.66) s | 4.09 | 147.42 MiB |
| 2 Workers | 4 × 50'000 Punkte | 1.96 (1.89–2.00) s | 7.43 | 226.00 MiB |
| 4 Workers | 8 × 50'000 Punkte | 1.26 (1.20–1.31) s | 11.56 | 239.86 MiB |

Je drei frische Prozesse, Apple M5 / Temurin 25.0.5+8-LTS,
warmer Dateisystemcache und maximal 512 MiB Heap. Der Beleg für
die End-to-End-Zeit umfasst die geschriebenen 195'738 Records
pro Lauf. Das 4-Worker-Ergebnis entspricht rund 2.83-fachem Durchsatz
in diesem Versuch und höherem RSS. Die virtuelle Mappingfläche
kann bei vier gleichzeitig aktiven Tasks bis zu ca. 333 MB betragen.
**Experiment behalten.** Die skalare Standardpipeline bleibt
einfach. Für einen weiteren Produktpfad müssten unter anderem
Abbruchverhalten, Speicherauslegung für breite Records, Scheduler
und mehrere Pipelineformen zusätzlich qualifiziert werden.
[Einzelmessungen](measurements/e4-chunks.csv)

### E4-Nachversuch · Vollständige Ground-Crop-Pipeline gegen PDAL

24. November 2026, Apple M1 arm64, macOS 15.8.1, 16 GiB RAM,
Temurin 25.0.3+9, `-Xmx512m`, PDAL 2.10.2/LazPerf
(conda-forge arm64, `libpdal-core h151f718_4`).

Der Versuch verwendet `examples/ground-crop.json` mit einem temporären
Ausgabeziel. Alle Varianten lesen 14'566'627 Punkte, filtern nach Box und
Klasse, berechnen X-, Y-, Z- und Intensitätsstatistik und schreiben
195'738 LAS-Records plus JSON-Metadaten. PDAL läuft mit `--stream` und
vier Reader-Workern. Die drei Java-Varianten nutzen dieselben Filter,
Statistiken, Writer und Abschlusslogik über eine interne Batch-Eingabe.

Der parallele Teil liegt im Experimentmodul. Beide Parallelvarianten
verteilen Chunks zyklisch auf vier Worker und verwenden je zwei
Punktpuffer pro Worker. Damit sind höchstens acht Chunks gleichzeitig
offen. `per-chunk` initialisiert Mapping, Decoder und Chunktabelle erneut
für jedes Arbeitspaket. `reused` validiert die Tabelle einmal und behält
Mapping und Codeczustand pro Worker. Jeder Chunk beginnt mit korrekt
initialisierten Modellen. Die Hauptpipeline verarbeitet die Batches in
Dateireihenfolge, sodass sich die Reihenfolge der Statistikoperationen
und der LAS-Records erhält.

```sh
./gradlew -Pexperiments installDist :experiments:installDist
python3 experiments/check-e4-pipeline.py --pdal "$PDAL"
python3 experiments/e4-pipeline.py "$PDAL" .cache/experiments/e4-pipeline.csv
```

`JAVA_HOME` verweist auf das verwendete JDK 25. Bei der hier verwendeten
PDAL-Installation verweist `PROJ_DATA` auf `share/proj` im Conda-Prefix.
Der Runner wärmt den Dateicache, prüft einen Lauf je Variante und misst
danach fünf frische Prozesse je Variante in rotierender Reihenfolge.
`/usr/bin/time -lp` erfasst Wandzeit, Prozess-CPU-Zeit und Spitzen-RSS.
Ausserhalb der Zeitmessung prüft der Runner jede Ausgabe gegen die
serielle Java-Referenz. Die Punktrecords müssen bytegleich und geordnet
sein, die Java-Metadaten bytegleich. Für PDAL gilt die P5-Statistiktoleranz.

| Variante | Decoder-Worker | Wandzeit, Median (Min–Max) | CPU-Zeit, Median | Mio. Eingangspunkte/s | Höchster RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Java seriell | 1 | 5.11 (5.08–5.42) s | 5.54 s | 2.85 | 154.84 MiB |
| Java `per-chunk` | 4 | 1.62 (1.55–2.21) s | 7.08 s | 8.99 | 191.27 MiB |
| Java `reused` | 4 | 1.54 (1.51–1.99) s | 6.86 s | 9.46 | 175.72 MiB |
| PDAL `--stream` | 4 | 2.85 (2.81–3.24) s | 7.96 s | 5.11 | 134.95 MiB |

Alle vier Vorläufe und 20 Messläufe waren erfolgreich. Jede Ausgabe
enthielt 195'738 vollständige Records in gleicher Reihenfolge, mit null
Abweichungen zur seriellen Java-Referenz. Die Java-Metadaten waren
bytegleich. Für PDAL stimmten Minimum und Maximum exakt. Die maximalen
absoluten Differenzen bei Mittelwert, Varianz und Standardabweichung
lagen bei `4.40e-4`, `3.29e-3` und `3.49e-9`, innerhalb der P5-Toleranz.

`reused` erzielte den 3.32-fachen Durchsatz gegenüber seriellem Java und
den 1.85-fachen gegenüber PDAL in dieser Messreihe. Die Wiederverwendung
senkte den Median gegenüber `per-chunk` um 0.08 s und den höchsten RSS um
15.55 MiB. Der schnellste Java-Pfad beanspruchte mehr Spitzen-RSS als
PDAL. Das Ergebnis gilt für diese Kachel, Pipeline und Maschine.
[Einzelmessungen](measurements/e4-pipeline.csv)

**Als Experiment behalten.** Die gemessene Beschleunigung rechtfertigt
eine spätere Prüfung als Produktoption. Vor einer solchen Entscheidung
sind unterschiedliche Kachelgrössen und Chunkverteilungen, das
Speicherverhalten breiter Records und weitere Laufumgebungen zu messen.

### Produktabnahme · `--laz-workers 4`

24. November 2026, Apple M1 arm64, macOS 15.8.1, 16 GiB RAM,
Temurin 25.0.3+9, `-Xmx512m` und PDAL 2.10.2/LazPerf
(conda-forge arm64, `libpdal-core h151f718_4`). Der Vergleich startet
die installierte Produkt-CLI einmal mit dem seriellen Standard und
einmal mit `--laz-workers 4`. PDAL verwendet `--stream` mit vier
`readers.las`-Workern. Autzen wird in physischer LAZ-Dateireihenfolge gelesen.

Solothurn 2014 berechnet Statistiken auf allen Punkten und schreibt
mit Schrittweite 500 exakt 28'695 Records. Autzen wählt jeden hundertsten
Punkt aus und berechnet Statistik inklusive RGB auf den 106'534 Treffern.
Die Eingaben bleiben vollständig gelesen. Je Datensatz liegen ein
Vorvergleich und drei rotierende Messrunden pro Variante vor. Der
Dateicache ist warm. Dateivergleich und Statistikprüfung sind ausserhalb
der Zeitmessung.

```sh
./gradlew installDist
./gradlew downloadTestData -Ptile=2014
./gradlew downloadTestData -Ptile=autzen
python3 tools/check-parallel-data.py "$PDAL" .cache/experiments/parallel-real-data.csv
```

| Datensatz | Variante | Wandzeit, Median (Min–Max) | CPU-Zeit, Median | Höchster RSS |
| --- | --- | ---: | ---: | ---: |
| Solothurn 2014 | Java seriell | 5.32 (5.28–5.40) s | 5.74 s | 147.03 MiB |
| Solothurn 2014 | Java, vier Worker | 1.50 (1.46–1.54) s | 6.97 s | 161.92 MiB |
| Solothurn 2014 | PDAL, vier Worker | 2.76 (2.74–2.84) s | 7.54 s | 106.22 MiB |
| Autzen | Java seriell | 5.02 (5.01–5.05) s | 5.75 s | 327.78 MiB |
| Autzen | Java, vier Worker | 1.76 (1.72–2.48) s | 7.21 s | 358.20 MiB |
| Autzen | PDAL, vier Worker | 2.39 (2.16–2.56) s | 7.15 s | 190.50 MiB |

Java seriell und parallel lieferten bytegleiche LAS- und JSON-Dateien.
Alle 28'695 Solothurner und 106'534 Autzen-Punktrecords stimmten auch mit
PDAL bytegenau und in derselben Reihenfolge überein. Die Referenz las
die Java-Ausgaben vollständig. CRS und Statistik bestanden den Vergleich.
Bei Solothurn bewahrt Java das Vorzeichenbit der drei Null-Offsets aus
dem Quellheader. PDAL schreibt positive Nullen. In Autzens minimaler
Z-Ausgabegrenze stehen die benachbarten Double-Werte `406.29999999999995`
und `406.3`. Diese Rundung betrifft die abgeleitete Grenze, während
Quantisierung und gespeicherte Punktrecords übereinstimmen.
[Einzelmessungen](measurements/parallel-real-data.csv)

Vier Java-Worker erreichten auf dem M1 gegenüber PDAL den 1.84-fachen
Durchsatz für Solothurn 2014 und den 1.36-fachen für Autzen. Die
Autzen-Laufzeitbereiche überlappen. Der Spitzen-RSS des Java-Pfads war
auf beiden Datensätzen höher. Die CLI bietet den Parallelpfad explizit
über `--laz-workers` an und liest standardmässig seriell.

### Laufzeitvergleich nach Übernahme von `0a17961`

Verglichen wurden der Origin-Stand `1c3a069` und die CLI nach Übernahme
der lokalen Änderungen. Beide liefen auf dem Apple M5 mit macOS 27.0.1,
24 GiB RAM, Temurin 25.0.5+8 arm64 und `-Xmx512m`. Die Pipeline
las jeweils 14'566'627 Punkte aus Solothurn 2019 und schrieb 195'738
Punkte sowie das JSON mit der Statistik. Der serielle Batch hatte
32'768 Punkte, die LAZ-Chunks im Parallelpfad bis zu 50'000 Punkte.

Beide Stände wurden mit `./gradlew --offline clean check installDist distZip`
gebaut. Die Origin-Installation wurde unter
`.cache/port-0a17961/origin` gesichert. Die Messaufrufe starteten ab
Repositorywurzel mit `cli` als `.cache/port-0a17961/origin/bin/pointcloud`
oder `build/install/pointcloud/bin/pointcloud` und `workers` als 1 oder 4.

```sh
JAVA_OPTS=-Xmx512m LC_ALL=C /usr/bin/time -lp "$cli" \
  pipeline examples/ground-crop.json --laz-workers "$workers" --metadata "$result"
```

Nach dem Vorwärmen des Dateisystemcaches lief jeder Pfad einmal zur
Korrektheitsprüfung und danach fünfmal in einem jeweils frischen Prozess.
Die Reihenfolge Origin/1, Port/1, Origin/4, Port/4 wechselte pro
Runde mit der umgekehrten Reihenfolge. Vergleiche und Entfernen der
Ausgaben liefen ausserhalb der Messung. Die LAS-Datei und das Statistik-JSON
waren in allen Läufen bytegleich. Die Messung lief ohne konkurrierende Builds,
Tests oder Benchmarks.

| Stand | Worker | Wandzeit, Median (Min–Max) | Mio. Punkte/s | Max. RSS |
| --- | ---: | ---: | ---: | ---: |
| Origin | 1 | 3.59 (3.54–3.63) s | 4.06 | 153.91 MiB |
| Übernahme | 1 | 3.60 (3.58–4.10) s | 4.05 | 152.88 MiB |
| Origin | 4 | 1.17 (1.10–1.23) s | 12.45 | 173.38 MiB |
| Übernahme | 4 | 1.20 (1.13–1.22) s | 12.14 | 173.80 MiB |

Die gemessenen Medianzeiten stiegen um 0.3 % und 2.6 %. Bei vier Workern
war der Median der paarweisen Zeitdifferenzen pro Runde 0.00 s.
Die Messbereiche überlappen. Die Serie zeigt für Ground-Crop
keine konsistente Verlangsamung. CRS-Ausgabe, zusätzliche Pfadprüfung
und Hardlink-Veröffentlichung liegen ausserhalb der Punktschleife.
Beim Chunkabschluss prüft eine zusätzliche Bedingung das Dateiende
für den seriellen Fall ohne Tabelle.
[Einzelmessungen](measurements/port-0a17961-runtime.csv)

### JFR der parallelen Produktpipeline

24. November 2026, Produktcode `1c3a069`, Apple M1 arm64, macOS 15.8.1,
16 GiB RAM, Temurin 25.0.3+9, `-Xmx512m` und PDAL 2.10.2/LazPerf
(`libpdal-core h151f718_4`). Dieselben beiden Pipelines und Eingaben wie
in der Produktabnahme. Mit warmem Dateicache liefen drei rotierende
Messrunden ohne JFR und separat je fünf Aufnahmen mit vier Java-Workern
sowie je eine serielle Kontrolle. Jeder Lauf startete einen neuen Prozess.

```sh
./gradlew installDist
python3 tools/check-parallel-data.py "$PDAL" .cache/experiments/parallel-jfr-unprofiled-2026-11-24.csv
python3 tools/check-parallel-data.py "$PDAL" --profile-dir .cache/experiments/parallel-jfr-2026-11-24
```

Für jede Aufnahmeserie einen leeren Zielordner wählen. Er enthält nach
erfolgreicher Ausführung die zwölf `.jfr`-Dateien und `summary.json` mit
den Profilwerten pro Lauf. Die Java-LAS und -JSON waren in jedem Lauf
bytegleich zum seriellen Referenzlauf. PDAL bestätigte mit den bisherigen
Toleranzen Statistik und CRS sowie alle Punktrecords in Reihenfolge
(28'695 für Solothurn, 106'534 für Autzen).

| Datensatz | Variante | Wandzeit, Median (Min–Max) | CPU-Zeit, Median | Höchster RSS |
| --- | --- | ---: | ---: | ---: |
| Solothurn 2014 | Java seriell | 5.08 (5.07–5.11) s | 5.48 s | 144.55 MiB |
| Solothurn 2014 | Java, vier Worker | 1.45 (1.43–1.47) s | 6.85 s | 163.45 MiB |
| Solothurn 2014 | PDAL, vier Worker | 2.73 (2.73–2.77) s | 7.47 s | 84.73 MiB |
| Autzen | Java seriell | 4.99 (4.99–5.00) s | 5.67 s | 334.89 MiB |
| Autzen | Java, vier Worker | 1.72 (1.67–1.74) s | 7.03 s | 357.61 MiB |
| Autzen | PDAL, vier Worker | 2.04 (2.03–2.09) s | 6.24 s | 166.98 MiB |

JFR verwendet `settings=profile` mit 2 ms Abtastintervall für Java-Stacks,
100 ms für Thread-CPU-Last und einer Schwelle von 100 µs für Parks. Alle
vier Decoder-Threads wurden in jedem parallelen Profil erkannt. JFR
meldete keinen Datenverlust. Für die serielle Kontrolle zählen die
Dekodieraufrufe im Hauptthread, für den Parallelpfad die Worker-Stacks.

| Vier Worker, Median (Min–Max) | Solothurn 2014 | Autzen |
| --- | ---: | ---: |
| Java-Worker-Stichproben | 1'990 (1'915–2'023) | 2'219 (2'199–2'252) |
| `decodeSymbol` oberster Stackframe | 67.6 % (60.4–69.5 %) | 60.8 % (58.3–62.0 %) |
| `decodeSymbol` irgendwo im Stack | 69.7 % (62.6–71.8 %) | 64.6 % (62.8–66.0 %) |
| Hauptthread wartet auf nächsten Chunk | 641 (551–686) ms | 1'510 (1'491–1'582) ms |
| Davon mindestens ein Worker wartet auf einen Auftrag | 6.9 (5.2–9.6) ms | 303 (297–316) ms |
| Anteil dieser Überlappung | 1.3 % (0.8–1.4 %) | 20.0 % (19.3–20.7 %) |

`decodeSymbol` war in allen fünf Läufen je Datensatz der häufigste
oberste Frame. Danach folgten unter anderem `IntegerCompressor.readCorrector`
und die jeweiligen `POINT10`- bzw. `POINT14`-Reader. Die Parkzeiten stammen
aus den profilierten Läufen. Die Überlappung zählt Zeit mit mindestens einem
wartenden Worker einmal und berücksichtigt geordnete Chunk-Abfragen des
Hauptthreads während der Verarbeitung.

Bei Autzen summierten sich die gewichteten JFR-Allokationsstichproben pro
parallelem Lauf median auf 919 MiB (875–935 MiB). Die Kategorien lagen bei
456 MiB für POINT14-Records, 350 MiB für RGB-Records, 109 MiB für
Layer-Arrays und 21 MiB für `PointBatch`-Puffer (jeweils eigene Mediane).
Die beiden Record-Klassen machten pro Lauf zusammen 80–88 % des Gewichts
aus. Autzen hatte pro Lauf sieben GC-Pausen mit zusammen 12.4–14.0 ms.
Solothurn lag bei rund 34 MiB gewichtetem Allokationsvolumen und hatte
keine GC-Pause. Die Stichproben schätzen das Allokationsvolumen. Die
separate RSS-Messung erfasst die maximale Speicherbelegung des Prozesses.

Als Nächstes die Symbolsuche in `ArithmeticDecoder.decodeSymbol` in einem
isolierten A/B-Versuch angehen. Die Entscheidung über eine Übernahme fällt
anhand unprofilierter End-to-End-Läufe mit bytegleichen Java-Ausgaben.
Für einen späteren Speicherversuch mit Autzen sind die beiden
Record-Allokationen der grössere Ansatzpunkt als die Layer-Arrays.

### A/B-Versuch · Symbolsuche im Arithmetic Decoder

Ausgangspunkt ist `1c3a069`. In den zehn parallelen JFR-Aufnahmen lagen
5'523 von 6'591 Solothurner und 6'167 von 6'697 Autzen-Stichproben mit
`decodeSymbol` als oberstem Frame am Rücksprung der tabellengestützten
Bisektion. B ersetzt deren Entscheidung bei jedem Mittelpunkt durch
eine Vorzeichenmaske. Quotient und kumulative Häufigkeit sind
nichtnegative `int`-Werte, sodass das Vorzeichen ihrer Differenz die
Vergleichsrichtung angibt. Die Maske ist bei Gleichheit null, wodurch
wie in A die untere Suchgrenze auf den Mittelpunkt rückt.

```java
int upper_mask = (u_dv - m.u_distribution[u_k]) >> 31;
u_n ^= (u_n ^ u_k) & upper_mask;
u_sym ^= (u_sym ^ u_k) & ~upper_mask;
```

Die beiden installierten Distributionen unterschieden sich im
`codec.jar`. Der Vergleich lief auf Apple M1 arm64, macOS 15.8.1,
Temurin 25.0.3+9, `-Xmx512m` und mit warmem Dateicache. Je Kombination
aus Datensatz und Workerzahl starteten sieben Paare frischer Prozesse
in abwechselnder A–B- und B–A-Reihenfolge. `/usr/bin/time -lp` erfasste
Wandzeit, User- und System-CPU-Zeit sowie Spitzen-RSS ohne JFR. Die
Pipelines entsprechen der Produktabnahme. Jeder Ergebnisvergleich
folgte ausserhalb der Zeitmessung. PDAL 2.10.2/LazPerf mit vier Workern
war die Referenz im Vorvergleich.

```sh
python3 tools/check-full-records.py "$baseline_cli" "$candidate_cli" \
  .cache/test-data/so-2014.laz .cache/test-data/autzen-classified.copc.laz
python3 tools/check-parallel-data.py "$PDAL" .cache/experiments/decode-symbol-ab.csv \
  --ab "$baseline_cli" "$candidate_cli"
```

| Datensatz | Worker | A, Median (Min–Max) | B, Median (Min–Max) | Gepaarte Änderung B/A | Siege B |
| --- | ---: | ---: | ---: | ---: | ---: |
| Solothurn 2014 | 1 | 5.18 (5.10–5.34) s | 5.34 (5.28–5.47) s | +3.49 % | 1/7 |
| Solothurn 2014 | 4 | 1.47 (1.45–1.47) s | 1.54 (1.50–1.59) s | +5.44 % | 0/7 |
| Autzen | 1 | 5.03 (5.00–5.10) s | 5.22 (5.16–5.51) s | +3.98 % | 0/7 |
| Autzen | 4 | 1.74 (1.70–1.76) s | 1.66 (1.63–1.69) s | −5.11 % | 7/7 |

Die gepaarte Änderung ist der Median der Quotienten aus B- und A-Wandzeit
minus eins. Bei Autzen mit vier Workern sank die mediane Prozess-CPU-Zeit
von 7.17 auf 6.44 s und der höchste Spitzen-RSS von 356.56 auf 327.78 MiB.
[56 Einzelmessungen mit CPU und RSS](measurements/decode-symbol-ab.csv)

Alle vollständigen Ausgaben lieferten pro Datensatz denselben SHA-256
über die Rohrecords in Dateireihenfolge. Geprüft wurden 14'347'366 Records
für Solothurn und 10'653'336 für Autzen in beiden Varianten und mit beiden
Workerzahlen. Die dezimierten Java-LAS und -JSON waren in den Vor- und
Messläufen bytegleich. Die PDAL-Vorvergleiche bestanden für Rohrecords,
Statistik und CRS mit den Toleranzen der Produktabnahme.

**A bleibt Produktstandard.** B erreichte bei Autzen mit vier Workern
die geforderten 5 % Beschleunigung und mindestens sechs Siege. Die
Verlangsamung überstieg in den drei anderen Messreihen die vereinbarte
Grenze von 2 %. Der lokale Versuchsordner enthält beide Distributionen
und den exakten Patch für B.

### A/B-Versuch · Feinere Suchtabelle im Arithmetic Decoder

25. November 2026, Apple M1 arm64, macOS 15.8.1, 16 GiB RAM,
Temurin 25.0.3+9 mit `-Xmx512m`, PDAL 2.10.2/LazPerf. A ist
`2b10708`. Geprüft wird, ob feinere Tabellenintervalle weniger
Bisektionsschritte und eine kürzere vollständige Pipeline ergeben.

Ein separater Build mit dem [Diagnosepatch](../experiments/patches/laz-table-diagnostics.patch)
hat beide Dateien komplett mit `writers.null` und je einem bzw. vier
Workern dekodiert. Die Thread-Ausgabe aus stderr enthält je Alphabetgrösse
die Aufrufe mit 0, 1 und mindestens 2 Suchschritten, deren Gesamtsumme
sowie die Zahl der Tabellenaktualisierungen. Die aufsummierten
Suchschritte stimmten zwischen den Workerzahlen exakt überein.
[Diagnose-Rohdaten](measurements/laz-table-width-diagnostics.csv)

| Alphabetgrösse | Suchschritte Solothurn 2014 | Suchschritte Autzen |
| ---: | ---: | ---: |
| 33 | 37'099'784 | 24'720'025 |
| 64 | 30'302'420 | 9'504'208 |
| 128 | 10'586'526 | 19'039'527 |
| 256 | 46'646'122 | 91'751'715 |
| Alle Tabellenmodelle | 159'897'122 | 164'478'780 |

Die Vereinigung der je drei stärksten Alphabetgrössen im Bereich 17–512
ergibt 33, 64, 128 und 256. Auf diese vier entfallen 78.0 % der
Suchschritte in Solothurn und 88.2 % in Autzen. Die Kandidaten ergänzen
für diese Grössen ein bzw. zwei Indexbits in `ArithmeticModel.init`.
Für eine Reproduktion der Messung A bei `2b10708` auschecken. Den
[Kandidatenpatch](../experiments/patches/laz-table-width.patch) für +1
in einem separaten Worktree von A anwenden. Für +2 in einer weiteren
Kopie von A den Fallwert im Patch von 1 auf 2 ändern. Die drei
Varianten mit `./gradlew --offline clean check installDist` bauen.
Sogar am maximalen gültigen Quotienten 32'831 liegen beide Zugriffe
innerhalb der Tabelle, da die feinste Variante 128 Quotienten pro
Tabellenintervall verwendet. Die Verteilung, adaptive Aktualisierung
und Symbolsuche entsprechen A. In den gemessenen Distributionen
unterschied sich nur `codec.jar`.

Zeitmessung in frischen Prozessen mit warmem Dateicache, je sieben
wechselnde A/B-Paare pro Pipeline und Workerzahl. `perf_counter_ns()`
erfasst die Wandzeit. `/usr/bin/time -lp` liefert User- und System-CPU
sowie Spitzen-RSS. Jede Java-LAS- und JSON-Ausgabe wird nach der
Messung bytegenau verglichen. PDAL 2.10.2 mit vier Reader-Workern
prüft die ausgewählten Punktrecords, Statistik und CRS im Vorvergleich.
Die Autzen-Läufe mit `writers.null` verwenden je drei Paare bei vier
Workern.

```sh
python3 tools/check-full-records.py "$baseline_cli" "$plus1_cli" \
  .cache/test-data/so-2014.laz .cache/test-data/autzen-classified.copc.laz \
  --additional-candidate "$plus2_cli"
python3 tools/check-parallel-data.py "$PDAL" "$out/ab.csv" --ab "$baseline_cli" "$candidate_cli"
python3 tools/check-parallel-data.py "$PDAL" "$out/reader.csv" \
  --ab "$baseline_cli" "$candidate_cli" --reader-null
```

Die ursprüngliche Vorgabe für die Hauptmatrix verlangte mindestens
5 % gepaarten medianen Wandzeitgewinn und mindestens sechs Siege in
einer Vier-Worker-Reihe. Jede Reihe durfte höchstens 2 % Wandzeit und
10 % Spitzen-RSS zulegen. Bei zwei geeigneten Kandidaten hatte die
kleinere Tabelle Vorrang.

| Datensatz | Worker | Extra-Bits | A, Median (Min–Max) | Kandidat, Median (Min–Max) | Wandzeit | CPU | Spitzen-RSS | Siege |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Solothurn 2014 | 1 | +1 | 5.158 (5.088–5.193) s | 4.984 (4.907–5.022) s | −3.52 % | −2.17 % | +1.60 % | 7/7 |
| Solothurn 2014 | 4 | +1 | 1.481 (1.430–1.663) s | 1.416 (1.380–1.433) s | −3.85 % | −3.86 % | +0.96 % | 7/7 |
| Autzen | 1 | +1 | 5.016 (4.961–5.378) s | 4.790 (4.730–4.855) s | −4.60 % | −3.68 % | +1.38 % | 7/7 |
| Autzen | 4 | +1 | 1.717 (1.692–1.760) s | 1.686 (1.663–1.703) s | −3.07 % | −1.66 % | +1.67 % | 6/7 |
| Solothurn 2014 | 1 | +2 | 5.182 (5.092–5.346) s | 4.844 (4.805–4.960) s | −4.88 % | −4.55 % | +0.60 % | 7/7 |
| Solothurn 2014 | 4 | +2 | 1.458 (1.439–1.516) s | 1.416 (1.395–1.596) s | −4.31 % | −3.59 % | +0.57 % | 5/7 |
| Autzen | 1 | +2 | 5.041 (4.980–5.369) s | 4.620 (4.595–4.706) s | −7.72 % | −6.83 % | −0.31 % | 7/7 |
| Autzen | 4 | +2 | 1.713 (1.682–1.762) s | 1.650 (1.620–1.708) s | −3.32 % | −1.01 % | +1.96 % | 7/7 |

Prozentwerte sind Mediane der Verhältnisse innerhalb desselben Paars
minus eins. In den +2-Reihen mit vier Workern stieg der mediane
Eingabedurchsatz von 9.84 auf 10.13 Mio. Punkte/s (Solothurn) und von
6.22 auf 6.46 Mio. Punkte/s (Autzen). Autzen mit `writers.null` war
mit +1 gepaart 1.52 % schneller (2/3 Siege) und mit +2 6.41 % schneller
(3/3 Siege). [124 Einzelmessungen](measurements/laz-table-width-ab.csv)

In nachgelagerten Diagnosebuilds mit vier Workern sank die Zahl der
Suchschritte mit +1 um 28.05 % auf Solothurn und 32.44 % auf Autzen.
Mit +2 sank sie um 48.32 % bzw. 55.35 %. Die Zahl der
tabellengestützten Symbolaufrufe war für jeden Datensatz gleich.

Nach der Hauptmatrix blieb A Produktstandard. Beide Kandidaten
erfüllten die Korrektheits- und RSS-Vorgaben. Der grösste vollständige
Vier-Worker-Gewinn betrug 4.31 % bei fünf Siegen. Für +2 begründeten
der kleine Codec-Diff und die durchgehenden Gewinne im seriellen
Standardpfad eine zusätzliche Prüfung auf Solothurn 2019.

#### Ground-Crop-Bestätigung für +2

25. November 2026, gleiche M1-Umgebung und dieselben A- und +2-Builds
wie in der Hauptmatrix. Die Beispielpipeline `examples/ground-crop.json`
liest 14'566'627 Punkte und schreibt nach räumlichem Zuschnitt,
Bodenfilter und Statistik 195'738 unkomprimierte LAS-Records.
Die Ausgaben liegen je Lauf in einem temporären Verzeichnis.

```sh
python3 tools/check-parallel-data.py "$PDAL" "$out/ground-crop-plus-2.csv" \
  --ab "$baseline_cli" "$plus2_cli" --ground-crop
```

Je Workerzahl liefen sieben alternierende A/B-Paare in frischen
Prozessen bei warmem Dateicache. Alle 28 gemessenen Läufe und die
drei Java-Vorläufe lieferten bytegleiche LAS- und JSON-Dateien wie A
seriell. PDAL 2.10.2 mit vier Reader-Workern bestätigte alle
195'738 Rohrecords in Dateireihenfolge, Statistik und CRS. PDAL
öffnete auch die Java-Ausgabe und bestätigte die Recordzahl.

| Worker | Variante | Wandzeit, Median (Min–Max) | Prozess-CPU, Median | Höchster Spitzen-RSS | Mio. Eingabepunkte/s |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | A | 5.143 (5.122–5.184) s | 5.54 s | 155.16 MiB | 2.832 |
| 1 | +2 | 4.810 (4.791–5.105) s | 5.24 s | 154.23 MiB | 3.028 |
| 4 | A | 1.569 (1.539–1.642) s | 6.72 s | 184.56 MiB | 9.282 |
| 4 | +2 | 1.465 (1.433–1.517) s | 6.47 s | 180.30 MiB | 9.941 |

| Worker | Gepaarte Wandzeit | Gepaarte Prozess-CPU | Gepaarter Spitzen-RSS | Schnellere Paare |
| ---: | ---: | ---: | ---: | ---: |
| 1 | −6.09 % | −5.52 % | +0.35 % | 7/7 |
| 4 | −5.44 % | −4.51 % | +1.12 % | 7/7 |

Die gepaarten Änderungen sind Mediane der Quotienten +2/A je Paar
minus eins. Der Durchsatz verwendet die jeweilige mediane Wandzeit.
[28 Einzelmessungen](measurements/laz-table-width-ground-crop.csv)

**Der Produktcodec übernimmt die gemessene +2-Variante.** Beim
ersten Aufbau der Dekodiertabelle in `ArithmeticModel.init` kommen
für die Alphabetgrössen 33, 64, 128 und 256 jeweils zwei Indexbits
hinzu. Beide Ground-Crop-Reihen bestanden die Bestätigungsgrenzen
von höchstens 2 % Wandzeit- und 10 % RSS-Anstieg. +2 war in allen
14 Paaren schneller und bestätigte die Gewinne aus der Hauptmatrix
auf einer weiteren vollständigen Pipeline.

### POINT14/RGB, gemeinsame Queue und Layer-Puffer

Apple M1 arm64, macOS 15.8.1, Temurin 25.0.3+9, `-Xmx512m`,
PDAL 2.10.2/LazPerf (`libpdal-core h151f718_4`). Drei unabhängige
Distributionen auf Basis des Produktcodes `1c3a069`.

- **Records.** POINT14 und RGB14 halten je ein eigenes Ausgabeobjekt pro
  Reader. Die Wiederverwendung betrifft die Ausgabe, die sofort in den
  LAS-Batch kopiert wird. [Patch](../experiments/patches/laz-record-scratch.patch)
- **Queue.** Eine gemeinsame FIFO versorgt die Worker. Das Fenster
  bleibt auf zwei Puffer pro effektivem Worker beschränkt, und der
  Hauptthread übernimmt die Ergebnisse in Chunk-Reihenfolge.
  [Patch und Refill-Fixture](../experiments/patches/laz-shared-queue.patch)
- **Layer.** Der Codec lädt den validierten Payload einmal in maximal
  8 MiB wiederverwendbaren Speicher pro Decoder. POINT14, RGB14, RGBNIR14
  und BYTE14 lesen aus Little-Endian-Sichten mit exakten Grenzen. Grössere
  gültige Payloads verwenden den bisherigen Ladepfad.
  [Patch und Slice-Tests](../experiments/patches/laz-layer-scratch.patch)

Für eine Reproduktion separat `git worktree add --detach` von `1c3a069`
ausführen, je einen Patch anwenden und mit `./gradlew --offline clean check
installDist` bauen. Von diesem Arbeitsbaum aus die installierten Launcher
als `baseline_cli` und `candidate_cli` übergeben. Der ausgewählte Launcher
bestimmt die Variante.

```sh
python3 tools/check-full-records.py "$baseline_cli" "$candidate_cli" \
  .cache/test-data/so-2014.laz .cache/test-data/autzen-classified.copc.laz
python3 tools/check-parallel-data.py "$PDAL" "$output_dir/full.csv" \
  --ab "$baseline_cli" "$candidate_cli"
python3 tools/check-parallel-data.py "$PDAL" "$output_dir/reader.csv" \
  --ab "$baseline_cli" "$candidate_cli" --reader-null
python3 tools/check-parallel-data.py "$PDAL" --profile-dir "$output_dir/jfr" \
  --profile-cli "$candidate_cli"
```

Die vollständige Pipeline lief für Solothurn 2014 und Autzen mit je einem
und vier Workern in sieben abwechselnden A/B-Paaren. Der isolierte
Reader-Kontrolllauf auf Autzen verwendete drei Paare mit vier Workern und
`writers.null`. Alle Prozesse starteten frisch bei warmem Dateicache.
Wandzeit, Prozess-CPU-Zeit und Spitzen-RSS stammen aus Läufen ohne JFR.
Die JFR-Aufnahmen profilieren separat pro Build und Datensatz fünf
Vier-Worker-Prozesse und einen seriellen Kontrollprozess.

Die vorab vereinbarte Grenze für eine Verlangsamung beträgt 2 % pro
Messreihe. Auf Autzen mit vier Workern sind mindestens 5 % Wandzeitgewinn
und sechs Siege aus sieben Paaren erforderlich. Für Records und Layer
gilt alternativ ein um mindestens 10 % niedrigerer Spitzen-RSS mit
sechs Siegen und nachweislich niedrigeren Zielallokationen.

| Kandidat | Wandzeit SO 1 | Wandzeit SO 4 | Wandzeit Autzen 1 | Wandzeit Autzen 4 | Spitzen-RSS Autzen 4 | Reader-Null Autzen 4 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Records | +0.77 % | +2.07 % | −0.60 % | −1.73 % | −30.25 % | −5.08 % |
| Queue | −0.19 % | −1.95 % | −0.59 % | −2.82 % | +0.20 % | 0.00 % |
| Layer | +0.19 % | +1.92 % | +0.40 % | +3.17 % | +1.58 % | −1.09 % |

Die Tabelle zeigt den Median der gepaarten Verhältnisse B/A minus eins.
Auf Autzen mit vier Workern lagen die Wandzeit-Mediane bei 1.73/1.70 s
für A/Records, 1.77/1.72 s für A/Queue und 1.83/1.86 s für A/Layer.
Die Kandidaten gewannen dort bei der Wandzeit fünf, sechs beziehungsweise
zwei von sieben Paaren. Die Prozess-CPU-Zeit änderte sich gepaart um
−3.01 %, +2.92 % beziehungsweise +1.52 %. Der Record-Kandidat gewann
alle sieben RSS-Paare. Der Median der gemessenen Spitzen-RSS-Werte sank
von 353.62 auf 246.20 MiB. [186 Einzelmessungen](measurements/laz-reuse-ab.csv)

Die separat aufgenommenen Profile zeigten auf Autzen mit vier Workern
folgende Änderungen. Jede Zahl ist der Median aus fünf Aufnahmen.

| Zielgrösse | A | Zuständiger Kandidat |
| --- | ---: | ---: |
| Gewichtete POINT14-Allokationsstichproben | 418.4 MiB | 0.0 MiB (Records) |
| Gewichtete RGB-Allokationsstichproben | 336.3 MiB | 0.0 MiB (Records) |
| GC-Pausen insgesamt | 13.57 ms | 2.60 ms (Records) |
| Chunk-Wartezeit mit mindestens einem untätigen Worker | 292.7 ms | 18.9 ms (Queue) |
| Gewichtete Layer-Array-Allokationsstichproben | 84.26 MiB | 2.89 MiB (Layer) |

Die Stichproben schätzen das Allokationsvolumen. Die Queue-Überlappung
beschreibt den gleichzeitigen Wartezustand von Hauptthread und Workern
im Profil. Die separat gemessene Wandzeit entscheidet über den Nutzen
für die Pipeline. Alle 48 Aufnahmen enthielten die erwarteten
Decoder-Threads und meldeten keinen Datenverlust.

**A bleibt Produktstandard.** Die Records erreichen das RSS-Ziel.
Auf Solothurn mit vier Workern überschreiten sie die zulässige
Wandzeitabweichung von 2 % um 0.07 Prozentpunkte. Die Queue
erreicht sechs Siege, ihr gepaarter Wandzeitgewinn bleibt unter 5 %.
Der Layer-Kandidat überschreitet auf Autzen mit vier Workern die
Wandzeitgrenze. Die drei Varianten bleiben als getrennte Patches verfügbar.

## E6 und E7 · JVM-Versionen, AOT-Cache und Native Image

Basis ist der Standardbuild nach P8 (`978f38d`). Zweite VM ist Temurin
27 auf derselben Architektur. Temurin 25 wird mit Standardstart und
mit strikt angefordertem AOT-Cache verglichen. Die Hypothese ist,
dass Laufzeit und AOT den Prozessstart stärker beeinflussen als
die lange Dekodierung. Das lokale Native Binary soll Startzeit
und RSS reduzieren können. Buildzeit, Grösse und voller Durchsatz
werden getrennt geprüft. Die beiden Aufgaben sind eine kleine
Stats-Pipeline auf einer Offline-Fixture und die vollständige
Ground-Crop-Pipeline. Gefordert sind identische Metadaten und
bytegleiche Punktrecords bei allen Laufzeiten.

### Aufbau und Reproduktion

Temurin 25.0.5+8-LTS und 27.0.1.1+4 sind aarch64. Das zusätzliche
Temurin-JDK stammt aus dem Adoptium-Release `jdk-27.0.1.1+4`,
138'176'968 B Download, mit übereinstimmender veröffentlichter SHA-256.
Es liegt in `.cache/jdks/temurin27`. Die lokale Oracle-GraalVM-Version
ist 25.0.4.1.1 aarch64 mit Native Image und Apple clang 21.0.0.
Vor Download und Build waren 414 GiB frei.
[Adoptium-Release](https://github.com/adoptium/temurin27-binaries/releases/tag/jdk-27.0.1.1%2B4) ·
[Java-25-AOT-Optionen](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)

Die Befehle verwenden die JARs der installierten JVM-Distribution.
Für die getrennte Warm-up-Messung kommt eine kleine Messklasse als
separates JAR auf den Klassenpfad. Die Ground-Crop-Referenz aus E1
liegt unter `.cache/experiments/e1-baseline.las` und `e1-baseline.json`.
Fehlt sie, wird sie abseits der Messung mit dem installierten
Standardprodukt erzeugt. `JAVA_HOME` zeigt auf Temurin 25,
`graal` auf GraalVM und `java27` auf den Java-27-Launcher.

```sh
./gradlew --offline installDist
work=.cache/experiments/p9
graal=/pfad/zu/graalvm
java27=.cache/jdks/temurin27/Contents/Home/bin/java
mkdir -p "$work/classes"
product_cp=$(printf '%s\n' build/install/pointcloud/lib/*.jar | paste -sd ':' -)
measure_cp="$product_cp:$work/warmup.jar"
"$JAVA_HOME/bin/javac" -cp "$product_cp" -d "$work/classes" experiments/RuntimeWarmup.java
"$JAVA_HOME/bin/jar" --create --file "$work/warmup.jar" -C "$work/classes" .
if [ ! -f .cache/experiments/e1-baseline.las ]; then
  JAVA_OPTS=-Xmx512m build/install/pointcloud/bin/pointcloud pipeline examples/ground-crop.json \
    > .cache/experiments/e1-baseline.json
  mv build/results/ground-crop.las .cache/experiments/e1-baseline.las
fi
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -XX:AOTMode=record \
  -XX:AOTConfiguration="$work/ground.aotconfig" -cp "$measure_cp" \
  pointcloud.Main pipeline examples/ground-crop.json --metadata "$work/warmup-train.json"
cmp "$work/warmup-train.json" .cache/experiments/e1-baseline.json
cmp build/results/ground-crop.las .cache/experiments/e1-baseline.las
rm -- build/results/ground-crop.las "$work/warmup-train.json"
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -XX:AOTMode=create \
  -XX:AOTConfiguration="$work/ground.aotconfig" -XX:AOTCache="$work/ground.aot" -cp "$measure_cp"
"$JAVA_HOME/bin/java" -Xmx512m -XX:AOTMode=on -XX:AOTCache="$work/ground.aot" \
  -Xlog:aot=info:stderr -cp "$measure_cp" pointcloud.Main --version
JAVA_HOME="$graal" /usr/bin/time -lp "$graal/bin/native-image" -J-Xmx8g \
  -H:ConfigurationFileDirectories=experiments/native-image -cp "$product_cp" \
  -o "$work/pointcloud-native" pointcloud.Main
file "$work/pointcloud-native"
otool -L "$work/pointcloud-native"
python3 experiments/p9-runtimes.py "$java27"
```

Das Training lief 5.67 s, Peak-RSS 151'126'016 B, und erzeugte
eine Konfiguration von 15'630'336 B. Die Assembly lief 0.29 s,
Peak-RSS 115'851'264 B, für einen Cache von 16'384'000 B.
Alle Trainingsausgaben waren bytegleich mit der Basis. `-XX:AOTMode=on`
und `-Xlog:aot=info:stderr` bestätigten den geladenen Cache.

Das erste Native Binary war ausführbar, scheiterte bei der
Pipeline-JSON-Ausgabe aber am reflektiven Zugriff auf Java-Records.
Ein lokaler Lauf mit dem Native-Image-Agent zeigte die tatsächlich
aufgerufenen Accessor-Methoden. Die verwendete Konfiguration
registriert nur diese Methoden der drei Ergebnis-Records.
Der korrigierte Build dauerte 42.40 s (Builder 41.4 s),
maximales Build-RSS 1'680'949'248 B. Das Binary wog 19'080'352 B.
`file` meldete Mach-O arm64. `otool -L` zeigte Systembibliotheken
und keine JVM-Laufzeit. Der P9-Builder meldete `--no-fallback`
als deprecated und wirkungslos. Der obige Aufruf wurde in P11
ohne diese Option erneut gebaut und direkt ausgeführt.
[GraalVM Native Image](https://www.graalvm.org/jdk25/reference-manual/native-image/)

### Ergebnisse

Auf der Apple-M5-Maschine liefen je sieben Mini- und drei
Ganzkachel-Prozesse pro Variante mit 512 MiB Heapgrenze.
Reihenfolge in der ersten Serie gruppiert und in der zweiten
wechselnd, jeweils ohne konkurrierende Builds oder Tests.
Alle Läufe verwendeten dieselben Eingaben. Die zweite Serie
minimiert die Abhängigkeit der Vergleiche von der Startreihenfolge.
Beide Serien bleiben in den Rohdaten. Die Zahlen unten stammen
aus der wechselnden Serie.

| Laufzeit | Mini, erster Lauf | Mini, Median (Min–Max) | Ground-Crop, Median (Min–Max) | Mio. Punkte/s | Max. Ground-Crop-RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Temurin 25 | 76.4 ms | 70.0 (67.8–76.4) ms | 3.727 (3.610–3.920) s | 3.91 | 152.78 MiB |
| Temurin 27 | 153.2 ms | 81.0 (76.6–153.2) ms | 3.651 (3.580–4.371) s | 3.99 | 151.86 MiB |
| Temurin 25 mit AOT | 57.9 ms | 45.1 (43.7–57.9) ms | 3.613 (3.578–3.632) s | 4.03 | 156.36 MiB |
| Native Image | 28.3 ms | 7.7 (7.2–28.3) ms | 4.050 (4.019–4.189) s | 3.60 | 94.25 MiB |

Mini verarbeitet 5 Punkte und gibt Statistiken aus. Ground-Crop
verarbeitet jeweils 14'566'627 Punkte und schreibt 195'738 in eine
6'655'601-B-Ausgabe. Alle 80 Prozessläufe beider Serien lieferten
dieselben fachlichen Ergebnisse wie der jeweilige Standardlauf.
Jede Wiederholung startet einen neuen Prozess. Die kurze Aufgabe
zeigt Start- und Initialisierungskosten, der lange Durchsatz enthält
das Anlaufen des JIT innerhalb des ersten Dateilaufs. Alle Daten
waren für die Wiederholungen bereits im Dateisystemcache verfügbar.
[Einzelmessungen](measurements/e6-e7-runtime.csv)

Java 27 bleibt eine geprüfte zusätzliche Laufzeit. Sein Vorteil
beim ganzen Bestand war gegenüber der Streuung klein. **AOT-
und Native-Versuche bleiben isoliert.** AOT senkt die kurze
Startaufgabe im Median um etwa 36 %. Native Image startet den
kleinen Fall wesentlich schneller und braucht weniger RSS,
verarbeitet die vollständige Kachel aber langsamer als der
JVM-Standard. Der Cache bleibt an JVM, Architektur und
identischen Produktklassenpfad gebunden.

### Nachprüfung mit getrennter Start- und Warm-up-Messung

Die Messung vom 24. November 2026 verwendet den Stand `0a17961`
auf dem M5 vor der Übernahme auf `1c3a069`. Temurin 25.0.5+8,
Temurin 27.0.1.1+4 und Oracle GraalVM 25.0.4.1.1 laufen als arm64
mit `-Xmx512m`.
Der Batch hat 32'768 Punkte, ein Thread verarbeitet die Daten. Alle
Laufzeiten verwenden dieselbe Ground-Crop-Eingabe und schreiben je
195'738 Punkte. Das Skript wechselt die Startreihenfolge. Die Eingabedatei
war durch die Referenzläufe und das AOT-Training bereits im Betriebssystemcache.

| Laufzeit | Start `--version`, Median (Min–Max) | Mini, Median (Min–Max) | Ground-Crop aus frischem Prozess, Median (Min–Max) | Max. Ground-Crop-RSS |
| --- | ---: | ---: | ---: | ---: |
| Temurin 25 | 29.6 (28.4–33.4) ms | 78.2 (72.8–108.0) ms | 3.628 (3.612–3.688) s | 155.91 MiB |
| Temurin 27 | 34.5 (32.6–167.2) ms | 87.7 (83.0–126.7) ms | 3.725 (3.643–3.746) s | 157.91 MiB |
| Temurin 25 mit AOT | 28.8 (28.0–39.0) ms | 54.4 (45.3–65.7) ms | 3.726 (3.623–3.757) s | 152.67 MiB |
| Native Image | 6.6 (6.4–277.3) ms | 9.1 (7.9–19.7) ms | 4.099 (4.017–4.346) s | 94.33 MiB |

Sieben frische Prozesse pro kurzer Aufgabe und drei pro Ganzkachel.
Die ersten `--version`-Aufrufe dauerten 33.4, 167.2, 39.0 und
277.3 ms in der Tabellenreihenfolge. Damit sind die Ausreisser beim
ersten Temurin-27- und Native-Start in der Streuung enthalten.
Mit AOT sparte die Mini-Pipeline im Median rund 30 % gegenüber Java 25.
Der frische Ganzkachellauf mit Native Image benötigte 13 % mehr Zeit
bei deutlich geringerem Spitzen-RSS.
[Einzelmessungen der frischen Prozesse](measurements/e6-e7-audit-runtime.csv)

Für Warm-up und Dauerbetrieb lief Ground-Crop viermal in derselben JVM.
Jede der drei JDK-Konfigurationen startete drei separate Prozesse.
Die Messung des ersten Scans beginnt nach dem Prozessstart und umfasst
die erstmalige Pipelineinitialisierung und das Anlaufen des JIT.
Die Scans 2–4 benutzen dieselbe bereits aufgewärmte JVM. Jede
Zeitmessung umfasst Konfiguration, Lesen, Auswahl, Statistik und
Veröffentlichung der LAS-Datei. Prüfung gegen Referenz-JSON und
Referenz-LAS sowie Löschen der Ausgabe erfolgen danach.

| JVM | Erster Scan, Median (Min–Max) | Warme Scans, Median (Min–Max) | Warmer Durchsatz | Max. RSS pro Prozess |
| --- | ---: | ---: | ---: | ---: |
| Temurin 25 | 3.659 (3.602–3.809) s | 3.530 (3.465–4.235) s | 4.13 Mio. Punkte/s | 159.16 MiB |
| Temurin 27 | 3.812 (3.708–3.828) s | 3.535 (3.458–3.852) s | 4.12 Mio. Punkte/s | 157.55 MiB |
| Temurin 25 mit AOT | 3.632 (3.577–3.771) s | 3.565 (3.533–3.731) s | 4.09 Mio. Punkte/s | 161.67 MiB |

Alle 68 frischen Aufrufe und 36 Scans in neun wiederverwendeten JVMs
lieferten die geprüften Ergebnisse. Beim warmen Ganzkacheldurchsatz lagen
die Unterschiede zwischen den drei JVM-Medianen innerhalb der Streuung.
Die Messreihe bestätigt den Java-25-Standard und die Entscheidung,
AOT und Native Image als Experimente zu behalten.
[Einzelmessungen der JVM-Scans](measurements/e6-audit-warmup.csv)

### Oracle Native Image mit PGO und G1 auf dem M1

24. November 2026. Apple M1 arm64, macOS 15.8.1, Apple clang 17.0.0,
Produktcode `1c3a069`, PDAL 2.10.2/LazPerf, Oracle GraalVM 25.6.5.1
auf JDK 25.0.5.1 und Temurin 25.0.3+9 mit HotSpot-G1. Der Oracle-Download
für macOS arm64 hat SHA-256
`5c0182f80bff02da9266a9928606e65ea2ac53a8cdb195fb3680972378e4e192`.
[Oracle-Release](https://www.graalvm.org/release-notes/25.6/)

Beide nativen GC-Vorabtests bauten und starteten auf diesem Mac. Der
Build meldete den jeweils angeforderten Collector. Die Testprozesse
lösten beobachtbare GC-Pausen aus. Die Innovationslinie führte G1 für
Native Image auf macOS arm64 in 25.1 ein. Das GC-Handbuch der LTS-Linie
beschreibt den früheren Linux-Support. Epsilon hält Allokationen bis zum
Prozessende und ist für die speicherintensive Ganzkachel-Matrix ungeeignet.
[G1 auf macOS arm64](https://www.graalvm.org/release-notes/25.1/#native-image) ·
[Native-Image-Speichermanagement](https://docs.oracle.com/en/graalvm/jdk/25/docs/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)

Vier Oracle-Binaries verwenden den identischen Produkt-Klassenpfad, die
vorhandenen Reachability-Metadaten, `-O3` und `-march=native`. Serial und G1
werden jeweils mit und ohne gemessene Trainingsprofile gebaut. Ein instrumentiertes Binary pro
Collector liefert je ein Profil für jede Kombination aus Alt-LAZ bzw. COPC
und einem bzw. vier Workern. Der optimierte Build liest seine vier Profile
mit `--pgo` ein. Die Standard-Builds verwenden die vom Compiler inferierten
Profile (`ML-inferred`). Alle gemessenen Prozesse nutzen `-Xmx512m`.
[GraalVM-PGO](https://www.graalvm.org/jdk25/reference-manual/native-image/optimizations-and-performance/PGO/basic-usage/)

| Eingabe | Aufgabe | Punkte | Format | Chunks |
| --- | --- | ---: | ---: | ---: |
| Erste Punkte von Solothurn 2019 | Training | 2'000'000 | 3 | 40 |
| Erste Punkte von Autzen | Training | 2'000'000 | 7 | 99 |
| Solothurn 2014 | Unabhängiger Test | 14'347'366 | 1 | 287 |
| Autzen ab Punktindex 2'000'000 | Test auf disjunkter Punktmenge | 8'653'336 | 7 | 273 |
| Autzen, Originaldatei | Vergleich mit früheren Messungen | 10'653'336 | 7 | 278 |

PDAL trennt Autzen über physische Punktbereiche und schreibt die beiden
Teile als COPC mit festem Seed. Dadurch ändert sich die Chunk-Aufteilung.
Die Originaldatei enthält einige Trainingspunkte. Für die Empfehlung zum
Durchsatz zählt primär der disjunkte Autzen-Ausschnitt.

Die acht Trainingsläufe schrieben je 4'000 Solothurner beziehungsweise
20'000 Autzen-Records. Pro Datei lieferten beide Collector- und Worker-
Varianten bytegleiche LAS- und JSON-Ausgaben. Die optimierten Builds
meldeten `PGO: user-provided` und Stufe 3. [Profil-Hashes](measurements/native-pgo-profiles.csv)

| Binary | Buildzeit | Grösse |
| --- | ---: | ---: |
| Native Serial O3 | 81.83 s | 24.16 MB |
| Native Serial O3 mit PGO | 44.56 s | 14.05 MB |
| Native G1 O3 | 80.90 s | 28.22 MB |
| Native G1 O3 mit PGO | 41.24 s | 15.27 MB |

Die instrumentierten Builds für die Profilerfassung dauerten separat
80.76 s (Serial) und 79.62 s (G1).

```sh
./gradlew --offline clean check installDist
python3 experiments/prepare-native-pgo-data.py "$PDAL" "$work"
python3 experiments/build-native-pgo.py "$work" --graal-home "$graal_home"
python3 tools/check-parallel-data.py "$PDAL" "$work/runtimes.csv" \
  --runtimes "temurin=$temurin_cli" "native-serial=$serial_cli" \
  "native-serial-pgo=$serial_pgo_cli" "native-g1=$g1_cli" \
  "native-g1-pgo=$g1_pgo_cli" --modern-holdout "$work/holdout-autzen.copc.laz"
python3 experiments/native-pgo-gc.py "$work"
```

Jeder Launcher startet seinen Prozess per `exec` mit `-Xmx512m`.
Die Temurin-Variante setzt `-XX:+UseG1GC`. Die Messung umfasst sieben
rotierende Runden für die Fünf-Punkte-Statistik und je sieben Runden
pro Datensatz und Workerzahl. Der Dateicache ist warm. Der bestehende
Referenzvergleich prüft LAS und JSON ausserhalb des Zeitfensters.
`perf_counter_ns()` misst den Prozessaufruf, `/usr/bin/time -lp` liefert
CPU-Zeit und Spitzen-RSS. GC-Logs stammen aus separaten Läufen.

| Laufzeit | Mini | SO 2014, 1/4 Worker | Autzen, 1/4 Worker | Autzen-Holdout, 1/4 Worker | Höchster RSS Holdout, 4 Worker |
| --- | ---: | ---: | ---: | ---: | ---: |
| Temurin G1 | 106.59 ms | 5.143 / 1.589 s | 5.034 / 1.883 s | 4.281 / 1.668 s | 373.94 MiB |
| Native Serial O3 | 11.53 ms | 5.913 / 1.696 s | 5.788 / 1.824 s | 4.955 / 1.632 s | 169.81 MiB |
| Native Serial O3 mit PGO | 11.29 ms | 5.809 / 1.650 s | 5.674 / 1.784 s | 4.848 / 1.619 s | 163.17 MiB |
| Native G1 O3 | 12.30 ms | 6.006 / 1.693 s | 5.798 / 1.854 s | 4.945 / 1.634 s | 169.03 MiB |
| Native G1 O3 mit PGO | 11.97 ms | 5.826 / 1.708 s | 5.694 / 1.824 s | 4.846 / 1.603 s | 162.94 MiB |

Die Zeiten sind Wandzeit-Mediane. Die Entscheidungswerte verwenden den
Median der Verhältnisse innerhalb derselben Runde. Auf dem Holdout mit
vier Workern senkte PGO die Wandzeit um 0.71 % (Serial, fünf von sieben
Siegen) und 1.37 % (G1, vier Siege). Der Spitzen-RSS sank gepaart um
3.81 % und 3.90 %, jeweils in allen sieben Runden. Beide Varianten liegen
unter der vereinbarten Schwelle von 5 % Zeitgewinn oder 10 % RSS-Gewinn
bei sechs Siegen und höchstens 2 % Regression pro Realdatenreihe. Die
Binary-Grösse sank durch das Training um rund 42 % beziehungsweise 46 %.
[245 Einzelmessungen](measurements/native-pgo-runtimes.csv)

| GC-Diagnose auf Autzen-Holdout, 4 Worker | Beobachtete GC-IDs | Pausen | Summe | Längste Pause |
| --- | ---: | ---: | ---: | ---: |
| Temurin G1 | 6 | 6 | 7.20 ms | 1.61 ms |
| Native Serial O3 | 14 | 14 | 11.20 ms | 2.15 ms |
| Native Serial O3 mit PGO | 13 | 13 | 9.16 ms | 1.03 ms |
| Native G1 O3 | 46 | 59 | 15.55 ms | 0.82 ms |
| Native G1 O3 mit PGO | 44 | 56 | 14.26 ms | 0.58 ms |

Mediane aus drei separaten Diagnoseläufen. Bei G1 kann eine GC-ID mehrere
Pausen umfassen, und parallele Phasen erscheinen ebenfalls in den Logs.
Die Pausensumme zählt ausschliesslich abgeschlossene `Pause`-Zeilen.
G1 hatte kürzere maximale Pausen und höhere Summen. Bei identischem
Trainingsstatus lag der gepaarte Wandzeit-Median auf dem Holdout mit G1
0.51 % bzw. 0.27 % über Serial. Der RSS-Vorteil lag unter 3 %. Serial
bleibt die Empfehlung für den nativen CLI-Pfad auf dem M1.
[15 GC-Diagnoseläufe](measurements/native-pgo-gc.csv)

Für kurze Aufrufe ist Native Serial wegen der rund neunfach kürzeren
Mini-Wandzeit geeignet. Bei vier Workern auf dem Autzen-Holdout gewann
Native Serial mit PGO gegenüber Temurin gepaart 5.75 % Wandzeit in
sechs von sieben Läufen und 55.98 % Spitzen-RSS in allen sieben Läufen.
Seriell lagen sämtliche nativen Ganzkachel-Mediane über Temurin. Die
Realdaten-Matrix rechtfertigt Temurin weiterhin als Standard.

Die vollständige geordnete Rohrecord-Folge stimmte für insgesamt
33'654'038 Eingabepunkte zwischen Temurin und allen vier nativen Builds
mit einem oder vier Workern überein. Alle gemessenen LAS- und JSON-
Ausgaben waren bytegleich. PDAL bestätigte 28'695, 106'534 und 86'534
Ausgaberecords, CRS und Statistik mit den bestehenden Toleranzen. Alle
Native-Builds bestanden die Fixture-, Korruptions- und SIGTERM-Tests
sowie die separate PDAL-Produktsuite auf Solothurn 2019 und 2014.

## E8 · TornadoVM auf Apple M5

Hypothese nach E2: Ein GPU-Kernel kann die Auswahlmaske schnell
berechnen, bei einmaligem Filtern bestimmen aber Datenaufnahme
und Host-Kompaktierung den Gesamtnutzen. Untersucht wird genau
der Crop-/Classification-Kernel auf dem dekodierten E2-Sample.
Der Referenzvergleich umfasst jede Maskenentscheidung und alle
kompaktierten Recordbytes. Die zentimetergenauen Rohkoordinaten
erlauben für diesen Datensatz dieselben inklusiven Grenzen mit
Integer-Operationen. Gemessen werden Input-Aufbereitung, Planaufbau,
erster und wiederholter Device-Lauf, Transfers und Kompaktierung.
Ein CPU-Fallback ist ein Fehler.

### Durchführung

TornadoVM 8.1.0-jdk22plus von Maven Central, Metal-Backend auf
Temurin 25.0.5+8-LTS arm64. macOS 27.0.1 mit integrierter M5-GPU
und Metal 4. Die Apple-Systembibliothek ist im lokalen SDK vorhanden.
Der Runtime-Aufruf benötigt kein separat installiertes
`metal`-Kommando. TornadoVM meldete `METAL`, Gerät
`metal-0-0`, Typ GPU und physischen Gerätenamen Apple M5.
Die Swift-Probe bestätigt die residente Metal-GPU und Shared Memory.
Der Kontrollshader mit `float` kompiliert über Metal zur Laufzeit.
Die sonst gleiche Variante mit `double` wird vom Metal-Compiler
mit `'double' is not supported in Metal` abgelehnt.
[TornadoVM-Installation](https://tornadovm.readthedocs.io/en/latest/installation.html) ·
[TornadoVM 8.1.0](https://github.com/beehive-lab/TornadoVM/releases/tag/v8.1.0)

```sh
swift experiments/probe-metal.swift
./gradlew -Pexperiments -Ptornado installDist :experiments:installDist
work=.cache/experiments/p10
mkdir -p "$work"
if [ ! -f .cache/experiments/kernel.las ]; then
  JAVA_OPTS=-Xmx512m build/install/pointcloud/bin/pointcloud pipeline examples/decimate.json \
    > "$work/sample.json"
  mv build/results/decimate.las .cache/experiments/kernel.las
fi
for run in 1 2 3; do
  /usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m --enable-native-access=ALL-UNNAMED \
    -Dtornado.enabled.backends=metal -Dtornado.home="$work" \
    -cp 'experiments/build/install/experiments/lib/*' pointcloud.TornadoCrop \
    > "$work/gpu-$run.csv" 2> "$work/gpu-$run.log"
done
```

Das E2-Sample liegt unter `.cache/experiments/kernel.las`.
Der Plan kopiert X, Y und Klasse bei jeder Ausführung über
die Tornado-Array-Schnittstelle. Eine Integer-Maske wird
zurückgelesen und auf dem Host stabil in vollständige
LAS-Records kompaktifiziert. Der Code setzt strikte
Floating-Point-Semantik und verwendet im GPU-Kernel
ausschliesslich 32-Bit-Integer. Er prüft die Voraussetzungen
Scale 0.01 und Offset 0 sowie alle Masken gegen die
Double-Referenz. Alle 33 Geräteausführungen (3 Prozesse,
je 1 erster und 10 wiederholte Durchläufe) haben eine
positive Geräte-Kernelzeit. 128'909 Entscheidungen und
1'730 vollständige Ausgaberecords stimmen pro Durchlauf exakt.

| Kosten | Erster Lauf, Median (Min–Max) | Wiederholt, Median (Min–Max) |
| --- | ---: | ---: |
| Kompilierung und Installation laut Profiler | 76.593 (71.752–454.976) ms | 0 ms |
| Geräte-Kernel laut Profiler | 0.0285 (0.0273–0.0286) ms | 0.0268 (0.0265–0.0309) ms |
| Geräteschreiben laut Profiler | 0.280 (0.279–0.358) ms | 0.042 (0.038–0.255) ms |
| Rücklesen laut Profiler | 0.088 (0.062–0.101) ms | 0.015 (0.012–0.047) ms |
| Plan-Ausführung als Wandzeit | 103.251 (92.481–477.727) ms | 0.778 (0.647–1.388) ms |
| Kompaktierung auf dem Host | 1.934 (1.872–2.122) ms | 0.457 (0.188–1.464) ms |
| Ausführung plus Kompaktierung | 105.184 (94.602–479.599) ms | 1.263 (1.083–2.470) ms |

Geräteinitialisierung, Host-Datenaufbereitung und Planaufbau
kosteten im Median separat 73.51 ms, 11.57 ms und 38.05 ms.
Der erstmalige Batch von Geräteinitialisierung bis fertiger
Kompaktierung brauchte median 240.81 ms (214.37–602.65 ms).
Der Dateizugriff für das gemeinsame Sample liegt ausserhalb
dieses Messbereichs. Maximales Prozess-RSS 174'653'440 B.
Die vom Profiler gezählten Bytes umfassen seine Arrayheader.
Erster Transfer 2'062'608 B hinein, wiederholter Transfer
1'546'956 B, jeweils 515'652 B zurück. Shared Memory hebt
diese vom gewählten Graphen verursachten Kosten nicht auf.

Die direkte CPU-Variante in derselben Anwendung benötigte
0.176 ms im Median (0.153–0.380 ms, 21 Messungen nach je fünf
Warm-up-Läufen). Der warme GPU-Pfad erreicht für den ganzen
Batch inklusive Kopieren und Kompaktieren ca. 102 Mio. Punkte/s.
Die frühere JMH-Reihe auf demselben Sample ergab 0.128 ms
skalar und 0.110 ms per Vector API. Die Arraybefüllung und
die Rückführung des GPU-Ergebnisses zählen gegen den
Kernelvorteil. **Produktive Übernahme verworfen.** Der
TornadoVM-Versuch bleibt getrennt reproduzierbar.
[Einzelmessungen](measurements/e8-metal.csv)

## Polygon-Ground-Crop gegen PDAL

25. November 2026, Apple M1 arm64, macOS 15.8.1, 16 GiB RAM.
Java mit Temurin 25.0.3+9, `-Xmx512m` und JTS 1.20.0.
PDAL 2.10.2/LazPerf aus `libpdal-core h151f718_4`.
Die [Beispielpipeline](../examples/polygon-ground-crop.json) liest
14'566'627 LAZ-Punkte und schreibt 143'244 Bodenpunkte als LAS.
Der Lauf umfasst LAZ-Dekodierung, Polygon- und Klassenfilter,
Statistik sowie LAS- und JSON-Ausgabe.

Die vier Kombinationen liefen je achtmal in frischen Prozessen bei
warmem Dateicache. Die Reihenfolge rotiert und wird nach der vierten
Runde umgekehrt. Die Prozesswandzeit kommt von `perf_counter_ns()`.
`/usr/bin/time -lp` liefert CPU-Zeit und Spitzen-RSS. Java verwendet
`--laz-workers 1` bzw. `4`, PDAL `--stream --readers.las.threads=1`
bzw. `4`. Beide CLIs erhalten `--metadata`. Die Pipeline verweist
für die Messung auf temporäre Ausgabeziele. Ausserhalb der Messung
wurden LAS-Records und Statistiken in jedem Durchlauf verglichen.
Der Vorlauf bestätigte auch CRS und bytegleiche Java-LAS- und
-JSON-Ausgaben.

| Reader-Worker | Engine | Wandzeit, Median (Min–Max) | Prozess-CPU, Median | Median der Spitzen-RSS | Mio. Eingabepunkte/s |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | Java | 5.049 (5.025–5.427) s | 5.61 s | 303.99 MiB | 2.88 |
| 1 | PDAL | 4.944 (4.888–5.312) s | 7.53 s | 66.80 MiB | 2.95 |
| 4 | Java | 1.661 (1.613–2.093) s | 7.69 s | 327.22 MiB | 8.77 |
| 4 | PDAL | 3.026 (2.930–3.477) s | 8.44 s | 92.58 MiB | 4.81 |

Java benötigte mit einem Reader-Worker im gepaarten Median 2.22 %
mehr und mit vier Workern 44.81 % weniger Wandzeit. PDAL gewann
alle acht Ein-Worker-Vergleiche, Java alle acht Vier-Worker-Vergleiche.
Der gepaarte Spitzen-RSS von Java lag beim 4.52- bzw. 3.54-Fachen.
Die Angaben zur Workerzahl gelten für den Reader. Prozess-CPU summiert
die Zeit aller Threads.
[32 Einzelmessungen](measurements/polygon-ground-crop-pdal.csv)

Eine separate JFR-Aufnahme mit vier Java-Workern wies dem
`RayCrossingCounter` und dem `SegmentVisitor` des JTS-Polygonpfads
gewichtete Allokationsstichproben von 383 bzw. 166 MiB zu. Diese
Schätzung bezieht sich auf das Allokationsvolumen über den gesamten
Lauf. Der Spitzen-RSS stammt aus den unprofilierten Vergleichsläufen.
