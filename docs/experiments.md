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
