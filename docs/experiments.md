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

## Bodenpunktraster mit Lückenfüllung gegen PDAL

25. November 2026, Apple M1 arm64, macOS 15.8.1, 16 GiB RAM, Netzbetrieb.
Java mit Temurin 25.0.3+9, `-Xmx512m` und GeoTools 35.1.
PDAL 2.10.2/LazPerf aus `libpdal-core h151f718_4` mit GDAL 3.13.3.
Die [Beispielpipeline](../examples/ground-grid-fill.json) liest alle
14'566'627 Punkte der Solothurn-2019-Kachel und schreibt das 200 × 200 Raster
mit 195'715 eingerasterten Bodenpunkten. Die Laufzeit umfasst Prozessstart,
LAZ-Dekodierung, Rechteckzuschnitt, Bodenfilter, Z-Statistik und die Ausgabe
von GeoTIFF und JSON-Metadaten. Beide GeoTIFFs enthalten vier unkomprimierte
Float64-Bänder. Java speichert sie pixelweise, PDAL bandweise.

Java verwendet `--laz-workers 1` oder `4`. PDAL verwendet `--stream` und
`--readers.las.threads=1` oder `4`. Pro Engine und Workerzahl werden
`window_size: 0` und `window_size: 1` ausgeführt. Nach je einem Warmlauf
folgen acht Runden mit zyklisch rotierender Reihenfolge, sodass jede
Variante an jeder Position einmal läuft. Jeder Lauf startet einen neuen
Prozess bei warmem Dateicache und schreibt in ein eigenes temporäres
Verzeichnis. Wandzeit kommt von `perf_counter_ns()`, Prozess-CPU und
Spitzen-RSS von `/usr/bin/time -lp`. GDAL-Prüfung, Vergleich der Zellwerte
und Metadaten sowie Dateigrössenerfassung liegen ausserhalb der Messung.

```sh
./gradlew installDist
python3 tools/check-ground-grid.py /pfad/zu/pdal \
  --benchmark-csv .cache/ground-grid-fill-pdal.csv
```

| `window_size` | Reader-Worker | Engine | Wandzeit, Median (Min–Max) in s | Prozess-CPU, Median in s | Median der Spitzen-RSS in MiB | Mio. Eingabepunkte/s |
| ---: | ---: | --- | ---: | ---: | ---: | ---: |
| 0 | 1 | Java | 5.319 (5.242–5.735) | 6.71 | 241.11 | 2.74 |
| 0 | 1 | PDAL | 4.921 (4.874–5.195) | 7.53 | 72.94 | 2.96 |
| 0 | 4 | Java | 2.061 (1.971–2.089) | 8.29 | 259.50 | 7.07 |
| 0 | 4 | PDAL | 2.923 (2.849–3.044) | 8.12 | 95.17 | 4.98 |
| 1 | 1 | Java | 5.377 (5.295–5.708) | 6.74 | 241.03 | 2.71 |
| 1 | 1 | PDAL | 4.934 (4.913–5.026) | 7.54 | 72.89 | 2.95 |
| 1 | 4 | Java | 2.045 (1.966–2.242) | 8.29 | 259.88 | 7.12 |
| 1 | 4 | PDAL | 2.942 (2.807–3.054) | 8.05 | 100.66 | 4.95 |

Java schrieb je Lauf 1'280'904 Byte GeoTIFF und 435 Byte JSON.
PDAL schrieb 1'283'156 Byte GeoTIFF und 12'235 Byte JSON.
Die gepaarten Prozentsätze sind Mediane der Verhältnisse innerhalb
derselben Messrunde.

| Reader-Worker | Java relativ zu PDAL, Fenster 0 | Java relativ zu PDAL, Fenster 1 | Zusatzzeit durch Füllung, Java (Min–Max) | Zusatzzeit durch Füllung, PDAL (Min–Max) |
| ---: | ---: | ---: | ---: | ---: |
| 1 | +7.50 % | +9.19 % | +0.60 % (−4.69 bis +5.15 %) | +0.00 % (−4.93 bis +2.54 %) |
| 4 | −29.75 % | −29.36 % | +0.54 % (−1.60 bis +11.38 %) | +0.24 % (−3.02 bis +4.89 %) |

PDAL benötigte in allen acht Ein-Worker-Runden weniger Wandzeit als Java.
Java benötigte in allen acht Vier-Worker-Runden weniger Wandzeit als PDAL.
Die zusätzlichen Zeiten für die Lückenfüllung liegen in der Streuung
der vollständigen Pipeline. Jede Variante hatte Runden, in denen die
Füllung schneller war, und Runden mit zusätzlicher Wandzeit.
Mit vier Workern und Füllung lag die mediane Spitzen-RSS von Java bei
260 MiB und die von PDAL bei 101 MiB.
[64 Einzelmessungen](measurements/ground-grid-fill-pdal.csv)

## Raster-Release gegen PDAL

26. November 2026, Apple M1 arm64, macOS 15.8.1, 16 GiB RAM, Netzbetrieb.
Temurin 25.0.3+9 mit `-Xmx512m`, GeoTools 35.1 und ImageIO-Ext 2.1.1.
PDAL 2.10.2/LazPerf aus `libpdal-core h151f718_4`, GDAL 3.13.3.
Alle drei Profile lesen dieselbe Solothurn-2019-Kachel, selektieren
195'738 Bodenpunkte im Rechteck und rasterisieren 195'715 davon
in ein 200 × 200-Gitter. Die Messung umfasst Prozessstart, Dekodierung,
Zuschnitt, Selektion, Z-Statistik, `mean,count,stdev` und die Ausgaben
GeoTIFF und JSON. Alle Raster verwenden Float64 und NoData −7777.

Die Profile sind Fenster 1 unkomprimiert, Fenster 2 unkomprimiert und
Fenster 2 mit Deflate. Java verwendet `--laz-workers 1` oder `4`.
PDAL verwendet `--stream --readers.las.threads=1` oder `4`. Nach einem
verifizierten Warmlauf je Kombination folgen acht Runden in frischen
Prozessen bei warmem Dateicache. Innerhalb jeder Runde rotieren Profile
und Engine-/Worker-Reihenfolge. Die Rasterprüfung mit GDAL und der Vergleich
der Zellwerte und Metadaten liegen ausserhalb der Zeitmessung. Die
Wandzeit verwendet `perf_counter_ns()`, CPU und Spitzen-RSS kommen von
`/usr/bin/time -lp`.

Java schreibt pixelweise und PDAL bandweise. Beim komprimierten Profil
verwendet Java die Deflate-Standardeinstellung des TIFF-Encoders, PDAL
`gdalopts: "COMPRESS=DEFLATE"` mit dem GDAL-Standard. Die Dateigrössen
beziehen sich auf diese vollständigen CLI-Ausgaben.

```sh
./gradlew installDist
python3 tools/check-ground-grid.py /pfad/zu/pdal --release \
  --benchmark-csv .cache/raster-release-pdal.csv
```

| Fenster und Kompression | Reader-Worker | Engine | Wandzeit, Median (Min–Max) in s | Prozess-CPU, Median in s | Median der Spitzen-RSS in MiB | TIFF-Byte |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| 1, none | 1 | Java | 5.382 (5.237–5.764) | 6.78 | 242.55 | 960'832 |
| 1, none | 1 | PDAL | 4.965 (4.880–5.489) | 7.55 | 75.15 | 962'592 |
| 1, none | 4 | Java | 2.103 (2.002–2.431) | 8.38 | 258.89 | 960'832 |
| 1, none | 4 | PDAL | 2.977 (2.922–3.382) | 8.29 | 99.26 | 962'592 |
| 2, none | 1 | Java | 5.339 (5.259–5.957) | 6.75 | 243.43 | 960'832 |
| 2, none | 1 | PDAL | 4.916 (4.871–5.111) | 7.53 | 72.73 | 962'592 |
| 2, none | 4 | Java | 2.042 (1.992–2.428) | 8.22 | 259.14 | 960'832 |
| 2, none | 4 | PDAL | 2.955 (2.931–3.298) | 8.23 | 107.09 | 962'592 |
| 2, deflate | 1 | Java | 5.510 (5.386–5.945) | 6.89 | 244.73 | 491'402 |
| 2, deflate | 1 | PDAL | 4.955 (4.887–5.895) | 7.56 | 74.41 | 477'508 |
| 2, deflate | 4 | Java | 2.241 (2.161–2.665) | 8.67 | 260.51 | 491'402 |
| 2, deflate | 4 | PDAL | 2.996 (2.937–3.434) | 8.29 | 103.45 | 477'508 |

Die JSON-Dateien haben 435 Byte für Java und 12'235 Byte für PDAL.
Die folgenden Prozentsätze sind Mediane der paarweisen Zeitverhältnisse
innerhalb derselben Runde.

| Reader-Worker | Java relativ zu PDAL, Fenster 1 | Fenster 2 | Fenster 2 mit Deflate |
| ---: | ---: | ---: | ---: |
| 1 | +6.82 % | +8.76 % | +9.99 % |
| 4 | −29.37 % | −30.75 % | −24.58 % |

Bei vier Workern brauchte Java in jeder Runde aller drei Profile weniger
Wandzeit. Der Wechsel von Fenster 1 auf 2 lag bei beiden Engines im Bereich
der Streuung. Deflate reduzierte die TIFF-Grösse um 48.86 % in Java und
50.39 % in PDAL. Bei Fenster 2 und vier Workern stieg die gepaarte Wandzeit
durch Deflate um 9.93 % in Java und 0.96 % in PDAL. Die Spannweite betrug
für Java +3.18 bis +29.02 % und für PDAL −0.11 bis +7.92 %.
[96 verifizierte Einzelmessungen](measurements/raster-release-pdal.csv)

### Stressprobe mit einer Million Zellen

`tools/check-ground-grid.py /pfad/zu/pdal --stress` erzeugt ein gemeinsames
unkomprimiertes LAS mit 500'000 synthetischen Höhenpunkten auf einem
1'000 × 1'000-Schachbrett. Eine Hälfte der Zellen hat genau einen Messpunkt
und eine Hälfte ist leer. Pro Kombination wurde ein vollständiger Lauf
mit einem Reader und den Bändern `mean,count,stdev` gemessen.

| Fenster | Engine | Wandzeit in s | Prozess-CPU in s | Spitzen-RSS in MiB | TIFF-Byte |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | Java | 0.705 | 1.56 | 256.55 | 24'001'632 |
| 1 | PDAL | 0.209 | 0.17 | 68.84 | 24'037'152 |
| 20 | Java | 3.773 | 4.66 | 251.34 | 24'001'632 |
| 20 | PDAL | 1.741 | 1.71 | 71.45 | 24'037'152 |

Alle Rasterwerte wurden geprüft. Beide Fenster füllten die 500'000 Lücken,
die Punktzahlen blieben exakt und die grösste Höhenabweichung bei Fenster 20
betrug 6.03 × 10⁻¹² m. Die Messung zeigt die Mehrarbeit eines breiten
Suchfensters auf einem Gitter mit vielen Lücken.

## TIN-Terrainmodell gegen PDAL

26. November 2026, MacBook Air M1 arm64, macOS 15.8.1, 16 GiB RAM,
Netzbetrieb. Temurin 25.0.3+9 mit `-Xmx512m`, Java-Batches mit
32'768 Records. PDAL 2.10.2/LazPerf aus conda-forge
(`libpdal-core h151f718_4`) und GDAL 3.13.3.

Die beiden [TIN-Beispielpipelines](../examples/dtm-solothurn-urban.json)
rechnen je ein Raster mit 200 × 200 Zellen bei 1 m im LV95-Gitter.
Der Crop reicht 50 m über das Ziel hinaus. Danach bleiben
`Classification == 2 && Withheld == 0`. Die Stadtkachel liefert
728'768 von 11'963'266 Punkten, die Waldkachel 428'321 von 14'566'627.
Nach dem Entfernen doppelter Roh-XY verwendet Java 728'751 respektive
428'313 Stützpunkte. Die vorherige Bibliotheksprobe mit den städtischen
Bodenpunkten benötigte für Aufbau und Iteration mit Tinfour rund 1.9 s
bei 512 MiB Heap. JTS erreichte unter derselben Grenze das Heap-Limit.

### Fachlicher Vergleich

Geprüft wurden das nordorientierte Gitter, EPSG:2056, das einzelne
Float64-Band, NoData, Punktzahlen und Z-Statistik. Die Java-Läufe mit
einem und vier Reader-Workern ergaben bitgleiche Höhen und gleiche
Metadaten. Die NoData-Masken von Java und PDAL waren in allen vier
Fällen identisch.

| Gebiet | Maximale Kante | Gültige Zellen | Höhenfehler über 1 µm | MAE (m) | RMSE (m) | Maximum (m) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Stadt | offen | 40'000 | 2 | 1.65 × 10⁻⁸ | 2.90 × 10⁻⁶ | 0.000574 |
| Stadt | 20 m | 32'773 | 2 | 2.01 × 10⁻⁸ | 3.21 × 10⁻⁶ | 0.000574 |
| Wald | offen | 40'000 | 4 | 1.01 × 10⁻⁶ | 1.12 × 10⁻⁴ | 0.016385 |
| Wald | 5 m | 39'751 | 4 | 1.01 × 10⁻⁶ | 1.13 × 10⁻⁴ | 0.016385 |

Bei allen sechs abweichenden Zellmitten haben die beiden Stützdreiecke
eine andere Diagonale über denselben vier XY-Stützpunkten. Jeweils
vier Punkte liegen exakt auf einem Kreis in der LAS-Zentimeterquantisierung.
Die Höhe aus den tatsächlichen Java- und PDAL-Dreiecken reproduzierte die
jeweilige Ausgabe innerhalb 1 µm. Das 95. Perzentil des absoluten
Zellfehlers lag in allen Fällen unter 5.4 × 10⁻¹¹ m.
[Koordinaten und Höhen der Stützdreiecke](measurements/tin-support-faces.json)

Die Schummerungen verwenden 315° Azimut und 45° Sonnenhöhe. Im bebauten
Ausschnitt überbrückt das offene TIN den grossen Gebäudegrundriss sichtbar
mit Dreiecksflächen. Das 20-m-Limit legt dort NoData frei. Die Java- und
PDAL-Stadtschummerungen sind für beide Varianten bytegleich.
[Gebäudedetail ohne Limit](measurements/tin-urban-buildings.png) ·
[Gebäudedetail mit 20 m](measurements/tin-urban-buildings-cap20.png) ·
[Waldschummerung](measurements/tin-forest-hillshade.png)

### Laufzeit

```sh
./gradlew installDist downloadTestData
./gradlew downloadTestData -Ptile=2019-urban
python3 tools/check-dtm.py /pfad/zu/pdal --benchmark-csv .cache/dtm-pdal.csv
```

Der vollständige Vergleich misst LAZ-Eingabe, Crop, Bodenpunktauswahl,
Statistik, TIN und Ausgabe eines unkomprimierten GeoTIFFs mit
JSON-Metadaten. PDAL läuft mit `--nostream` und
`filters.delaunay`, `filters.faceraster`, `writers.raster`. Je Gebiet
laufen acht rotierende Runden mit je einem und vier Reader-Workern.
Dateicache und Varianten sind vor der Messung aufgewärmt. Jeder
Messlauf startet einen neuen Prozess. Zellwerte und Mengenstatistik
werden ausserhalb der Zeitmessung gegen die jeweilige Abnahme geprüft.

Der zweite Vergleich liest dieselbe vorbereitete, unkomprimierte
Boden-LAS in beiden Engines. Er misst LAS-Lesen, Statistik, TIN,
Rasterisierung und Ausgabe mit einem Reader in fünf frischen Prozessen je
Engine und Gebiet. Die vollständige LAZ-Dekodierung und Filterung entfallen
hier. Das Peak-RSS von PDAL im vollständigen Lauf enthält die materialisierten
Punktansichten des nicht streamenden Pfads.

| Gebiet | Eingabe | Engine | Reader | Median (s) | Min–Max (s) | Median CPU (s) | Peak-RSS Median / Max (MiB) | Mio. Punkte/s |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Stadt | LAZ | Java | 1 | 5.512 | 5.429–5.613 | 7.96 | 441 / 444 | 2.17 |
| Stadt | LAZ | PDAL | 1 | 4.482 | 4.464–4.538 | 5.93 | 850 / 938 | 2.67 |
| Stadt | LAZ | Java | 4 | 2.937 | 2.848–3.281 | 9.79 | 446 / 466 | 4.07 |
| Stadt | LAZ | PDAL | 4 | 2.646 | 2.577–2.740 | 7.07 | 880 / 968 | 4.52 |
| Stadt | Boden-LAS | Java | 1 | 1.936 | 1.890–1.937 | 4.02 | 383 / 385 | 0.38 |
| Stadt | Boden-LAS | PDAL | 1 | 0.560 | 0.557–0.571 | 0.55 | 240 / 241 | 1.30 |
| Wald | LAZ | Java | 1 | 6.179 | 6.135–6.493 | 8.31 | 425 / 429 | 2.36 |
| Wald | LAZ | PDAL | 1 | 5.644 | 5.571–5.767 | 7.37 | 728 / 939 | 2.58 |
| Wald | LAZ | Java | 4 | 2.916 | 2.818–3.641 | 10.68 | 442 / 447 | 4.99 |
| Wald | LAZ | PDAL | 4 | 3.040 | 2.937–3.400 | 9.19 | 851 / 990 | 4.79 |
| Wald | Boden-LAS | Java | 1 | 1.770 | 1.496–2.212 | 3.74 | 337 / 343 | 0.24 |
| Wald | Boden-LAS | PDAL | 1 | 0.380 | 0.359–0.715 | 0.36 | 159 / 163 | 1.13 |

In der vollständigen Pipeline mit vier Readern benötigt Java in der
Stadt 11 % mehr Wandzeit. Im Wald liegt der Java-Median 4 % tiefer.
Dort überlappen die gemessenen Laufzeitspannen. Auf der gemeinsamen
Boden-LAS beträgt die Wandzeit von Java das 3.5-Fache in der Stadt und
das 4.7-Fache im Wald. Der Vergleich lokalisiert den grösseren Abstand
im Teil der Pipeline ab der gefilterten Punktmenge. Bei der
vollständigen LAZ-Eingabe hat Java in beiden Gebieten das tiefere
Spitzen-RSS.

Die GeoTIFFs umfassen 320'668 Bytes bei Java und 320'732 Bytes bei PDAL.
Die Grösse der JSON-Metadaten ist pro Variante in den Rohmessungen
festgehalten. [84 verifizierte Einzelmessungen](measurements/tin-dtm-pdal.csv)

## TIN-Optimierungsversuche

Basis `8021112`, Solothurn Stadt und Wald, Apple M1, Temurin 25.0.3+9
mit `-Xmx512m`, PDAL 2.10.2/LazPerf und GDAL 3.13.3. Die
Versuchs-Patches liegen unter `experiments/patches/tin-*.patch`. Sie
werden in separaten Worktrees auf die Basis angewendet. Die im
vorherigen Abschnitt beschriebene CLI dient als Produktreferenz.

### Messaufbau

`experiments/check-tin.py` vergleicht zwei installierte CLIs mit
demselben Raster und denselben Eingabedateien. Beim ersten Aufruf
erzeugt der Referenz-Build die beiden gemeinsamen Boden-LAS-Dateien.
Beide Formate werden in getrennten Messreihen verwendet. Die
Boden-LAS enthält die vorher ausgewählten Punkte. Der vollständige
LAZ-Lauf enthält auch Dekodierung, Crop und Bodenauswahl.

Acht gepaarte Runden, je ein frischer Prozess, rotierende Reihenfolge,
warmer Dateicache. Zeitmessung und Ausgabekontrolle erfolgen getrennt.
Die Kontrollläufe verwenden offenes TIN und Kantenlimits von 20 m
in der Stadt beziehungsweise 5 m im Wald. Verglichen werden
Georeferenzierung, Float64-Zellbits, NoData und Metadaten.
Für alternative Triangulationen werden Abweichungen über 1 µm und
veränderte Masken anhand der tatsächlichen Stützdreiecke nachgerechnet.

Die zum Vergleich gepaarte Verbesserung ist der Median aus
`1 - Kandidatenzeit / Referenzzeit` je Runde. Als belastbarer
Einzelgewinn gelten mindestens 5 % bei mindestens sechs Siegen
auf einem Gebiet und höchstens 2 % Verlust auf dem anderen. Ein
Anstieg des Spitzen-RSS um mehr als 10 % wird als Speicherkompromiss
ausgewiesen.

```sh
export JAVA_HOME=/pfad/zu/temurin-25
pdal=/pfad/zu/pdal
work="$PWD/.cache/experiments/tin-performance"
patches="$PWD/experiments/patches"
mkdir -p "$work"
for name in baseline mixed; do
  git worktree add --detach "$work/$name" 8021112
  if [ "$name" = mixed ]; then git -C "$work/$name" apply "$patches/tin-mixed.patch"; fi
  (cd "$work/$name" && ./gradlew test installDist)
done
python3 experiments/check-tin.py "$pdal" --work "$work" \
  --baseline-cli "$work/baseline/build/install/pointcloud/bin/pointcloud" \
  --cli "$work/mixed/build/install/pointcloud/bin/pointcloud" \
  --name mixed --csv "$work/mixed.csv"
```

### Einzelvarianten auf vorbereiteter Boden-LAS

| Variante / Vergleich | Gebiet | Wandzeit Median (s) | Gepaarter Gewinn | Siege | CPU Median (s) | Peak-RSS Max (MiB) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Gemischtes HashSet / Basis | Stadt | 1.912 / 1.789 | 7.71% | 8/8 | 3.96 / 3.73 | 387 / 365 |
| Gemischtes HashSet / Basis | Wald | 1.374 / 1.311 | 4.58% | 8/8 | 3.07 / 2.94 | 342 / 332 |
| Primitive Tabelle / gemischtes Set | Stadt | 1.796 / 1.699 | 5.21% | 8/8 | 3.74 / 3.33 | 366 / 371 |
| Primitive Tabelle / gemischtes Set | Wald | 1.339 / 1.300 | 2.99% | 6/8 | 2.99 / 2.89 | 334 / 313 |
| Standard-TIN / SemiVirtual | Stadt | 1.924 / 1.805 | 6.70% | 7/8 | 4.00 / 3.80 | 385 / 441 |
| Standard-TIN / SemiVirtual | Wald | 1.382 / 1.302 | 4.18% | 7/8 | 3.12 / 2.96 | 341 / 354 |
| Hilbert / Standard mit Dateireihenfolge | Stadt | 1.847 / 1.811 | 3.45% | 5/8 | 3.87 / 3.92 | 442 / 445 |
| Hilbert / Standard mit Dateireihenfolge | Wald | 1.291 / 1.247 | 3.49% | 8/8 | 2.98 / 2.93 | 351 / 349 |
| Gemeinsamer Prüfdurchlauf / Basis | Stadt | 1.943 / 1.867 | 4.73% | 7/8 | 4.08 / 3.90 | 387 / 385 |
| Gemeinsamer Prüfdurchlauf / Basis | Wald | 1.414 / 1.318 | 7.25% | 8/8 | 3.13 / 3.06 | 341 / 340 |
| Direkte LV95-GeoKeys / Basis | Stadt | 1.950 / 1.583 | 18.74% | 8/8 | 4.04 / 3.03 | 386 / 357 |
| Direkte LV95-GeoKeys / Basis | Wald | 1.408 / 1.016 | 27.38% | 8/8 | 3.16 / 2.01 | 340 / 280 |

Hashes, gemeinsame Stützpunktprüfung und direkte GeoKeys bestanden
alle vier Profile mit bitgleichen Float64-Zellen und identischen
Metadaten. Standard-TIN und Hilbert lieferten dieselben NoData-Masken.
Die Höhenabweichungen gegenüber ihren Java-Referenzen blieben bei
höchstens 1.14 × 10⁻¹³ m. Hilbert blieb beim Gesamtgewinn inklusive
Sortieren unter der 5-%-Schwelle.

Bei einer Million synthetischen Stützpunkten lieferten beide
TIN-Varianten alle 40'000 analytisch geprüften Rasterhöhen unter
`-Xmx512m`. Die Einzelprobe ergab 4.716 s und 448 MiB Spitzen-RSS
für SemiVirtual sowie 3.237 s und 516 MiB für Standard. Der Stadtlauf
der Standardvariante erhöht das maximale RSS um rund 15 %.

Der kombinierte Kandidat verwendet die primitive XY-Tabelle,
Standard-TIN, die Stützpunktprüfung beim Rasterdurchlauf und
direkte LV95-GeoKeys.

Hilbert reduzierte die mittlere Zahl der Lawson-Suchschritte in
der Stadt von 17.10 auf 3.05 und im Wald von 16.52 auf 3.22. Die
gemessenen Zeitgewinne von rund 3.5 % enthalten den Sortieraufwand.

### CRS-Initialisierung und AOT

| Eingabe | Frischer Standardprozess (s) | AOT (s) | Direkte LV95-GeoKeys (s) |
| --- | ---: | ---: | ---: |
| DTM mit fünf Punkten | 0.609 | 0.340 | 0.228 |
| Boden-LAS Stadt | 1.968 | 1.625 | siehe Einzelvariante |
| Boden-LAS Wald | 1.551 | 1.123 | siehe Einzelvariante |

Acht frische Prozesse je Variante. Die ersten Läufe in drei
JVMs mit je vier kleinen DTMs dauerten 0.519–0.530 s. Die
anschliessenden neun Läufe im selben Prozess dauerten
0.0030–0.0039 s. Das enthält neben dem bereits initialisierten
CRS auch die aufgewärmten JVM-Klassen.

Der JFR-Einzellauf für den kleinen DTM misst im GeoTIFF-Konstruktor
460 ms und beim anschliessenden Schreiben 21 ms. Mit direkten
GeoKeys sind es 0.28 ms und 108 ms. Ein Teil der übrigen
Initialisierung fällt damit erst bei der Ausgabe an. Der AOT-Cache
wurde auf allen drei DTM-Eingaben trainiert. Aufnahme und Erstellung
dauerten 3.668 s und 0.704 s. Die Datei umfasst 36'356'096 Bytes.
`AOTMode=on` und das JVM-Log bestätigten das Öffnen und Mappen
des Archivs. GeoTIFF und JSON waren in den Laufzeitversuchen
bytegleich.

```sh
python3 experiments/tin-runtime.py --work "$work" --ground-dir "$work/input" \
  --baseline-cli "$work/baseline/build/install/pointcloud/bin/pointcloud" \
  --geokey-cli "$work/geokey/build/install/pointcloud/bin/pointcloud" \
  --csv "$work/runtime.csv"
```

[AOT-Einzelmessungen](measurements/tin-optimization-runtime.csv)

### Kombinierter Tinfour-Kandidat

Auf Boden-LAS gewinnt die Kombination in beiden Gebieten alle
acht Zeitpaare. Der identische kombinierte Java-Build war in
einem getrennten JFR-Lauf mit 0.794 s in der Stadt und 0.318 s
im Wald am längsten mit dem TIN-Einfügen beschäftigt. Der
GeoTIFF-Konstruktor liegt dort unter 1 ms. Der weiterhin
über 20 % liegende Abstand zu PDAL erfüllt die Bedingung
für den zusätzlichen Delaunator-Prototyp.

| Gebiet | Engine | Median / Min–Max (s) | Median CPU (s) | Peak-RSS Max (MiB) |
| --- | --- | ---: | ---: | ---: |
| Stadt | Java-Basis | 1.888 / 1.847–1.921 | 3.95 | 386 |
| Stadt | kombiniert | 1.355 / 1.341–1.377 | 2.35 | 351 |
| Stadt | PDAL 2.10.2 | 0.557 / 0.554–0.568 | 0.54 | 247 |
| Wald | Java-Basis | 1.403 / 1.362–1.595 | 3.10 | 341 |
| Wald | kombiniert | 0.812 / 0.774–0.834 | 1.58 | 270 |
| Wald | PDAL 2.10.2 | 0.345 / 0.343–0.578 | 0.33 | 163 |

Vollständige LAZ-Pipelines mit Crop, Statistik und GeoTIFF. Die
Kombination lieferte bei einem und vier Readern bitgleiche
Ausgaben und behielt gegenüber der bisherigen Java-CLI dieselben
NoData-Masken. Die Höhen unterschieden sich höchstens um
1.14 × 10⁻¹³ m. Die aus der Erstabnahme bekannten sechs
PDAL-Abweichungen wurden anhand der Dreiecke erneut bestätigt.

| Gebiet | Reader | Engine | Median / Min–Max (s) | Median CPU (s) | Peak-RSS Max (MiB) |
| --- | ---: | --- | ---: | ---: | ---: |
| Stadt | 1 | Java-Basis | 5.461 / 5.413–5.787 | 7.83 | 442 |
| Stadt | 1 | kombiniert | 4.920 / 4.898–5.596 | 6.21 | 406 |
| Stadt | 1 | PDAL 2.10.2 | 4.453 / 4.422–4.484 | 5.88 | 943 |
| Stadt | 4 | Java-Basis | 2.959 / 2.868–3.080 | 9.85 | 460 |
| Stadt | 4 | kombiniert | 2.477 / 2.432–2.586 | 8.04 | 419 |
| Stadt | 4 | PDAL 2.10.2 | 2.618 / 2.555–2.657 | 7.09 | 980 |
| Wald | 1 | Java-Basis | 6.166 / 6.116–6.343 | 8.21 | 428 |
| Wald | 1 | kombiniert | 5.570 / 5.523–5.643 | 6.69 | 362 |
| Wald | 1 | PDAL 2.10.2 | 5.590 / 5.545–5.738 | 7.33 | 1056 |
| Wald | 4 | Java-Basis | 2.967 / 2.893–3.078 | 10.93 | 445 |
| Wald | 4 | kombiniert | 2.388 / 2.310–2.468 | 9.11 | 375 |
| Wald | 4 | PDAL 2.10.2 | 3.035 / 2.948–3.340 | 9.38 | 1093 |

### Delaunator-Prototyp

Der zusätzliche Patch baut auf `tin-combined.patch` auf.
Er verwendet radiale Ordnung, flache Integer-Arrays für das
Dreiecksnetz und exakte Orientierungs- und InCircle-Prädikate
für gleich skalierte Rohkoordinaten mit höchstens 35'000
Einheiten Spannweite je Achse. Die radiale Reihenfolge wird
ebenfalls exakt verglichen. Andere Eingaben verwenden den
Tinfour-Pfad. Der Mapbox-Lizenzhinweis steht im Prototyp.
[Algorithmusgrundlage](https://github.com/mapbox/delaunator/tree/v5.0.1)

| Gebiet | Engine | Boden-LAS Median / Min–Max (s) | Median CPU (s) | Peak-RSS Max (MiB) |
| --- | --- | ---: | ---: | ---: |
| Stadt | kombiniert | 1.371 / 1.351–1.394 | 2.38 | 353 |
| Stadt | Delaunator | 0.862 / 0.847–0.880 | 1.50 | 247 |
| Stadt | PDAL 2.10.2 | 0.559 / 0.557–0.572 | 0.54 | 245 |
| Wald | kombiniert | 0.788 / 0.776–0.864 | 1.54 | 270 |
| Wald | Delaunator | 0.644 / 0.637–0.672 | 1.23 | 185 |
| Wald | PDAL 2.10.2 | 0.345 / 0.342–0.522 | 0.33 | 164 |

Der gepaarte Gewinn gegenüber der Tinfour-Kombination beträgt
37.16 % in der Stadt und 18.01 % im Wald, je acht Siege.
PDAL bleibt auf vorbereiteter Boden-LAS in beiden Gebieten
schneller.

Bei allen Kantenlimits waren die NoData-Masken gleich. Gegen
die Tinfour-Kombination wichen fünf Stadt- und zwei Waldzellen
um mehr als 1 µm ab, bei maximal 3.00 mm und 11.54 mm.
Gegen PDAL waren es vier Stadt- und zwei Waldzellen,
bei maximal 3.00 mm und 16.38 mm. Jede Differenz wurde aus
den tatsächlich verwendeten Dreiecken reproduziert.
Die jeweiligen Roh-XY liegen exakt auf demselben Kreis.
[Stützdreiecke](measurements/tin-optimization-flat-faces.json)

```sh
git worktree add --detach "$work/delaunator" 8021112
git -C "$work/delaunator" apply "$patches/tin-combined.patch"
git -C "$work/delaunator" apply "$patches/tin-delaunator.patch"
(cd "$work/delaunator" && ./gradlew clean check installDist)
python3 experiments/check-tin.py "$pdal" --work "$work" \
  --baseline-cli "$work/baseline/build/install/pointcloud/bin/pointcloud" \
  --cli "$work/delaunator/build/install/pointcloud/bin/pointcloud" \
  --candidate-faces "$work/delaunator/tools/TerrainFaces.java" \
  --allow-mesh-differences --include-pdal --mode laz \
  --name flat-laz --csv "$work/flat-laz.csv"
```

Die vollständigen LAZ-Läufe enthalten ebenfalls Bodenauswahl,
Statistik und GeoTIFF. Der Delaunator-Build liefert mit einem und
vier Readern jeweils bitgleiche Zellwerte und Metadaten je Gebiet
und Kantenlimit.

| Gebiet | Reader | Engine | Median / Min–Max (s) | Median CPU (s) | Peak-RSS Max (MiB) |
| --- | ---: | --- | ---: | ---: | ---: |
| Stadt | 1 | Java-Basis | 5.488 / 5.454–5.549 | 7.82 | 444 |
| Stadt | 1 | Delaunator | 4.468 / 4.420–4.662 | 5.38 | 305 |
| Stadt | 1 | PDAL 2.10.2 | 4.486 / 4.459–4.567 | 5.92 | 948 |
| Stadt | 4 | Java-Basis | 2.928 / 2.880–3.115 | 9.89 | 455 |
| Stadt | 4 | Delaunator | 1.967 / 1.932–2.013 | 7.05 | 318 |
| Stadt | 4 | PDAL 2.10.2 | 2.651 / 2.582–2.704 | 7.11 | 981 |
| Wald | 1 | Java-Basis | 6.205 / 6.128–6.555 | 8.22 | 429 |
| Wald | 1 | Delaunator | 5.467 / 5.417–5.798 | 6.40 | 275 |
| Wald | 1 | PDAL 2.10.2 | 5.650 / 5.574–5.808 | 7.37 | 1049 |
| Wald | 4 | Java-Basis | 2.957 / 2.821–3.091 | 10.86 | 451 |
| Wald | 4 | Delaunator | 2.202 / 2.126–2.694 | 8.49 | 297 |
| Wald | 4 | PDAL 2.10.2 | 2.998 / 2.955–3.044 | 9.18 | 1079 |

[480 A/B-Einzelmessungen](measurements/tin-optimization-ab.csv)

## Delaunator-Kandidat für vollständige DTM-Raster

Der separate Branch `codex/tin-delaunator-candidate` vergleicht
Delaunator mit der vollständigen Tinfour-Produktpipeline aus `aad5f70`.
Der Ganzzahlpfad verwendet den radialen Aufbau aus Delaunator 5.0.1.
Bei gleichen positiven X- und Y-Skalen darf der Abstand zwischen
kleinster und grösster deduplizierter Rohkoordinate je Achse
35'000 Einheiten betragen. Innerhalb dieses Bereichs passt der
InCircle-Determinant in ein Java-`long`. Die radiale Ordnung verwendet
eine exakte 128-Bit-Summe. Beide Kerne teilen sich die Rasterisierung
und die Nächste-Nachbar-Suche für Restzellen.

Die Messung verwendet macOS 15.8.1 auf dem Apple M1 mit 16 GiB RAM,
Temurin 25.0.3+9, `-Xmx512m`, PDAL 2.10.2 und GDAL 3.13.3.
Die Prozesse starten frisch mit warmem Dateicache. Gemessen
wird das offene Netz mit unkomprimiertem GeoTIFF und
JSON-Metadaten. Java läuft mit vollständiger Ausgabe, PDAL
berechnet das reine TIN. Stadt und Wald erhalten acht rotierende
Runden je Eingabe- und Worker-Konfiguration. Autzen, der breite
Waldzuschnitt und die Höhenkachel erhalten fünf Runden mit
vier LAZ-Readern. Die Abnahme prüft jedes Profil zusätzlich
mit Kantenlimit und Java mit einem und vier LAZ-Workern.

### Abnahme der Rasterhöhen

Alle vollständigen Raster enthalten ausschliesslich endliche Höhen und
keinen NoData-Tag. Die Java-Rasterwerte und Metadaten stimmen pro Kern
mit einem und vier LAZ-Workern bitweise überein. Die nächstgelegenen
Stützpunkte wurden pro Gebiet mit Restzellen an je 13 Zellen durch
eine direkte Distanzsuche nachvollzogen.

| Gebiet | TIN-Kern | Eindeutige Stützpunkte | Kantenlimit | Ergänzte Zellen, offen / mit Limit | TIN-Höhenabweichungen zum Produkt über 1 µm / Maximum |
| --- | --- | ---: | ---: | ---: | ---: |
| Stadt | Delaunator | 728'751 | 20 m | 0 / 7'227 | 5 / 3.00 mm |
| Wald | Delaunator | 428'313 | 5 m | 0 / 249 | 2 / 11.54 mm |
| Autzen | Delaunator | 62'290 | 20 ft | 0 / 0 | 0 / <1 µm |
| Breiter Wald | Tinfour | 712'642 | 5 m | 0 / 648 | 0 / bitgleich |
| Höhenkachel | Delaunator | 787'920 | 5 m | 0 / 272 | 3 / 6.06 mm |

Kein Wechsel zwischen TIN-Höhe und Ersatzhöhe in beiden Richtungen.
Die zehn Höhenabweichungen zum Produkt oberhalb von 1 µm wurden aus
den tatsächlich verwendeten Dreiecken nachgerechnet. Gegen PDAL
waren es 4, 2, 6, 5 und 2 Zellen in der Reihenfolge der Tabelle.
PDAL und Java ergaben in allen Profilen dieselben TIN-NoData-Masken.
Die Rastergeometrie von Autzen ist in internationalen Fuss,
die Höhentoleranz ist in US-Vermessungsfuss umgerechnet. PDAL
führt für Autzen zusätzlich das vertikale CRS EPSG:6360.
[Stützdreiecke und Zellhöhen](measurements/tin-complete-delaunator-faces.json)

### Laufzeit und Speicher

Die drei Werte je Messspalte stehen in der Reihenfolge Java-Produkt,
Java-Kandidat und PDAL 2.10.2. Die Java-Varianten erzeugen das vollständige
Raster. Das offene TIN von PDAL liefert bei diesem Vergleich bereits
einen Wert je Zelle. CPU ist die Summe aus User- und Systemzeit.
Spitzen-RSS ist der höchste gemessene Prozesswert. Der gepaarte
Gewinn ist der Median der Zeitersparnis je Runde gegenüber dem
Java-Produktstand.

| Gebiet | Eingabe | Reader | Wandzeit Median (s) | CPU Median (s) | Spitzen-RSS (MiB) | Gepaarter Gewinn |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Stadt | Boden-LAS | 1 | 1.205 / 0.892 / 0.559 | 2.05 / 1.54 / 0.55 | 285 / 246 / 246 | 27.14 % |
| Stadt | LAZ | 1 | 4.784 / 4.480 / 4.479 | 5.90 / 5.45 / 5.90 | 338 / 304 / 941 | 6.64 % |
| Stadt | LAZ | 4 | 2.310 / 1.993 / 2.622 | 7.69 / 7.17 / 7.08 | 350 / 314 / 977 | 14.16 % |
| Wald | Boden-LAS | 1 | 0.887 / 0.669 / 0.346 | 1.71 / 1.27 / 0.33 | 243 / 184 / 164 | 24.50 % |
| Wald | LAZ | 1 | 5.611 / 5.414 / 5.590 | 6.75 / 6.35 / 7.33 | 326 / 275 / 1042 | 3.73 % |
| Wald | LAZ | 4 | 2.416 / 2.197 / 2.973 | 8.94 / 8.59 / 9.12 | 349 / 295 / 1081 | 9.10 % |
| Autzen | LAZ | 4 | 2.510 / 2.450 / 2.343 | 10.37 / 10.13 / 7.57 | 481 / 480 / 807 | 2.01 % |
| Breiter Wald | LAZ | 4 | 2.858 / 2.889 / 3.420 | 9.87 / 9.99 / 9.88 | 380 / 380 / 993 | −0.46 % |
| Höhenkachel | LAZ | 4 | 2.974 / 2.589 / 3.414 | 10.21 / 9.44 / 10.00 | 392 / 383 / 1177 | 12.94 % |

Der Kandidat erfüllt die Übernahmekriterien. In Stadt und Wald gewann
er alle acht LAZ-Runden mit vier Readern. Das Spitzen-RSS sank dort um
36 bzw. 54 MiB. Der bitgleiche Tinfour-Fallback verlor im gepaarten
Median 0.46 %. Seine Laufzeiten lagen zwischen 2.783 und 3.128 s,
bei 2.801 bis 2.876 s für den Produktstand. Die Zahl der von
anderen Diagonalen zusätzlich gefüllten Zellen beträgt null.
[189 Einzelmessungen](measurements/tin-complete-delaunator.csv)

Die Restzellensuche wurde separat auf der vorbereiteten Boden-LAS mit
Kantenlimit gemessen. Je Profil liefen drei frische Prozesse. Das JFR-Ereignis
`jdk.MethodTrace` erfasst `TerrainWriter.fillNearest` einschliesslich
des Indexaufbaus. Die Mediane lagen bei 470 ms in der Stadt, 256 ms im Wald,
1.9 ms in Autzen, 470 ms im breiten Wald und 478 ms auf der Höhenkachel.
In Autzen genügte der Durchlauf über die 40'000 vorhandenen Höhen.

### Reproduktion

```sh
# Gradle-Distributionen für aad5f70 und den Kandidaten bauen.
# Die folgenden Befehle laufen im Kandidaten-Worktree.
for tile in 2019 2019-urban 2019-height autzen
do
  ./gradlew downloadTestData -Ptile="$tile"
done
python3 experiments/check-tin.py "$pdal" \
  --baseline-cli "$product/build/install/pointcloud/bin/pointcloud" \
  --baseline-faces "$product/tools/TerrainFaces.java" \
  --cli "$PWD/build/install/pointcloud/bin/pointcloud" \
  --data "$PWD/.cache/test-data" --work "$work" \
  --mode both --complete --extended --allow-mesh-differences --include-pdal \
  --name delaunator --csv "$work/delaunator.csv"
```

## Höhe über Grund gegen PDAL 2.10.2

Apple M1 mit 16 GiB, macOS 15.8.1, Temurin 25.0.3+9 mit `-Xmx512m`,
PDAL 2.10.2 und GDAL 3.13.3. Beide Engines lesen je Gebiet dieselbe
vollständige LAZ-Kachel und dasselbe vorbereitete, unkomprimierte
502 × 502 GeoTIFF. Die Raster stammen aus der kantonalen Befliegung
2019. Die acht rotierenden Runden messen frische Prozesse bei warmen
Dateicaches mit 1 und 4 LAZ-Readern, Float64-Höhenberechnung,
Statistik über `Z,HeightAboveGround`, JSON-Metadaten und `writers.null`.
`zero_ground: false` erzwingt eine Rasterabfrage für jeden Punkt.
PDAL erhält eine explizite negative Clamp-Untergrenze.

Fünf weitere rotierende Runden messen Java mit derselben Z-Statistik
mit und ohne HAG. Das Einlesen des Rasters mitsamt CRS-Validierung
und GeoTools-Initialisierung wird auf drei separaten frischen Läufen
je Kachel per JFR gemessen. Diese Durchläufe sind ausserhalb des
Wandzeitvergleichs. Die DTM-Erzeugung ist Teil des separaten
Solothurn-Beispiels.

### Vollständige HAG-Pipeline

| Gebiet | Engine | Reader | Wandzeit Median / Min–Max (s) | CPU Median (s) | Spitzen-RSS Max (MiB) | Mio. Punkte/s Median |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Stadt | Java | 1 | 4.623 / 4.376–4.905 | 5.92 | 216 | 2.59 |
| Stadt | PDAL 2.10.2 | 1 | 4.042 / 3.725–4.316 | 7.45 | 87 | 2.96 |
| Stadt | Java | 4 | 2.041 / 1.813–2.217 | 7.62 | 245 | 5.86 |
| Stadt | PDAL 2.10.2 | 4 | 3.595 / 3.443–4.089 | 7.40 | 125 | 3.33 |
| Wald | Java | 1 | 5.743 / 5.566–6.574 | 7.21 | 252 | 2.54 |
| Wald | PDAL 2.10.2 | 1 | 5.211 / 4.943–5.587 | 9.27 | 87 | 2.80 |
| Wald | Java | 4 | 2.243 / 2.138–3.070 | 9.17 | 266 | 6.50 |
| Wald | PDAL 2.10.2 | 4 | 4.611 / 4.368–5.133 | 9.84 | 134 | 3.16 |

Mit vier Readern braucht Java im Wandzeitmedian 43 % weniger Zeit
in der Stadt und 51 % weniger im Wald. Mit einem Reader ist PDAL
in beiden Gebieten schneller. Die Java-Prozesse belegen in allen
Konfigurationen mehr Spitzen-RSS. Die Abnahme verglich dazu in der
Stadt 3'512'369 und im Wald 10'262'303 vollständige, unveränderte
LAS-Records mit HAG über 5 m. Die Statistik für Z und HAG lag
innerhalb der bestehenden Produkttoleranz.

### Zusatzaufwand in Java

| Gebiet | Reader | Nur Z-Statistik (s) | HAG und gleiche Z-Statistik (s) | Gepaarter Mehrbedarf (s) |
| --- | ---: | ---: | ---: | ---: |
| Stadt | 1 | 3.673 | 4.426 | 0.690 |
| Stadt | 4 | 1.219 | 1.856 | 0.629 |
| Wald | 1 | 4.876 | 5.543 | 0.675 |
| Wald | 4 | 1.524 | 2.134 | 0.623 |

Das Laden des DEMs einschliesslich der CRS-Prüfung und
GeoTools-Initialisierung dauerte laut `jdk.MethodTrace` im Median
0.516 s in der Stadt und 0.518 s im Wald. Die Kontrollmessung
vergleicht dieselbe Z-Statistik mit und ohne HAG und umfasst den
Rasteraufbau und alle Punktabfragen. Die JFR-Zeit stammt aus
separaten Prozessen mit eigener JIT-Aufwärmung.
[104 Einzelmessungen](measurements/hag-dem-pdal.csv) · [Sechs JFR-Ladezeiten](measurements/hag-dem-load.csv)

### Reproduktion

```sh
./gradlew installDist
./gradlew downloadTestData
./gradlew downloadTestData -Ptile=2019-urban
python3 tools/check-hag-dem.py "$pdal" \
  --benchmark-csv .cache/hag-dem/benchmark.csv --setup-csv .cache/hag-dem/setup.csv
```

## Einzelbaumdetektion im Solothurner Wald

Ein Durchlauf durch die LAZ sammelt die beiden Zellgewinner nach
Höhe über Grund (CHM) und absolutem Z (Vegetations-DSM). Verwendet
werden Vegetationsklassen 3–5, `Withheld == 0` und HAG ab 5 m.
Die Variante gibt XYZ und HAG am selben Originalrücklauf aus.
Wir verglichen auf einem 0,5-m-Gitter feste Kreisradien von 2, 3
und 4 m und einen höhenabhängigen Radius `clamp(0.12 × HAG, 1.5, 4)`.
Gleich hohe Zellen im Suchkreis erhalten Nordwest-Vorrang.
Der 4-m-Halo liegt ausserhalb des halboffenen Ausgabekerns.
[Studie zum Hangeffekt](https://doi.org/10.1016/j.isprsjprs.2015.02.013)

### Fachliche Abnahme

Die [visuellen Referenzen](../experiments/treetop-reference/README.md)
standen vor der ersten Detektion fest. Die Eins-zu-eins-Zuordnung
maximiert bei höchstens 2 m Abstand zuerst die Trefferzahl und
minimiert danach die Summe der XY-Abstände. CHM und DSM mit 3 m
Radius erreichten auf den Kalibrierflächen praktisch dasselbe
mittlere F1. Die vorab definierte Regel wählte den schnelleren
festen Kandidaten `dsm-r3` vor dem Öffnen der Prüfergebnisse.
Die Detektorzeiten für diese Stichentscheidung lagen bei rund
1,12 ms (DSM) und 1,16 ms (CHM).

| Radius | CHM, mittleres Kalibrier-F1 | DSM, mittleres Kalibrier-F1 |
|---|---:|---:|
| 2 m | 0,548 | 0,539 |
| 3 m | 0,645 | **0,646** |
| 4 m | 0,623 | 0,596 |
| Höhenabhängig | 0,472 | 0,461 |

| Prüffläche | Referenzen | Detektiert | Zugeordnet | Precision | Recall | F1 | Ø XY-Fehler | HAG-MAE | Ausgeschlossen |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Wald, 8° mediane Neigung | 52 | 76 | 44 | 57,9 % | 84,6 % | 0,688 | 0,78 m | 0,46 m | 954,3 m² |
| Wald, 39° mediane Neigung | 99 | 86 | 55 | 64,0 % | 55,6 % | 0,595 | 0,80 m | 0,36 m | 344,5 m² |
| Höhenkachel, Wald und Offenland | 34 | 39 | 29 | 74,4 % | 85,3 % | 0,795 | 0,28 m | 0,06 m | 160,5 m² |
| Gesamt | 185 | 201 | 128 | **63,7 %** | **69,2 %** | **0,663** | | | |

Der Detektor bleibt im Experimentmodul. Die Freigabe verlangte
insgesamt je 80 % Precision und Recall sowie F1 ≥ 0,75 je Fläche.
Im flacheren Wald blieben 32 Detektionen ohne Zuordnung.
Die Karte zeigt mehrere davon auf plausiblen Kronen, die in
der visuellen Referenz fehlen können. Im steilen Wald blieben
44 Referenzspitzen ohne Treffer. Bei allen 44 lag im 3-m-Kreis
der Referenzzelle ein höheres absolutes Z. Bei 20 davon hatte
der höchste Nachbar eine geringere Höhe über Grund als der
Zellgewinner der Referenzzelle. Ob beide Zellen zur selben
Krone gehören, ist aus dem Höhenvergleich allein offen.
Diese Fälle eignen sich für einen Vergleich mit lokaler
Prominenz und einem höhenabhängigen Fenster mit grösserem
Mindestradius. Grau hinterlegte Bereiche in der Karte waren
von der Zuordnung ausgeschlossen.
[Prüfkarte](measurements/treetops-holdout.jpg) · [Qualitätsdaten](measurements/treetops-quality.json)

### Technische Abnahme

PDAL 2.10.2 und GDAL 3.13.3 lieferten auf denselben Gittern
466'560 identische Float64-Zellwerte, davon 131'578 NoData.
`lidR` 4.3.3 bestätigte auf den fünf Rastern alle Maxima mit
eindeutigem Ergebnis über 30 Vergleiche. Neun Ergebniszellen
unterschieden sich bei Gleichständen. Für `dsm-r3` waren alle
fünf Resultate identisch. Auf `cal-flat` schrieben Java mit
einem und vier Readern acht bytegleiche CSV-Paare. Eine Teilung
des Kerns in West und Ost lieferte die gleichen XYZ/HAG-Zeilen.
Die IDs beginnen pro CSV bei 1.

### Laufzeit des gewählten Kandidaten

Der 480 × 480 m grosse Kern umfasst bei 0,5 m Auflösung
921'600 Zellen und 30'976 zusätzliche Halozellen. Von den
14'566'627 LAZ-Punkten wurden 9'796'048 in das erweiterte
Gitter übernommen. Jeder Lauf schrieb dieselben 2'498
Baumspitzen bytegleich. Gemessen auf Apple M1, macOS 15.8.1,
Temurin 25.0.3+9 und `-Xmx512m`, mit warmem Dateicache und
je acht rotierenden Läufen in frischen Prozessen.

| LAZ-Worker | Wandzeit, Median (Min–Max) | CPU, Median | Spitzen-RSS, Median / Max | DEM, Median | Dekodieren und Raster, Median | Detektor inkl. Suchfensteraufbau, Median |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 5,697 s (5,651–6,020 s) | 7,255 s | 274,9 / 279,1 MiB | 0,556 s | 4,973 s | 21,6 ms |
| 4 | 2,294 s (2,184–3,699 s) | 9,330 s | 292,0 / 300,5 MiB | 0,566 s | 1,596 s | 23,8 ms |

Mit vier Workern entsprachen 23,8 ms im Median 1,02 % der
Wandzeit. Die Laufzeitgrenzen von 0,5 s und 20 % sind erfüllt.
[16 Einzelmessungen](measurements/treetops-runtime.csv)

### Reproduktion

Das optionale Gradle-Projekt nutzt die Produkt-CLI-Abhängigkeiten.
Für den Referenzlauf braucht es PDAL 2.10.2 mit GDAL, R mit
`lidR` und `terra` in `.cache/treetops/r-library` sowie Python mit
NumPy und SciPy für die Zuordnung. Die Vergleiche liefen mit
R 4.5.3, `lidR` 4.3.3, `terra` 1.9.60, NumPy 2.0.2 und SciPy 1.13.1.
Die Realdaten und das Wald-DEM liegen an den Pfaden aus der
[HAG-Abnahme](#höhe-über-grund-gegen-pdal-2102).

```sh
./gradlew downloadTestData
./gradlew downloadTestData -Ptile=2019-height
./gradlew -Pexperiments :experiments:test installDist
mkdir -p .cache/treetops/r-library
Rscript --vanilla -e 'install.packages(c("lidR", "terra"), lib=".cache/treetops/r-library", repos="https://cloud.r-project.org")'
python3 experiments/check-treetops.py "$pdal" --generate
python3 experiments/score-treetops.py experiments/treetop-reference \
  .cache/treetops/comparison calibrate > .cache/treetops/nomination.json
python3 experiments/score-treetops.py experiments/treetop-reference \
  .cache/treetops/comparison evaluate .cache/treetops/nomination.json
python3 experiments/check-treetops.py "$pdal" --verify
python3 experiments/check-treetops.py "$pdal" --benchmark dsm-r3
```

## Lokale Prominenz für Baumspitzen

Das Java-Experiment sammelt die vorhandenen CHM- und DSM-Zellgewinner
in einem LAZ-Durchlauf. Es vergleicht rohe und maskiert geglättete
Oberflächen, jeweils mit 3-m-Maxima und fünf Prominenzschwellen von
0,25 bis 1,50 m. Der Gaussian-Filter hat σ = 0,5 m und einen
separablen Filterradius von 1,5 m. Er normiert über vorhandene Werte
und erhält NoData an unbelegten Zellen. Die Ausgabe verwendet XYZ/HAG
des ursprünglichen Zellgewinners.

Die Prominenzsuche beginnt an einem lokalen Maximum mit mindestens
drei gültigen Nachbarzellen. Sie verfolgt achtfach verbundene Zellen
im 6-m-Kreis, solange die Oberfläche höher als Spitzenhöhe minus
Schwelle ist. Ein erreichbarer höherer Wert verwirft die Spitze.
Gleichstände entscheidet die Nordwest-Reihenfolge. Der Halo umfasst
6 m und bei Glättung weitere 1,5 m. Ein Sattel genau auf der
Prominenzschwelle genügt für zwei getrennte Spitzen.
[Studie zu Höhendynamik und Baumspitzen](https://doi.org/10.1109/IGARSS53475.2024.10640418)

### Bisherige Flächen und Variantenwahl

Auf den fünf bekannten Flächen erreichte `chm-gauss-prom-150` mit
F1 **0,667** das beste Mittel der Prominenzvarianten. `dsm-r3`
lag bei 0,674. Mit demselben 3-m-Detektor und Gaussian-Glättung
erreichten sowohl CHM als auch DSM rund 0,697. Auf der bekannten
steilen Fläche stiegen die Treffer mit dem geglätteten CHM und
3 m Radius von 55 auf 61 bei 99 Referenzen. Die Zahl der
Detektionen stieg von 86 auf 100. Für den Blindtest wurde wie
vereinbart die beste Prominenzvariante eingefroren.

### Zwei neue Blindflächen

Die [Referenzen](../experiments/treetop-prominence-reference/README.md)
beruhen auf zwei getrennten visuellen Beurteilungen von Original-LiDAR
und Orthofotos. Bewertet wurden 146 gemeinsam markierte Spitzen im
Oberstand. Für den Vergleich gilt eine eindeutige Zuordnung bis 2 m.

| Fläche | Methode | Referenzen | Detektiert | Zugeordnet | Precision | Recall | F1 |
|---|---|---:|---:|---:|---:|---:|---:|
| Steil, 31,8° | DSM, 3 m Radius | 80 | 99 | 49 | 49,5 % | 61,3 % | 0,547 |
| Steil, 31,8° | CHM, 1,5 m Prominenz | 80 | 74 | 43 | 58,1 % | 53,8 % | 0,558 |
| Mässig geneigt, 12,9° | DSM, 3 m Radius | 66 | 89 | 57 | 64,0 % | 86,4 % | 0,735 |
| Mässig geneigt, 12,9° | CHM, 1,5 m Prominenz | 66 | 76 | 54 | 71,1 % | 81,8 % | 0,761 |
| Gesamt | DSM, 3 m Radius | 146 | 188 | 106 | 56,4 % | 72,6 % | 0,635 |
| Gesamt | CHM, 1,5 m Prominenz | 146 | 150 | 97 | **64,7 %** | **66,4 %** | **0,655** |

Die Prominenz liefert neun Referenzspitzen, die der 3-m-Detektor
verfehlt, und verliert 18 seiner Treffer. Im steilen Wald sinkt
die Zahl der nicht zugeordneten Detektionen von 50 auf 31. Dort
bleiben 37 von 80 markierten Spitzen ohne Treffer. Die Schranken
von je 80 % für Precision und Recall sowie F1 ≥ 0,75 je Fläche
sind verfehlt. Der Detektor bleibt im Experimentmodul.
[Blindprüfkarte](measurements/treetop-prominence-holdout.jpg) ·
[Vollständige Qualitätsdaten](measurements/treetop-prominence-quality.json)

### Unabhängige Methoden

Die folgenden Ergebnisse betreffen die fünf bekannten Flächen.
Die Rastervergleiche verwenden dasselbe 0,5-m-Gitter und die
gleichen Ausgabekerne. Die 3-m-Suche in NumPy reproduzierte die
rohen Java-CHM- und DSM-Ausgaben in allen zehn Fällen exakt.
Das amtliche Vegetationshöhenmodell wurde aus 25-cm-Zellen per
ausgerichtetem 2×2-Maximum aggregiert. GDAL und ein separater
Arrayvergleich bestätigten Georeferenzierung, NoData und jedes
aggregierte Maximum. Die alternative Spike-free-Oberfläche kam
aus den normalisierten Erstreflexionen der gleichen Auswahl.
Auf der Höhenkachel änderte sie gegenüber dem einfachen CHM
34'174 Zellen in der Abdeckungsmaske ab 5 m.

| Externe Methode | Mittleres F1 | Rasterdetektionen ohne Originalrücklauf |
|---|---:|---:|
| Amtliches VHM, 3 m Radius | 0,569 | 35 |
| `lidR::lspikefree`, 3 m Radius | 0,667 | 12 |
| `lidaRtRee` auf CHM | 0,582 | 5 |
| `lidaRtRee` auf DSM und DTM | 0,604 | 3 |
| `lidR::li2012`, 3D | 0,462 | 0 |

Die ungestützten Rasterdetektionen werden in der Precision
als unzugeordnet mitgezählt. Li2012 verarbeitete je Ausschnitt
66'876 bis 302'626 Voxelrepräsentanten innerhalb des Zeitlimits.
`lidaRtRee` verwendet sein internes Ersetzen von NoData durch
Null beziehungsweise Gelände-Z. Die Ergebnisse stammen aus
R 4.5.3, `lidR` 4.3.3, `terra` 1.9.60 und `lidaRtRee` 4.0.9.
[Solothurner VHM](https://data.geo.so.ch/?filter=ch.so.agi.lidar_2019.ndsm_vegetation) ·
[lidR](https://cran.r-project.org/web/packages/lidR/refman/lidR.html) ·
[lidaRtRee](https://lidar.pages-forge.inrae.fr/lidaRtRee/reference/tree_segmentation.html)

### Laufzeit

Die Messung auf dem M1 steht wegen gleichzeitiger Systemlast aus.
Der vorbereitete Benchmark verwendet denselben 480 × 480 m grossen
Kern der Waldkachel, 0,5 m Zellbreite und `-Xmx512m`. Er misst den
3-m-DSM-Detektor und `chm-gauss-prom-150` mit je einem und vier
LAZ-Workern in acht rotierenden Runden. Nach zwei Aufwärmläufen
startet er für jede Messung einen frischen Prozess. Er vergleicht
die CSV-Bytes und Punktzähler je Methode und protokolliert Wandzeit,
Prozess-CPU-Zeit, Spitzen-RSS, Durchsatz, Rasteraufbau, Glättung
und Detektor. Die Schranken für die Detektion samt Glättung sind
0,5 s und 20 % der Vier-Worker-Wandzeit im Median.

### Reproduktion

Die Pfade und Voraussetzungen aus dem vorherigen Experiment
gelten weiter. Jeder Vergleich bekommt ein frisches Arbeitsverzeichnis.
Für die externen Vergleiche installieren wir `lidaRtRee` in der
separaten R-Bibliothek und verwenden die bereits eingerichtete
`lidR`-Bibliothek. Die Prüfflächen werden nach Nominierung mit den
eingefrorenen Referenzdateien ausgewertet.

```sh
./gradlew -Pexperiments :experiments:test installDist
treetop_run=$(mktemp -d .cache/treetop-prominence.XXXXXX)
mkdir -p "$treetop_run/r-library"
R_LIBS_USER=.cache/treetops/r-library Rscript --vanilla -e \
  'install.packages("lidaRtRee", lib=commandArgs(TRUE)[1], repos="https://cloud.r-project.org")' \
  "$treetop_run/r-library"
python3 experiments/check-treetop-prominence.py "$pdal" develop --work "$treetop_run"
python3 experiments/check-treetops-external.py "$pdal" --work "$treetop_run"
python3 experiments/check-treetop-prominence.py "$pdal" external --work "$treetop_run"
python3 experiments/check-treetop-prominence.py "$pdal" holdout --work "$treetop_run"
python3 experiments/check-treetop-prominence.py "$pdal" verify --work "$treetop_run"
python3 experiments/check-treetop-prominence.py "$pdal" benchmark --work "$treetop_run"
```
