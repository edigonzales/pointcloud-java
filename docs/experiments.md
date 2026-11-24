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

Am kleinen Fixchunk-Fixture wurde die Unterscheidung geprüft:
Mit absolutem Tabellenzeiger gleich Punktdatenoffset greift der
sequenzielle Pfad, 50'003 vollständige Records wurden verglichen.
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

Die Befehle verwenden denselben Klassenpfad aus der installierten
JVM-Distribution. Für das Vergleichsskript muss die Ground-Crop-Referenz
aus E1 unter `.cache/experiments/e1-baseline.las` und `e1-baseline.json`
liegen. Alternativ kann sie mit dem installierten Standardprodukt
erzeugt und vor der Messung aus `build/results` verschoben werden.

```sh
./gradlew --offline installDist
work=.cache/experiments/p9
mkdir -p "$work"
product_cp=$(printf '%s\n' build/install/pointcloud/lib/*.jar | paste -sd ':' -)
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -XX:AOTMode=record \
  -XX:AOTConfiguration="$work/ground.aotconfig" -cp "$product_cp" \
  pointcloud.Main pipeline examples/ground-crop.json --metadata "$work/train.json"
cmp build/results/ground-crop.las .cache/experiments/e1-baseline.las
rm build/results/ground-crop.las
/usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -XX:AOTMode=create \
  -XX:AOTConfiguration="$work/ground.aotconfig" -XX:AOTCache="$work/ground.aot" -cp "$product_cp"
JAVA_HOME="$graal" /usr/bin/time -lp "$graal/bin/native-image" -J-Xmx8g \
  -H:ConfigurationFileDirectories=experiments/native-image -cp "$product_cp" \
  -o "$work/pointcloud-native" pointcloud.Main
file "$work/pointcloud-native"
otool -L "$work/pointcloud-native"
python3 experiments/p9-runtimes.py .cache/jdks/temurin27/Contents/Home/bin/java
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
./gradlew -Pexperiments -Ptornado :experiments:installDist
work=.cache/experiments/p10
mkdir -p "$work"
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
