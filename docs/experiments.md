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
