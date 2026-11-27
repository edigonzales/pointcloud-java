# PointCloud Java

PointCloud Java ist eine eigenständige Java-25-Bibliothek und CLI für
lokale LAS-/LAZ-Dateien. Der [Auftrag](codex-auftrag-pointcloud-java.md) begrenzt
das Produkt auf LAS- und GeoTIFF-Ausgabe sowie lineare Pipelines einer definierten PDAL-Teilmenge.
CRS-Reprojektion, COPC-/EPT-Raumsuche und Plugin-Graphen liegen ausserhalb
dieses Umfangs. Der aktuelle Stand ist im [Etappenprotokoll](docs/implementation-log.md)
festgehalten.

Mit einem JDK 25 im `JAVA_HOME` oder auf `PATH`:

```sh
./gradlew clean check installDist
cli=build/install/pointcloud/bin/pointcloud
"$cli" --help
"$cli" info src/test/resources/las/format-8.laz
```

Der Fixture-Aufruf beginnt mit:

```text
LAS 1.4, point format 8, LAZ compressed
Header point count: 5
Record length: 40
```

Der Standardbuild verwendet den Gradle-Wrapper und Groovy DSL. Er benötigt
keine nativen LiDAR-Bibliotheken, Spezialhardware oder Realdaten.

`./gradlew distZip` erzeugt `build/distributions/pointcloud.zip`. Das ZIP
enthält den CLI-Launcher, die Bibliotheken und die Lizenzdatei. Für Bibliotheksaufrufe
gehören `pointcloud.jar`, `codec.jar` und die weiteren JARs aus
`build/install/pointcloud/lib/` auf den Klassenpfad.

```sh
java -cp 'build/install/pointcloud/lib/*' examples/ReadBatches.java src/test/resources/las/format-3.laz
```

Die Realdatenbeispiele laufen ab Repositorywurzel.

```sh
./gradlew downloadTestData
"$cli" pipeline examples/ground-crop.json --metadata build/results/java-metadata.json
```

Ground-Crop verarbeitet die 14'566'627 Punkte der Pflichtkachel und
schreibt 195'738 Bodenpunkte im gewählten Ausschnitt.
`"$cli" info .cache/test-data/so-2019.laz` zeigt unter anderem
`Projected CRS: EPSG:2056` und den GeoTIFF-Text für CH1903+ / LV95.

[Polygon-Ground-Crop](examples/polygon-ground-crop.json) verwendet einen
L-förmigen Perimeter und schreibt 143'244 Bodenpunkte als
`build/results/polygon-ground-crop.las`. Die JSON-Metadaten enthalten
die Punktzahlen und die Statistik der ausgewählten Punkte.

```sh
"$cli" pipeline examples/polygon-ground-crop.json --metadata build/results/polygon-ground-crop.json
```

[Polygon-Ground-Grid](examples/polygon-ground-grid.json) rastert die
Bodenpunkte im L-Perimeter in ein 200 × 200-Zellen-GeoTIFF mit 1 m Zellbreite.
Die vier Bänder enthalten minimale, maximale und mittlere Höhe sowie
Punktzahl je Zelle. Die Metadaten zählen die 143'238
eingerasterten Punkte separat. Bestehende Java-Pipelines migrieren
von `"type": "writers.gdal"` zu `"type": "writers.raster"`.

```sh
"$cli" pipeline examples/polygon-ground-grid.json --metadata build/results/polygon-ground-grid.json
```

[Ground-Grid-Fill](examples/ground-grid-fill.json) rastert einen rechteckigen
Solothurn-Ausschnitt und füllt mit `window_size: 1` Lücken aus direkt
benachbarten Messzellen. Im Band `mean` entsteht ein Geländemodell.
Das Band `count` gibt weiterhin die Anzahl gemessener Punkte je Zelle an.

```sh
"$cli" pipeline examples/ground-grid-fill.json --metadata build/results/ground-grid-fill.json
```

[Ground-Grid-Height](examples/ground-grid-height.json) schreibt ein
einbandiges Höhenraster mit Fenster 2 und verlustfreiem Deflate.
[Ground-Grid-Intensity](examples/ground-grid-intensity.json) schreibt
fünf Bänder für die Intensität, einschliesslich der Zellstreuung `stdev`.

```sh
"$cli" pipeline examples/ground-grid-height.json --metadata build/results/ground-grid-height.json
"$cli" pipeline examples/ground-grid-intensity.json --metadata build/results/ground-grid-intensity.json
```

[`writers.dtm`](docs/architecture.md#tin-terrainmodell) interpoliert die
Geländehöhe aus einem Delaunay-Dreiecksnetz der selektierten Bodenpunkte.
Zwei Beispiele rechnen ein Höhenraster für ein bebautes Gebiet und
einen Wald in Solothurn.

```sh
./gradlew downloadTestData -Ptile=2019-urban
"$cli" pipeline examples/dtm-solothurn-urban.json --laz-workers 4 --metadata build/results/dtm-solothurn-urban.json
"$cli" pipeline examples/dtm-solothurn-forest.json --laz-workers 4 --metadata build/results/dtm-solothurn-forest.json
```

Die vollständigen Beispiele setzen `fill_nodata: "nearest"`.
Zellmitten ohne zulässiges Stützdreieck übernehmen die Höhe des
nächstgelegenen Bodenpunkts.

```sh
"$cli" pipeline examples/dtm-solothurn-urban-complete.json --laz-workers 4 --metadata build/results/dtm-solothurn-urban-complete.json
"$cli" pipeline examples/dtm-solothurn-forest-complete.json --laz-workers 4 --metadata build/results/dtm-solothurn-forest-complete.json
```

Das [Stadtbeispiel mit Stützmaske](examples/dtm-solothurn-urban-support.json)
schreibt zusätzlich ein deckungsgleiches GeoTIFF. Die Codes `0`, `1` und `2`
kennzeichnen unbelegte Zellen, TIN-Höhen und Höhen der nächsten Bodenmessung.

```sh
"$cli" pipeline examples/dtm-solothurn-urban-support.json --laz-workers 4 --metadata build/results/dtm-solothurn-urban-support.json
```

[`filters.hag_dem`](docs/architecture.md#höhe-über-grund) berechnet aus
einem vorhandenen DTM die Punktdimension `HeightAboveGround`. Nach dem
Stadt-DTM oben liest dieses Beispiel erneut die ursprüngliche LAZ,
wählt Vegetationspunkte über 5 m und hängt mit
`extra_dims: "HeightAboveGround=double"` die Höhe über Grund an
deren Originalrecords an. Die Metadaten enthalten die Statistik
der relativen Höhen.

```sh
"$cli" pipeline examples/hag-dem-solothurn-urban.json --laz-workers 4 --metadata build/results/hag-dem-solothurn-urban.json
pdal info --schema build/results/hag-dem-solothurn-urban.las --readers.las.use_eb_vlr=true
```

Das [Kronenhöhenmodell](examples/chm-solothurn-forest.json) nutzt das
vollständige Wald-DTM oben und rastert `HeightAboveGround`.
Das GeoTIFF enthält die maximale Höhe und die Punktzahl je 0,5-m-Zelle
für Vegetation ab 5 m über Grund.

```sh
"$cli" pipeline examples/chm-solothurn-forest.json --laz-workers 4 --metadata build/results/chm-solothurn-forest.json
```

Das [Riedholzer Höhen-Schwellenraster](examples/height-thresholds-riedholz.json)
schreibt die Anzahl ausgewählter Erstreflexionen und ihren jeweiligen Anteil
über 0,5 m, 2 m und 5 m Höhe über Grund. Die Realdatenvorbereitung
verwendet PDAL 2.10.2 und GDAL 3.13.3.

```sh
./gradlew downloadTestData -Ptile=2019-riedholz
python3 tools/check-height-thresholds.py "$(command -v pdal)" --prepare-dem
"$cli" pipeline examples/height-thresholds-riedholz.json --laz-workers 4 --metadata build/results/height-thresholds-riedholz.json
```

Die [Riedholzer Perzentile](examples/raster-percentiles-riedholz.json)
verwenden dieselben Eingangsdaten und schreiben `count,p50,p90`.

```sh
"$cli" pipeline examples/raster-percentiles-riedholz.json --laz-workers 4 --metadata build/results/raster-percentiles-riedholz.json
```

Für indizierte LAZ-Dateien kann die CLI mit `--laz-workers 4` bis zu vier
unabhängige Chunks parallel dekodieren. Ohne Option liest sie seriell.

Die [Formatmatrix und Pipeline-Optionen](docs/architecture.md) beschreiben
den unterstützten Umfang. Die Unterschiede bei Ausdrücken und Extra Bytes
stehen unter [Kompatibilität mit PDAL 2.10.2](docs/architecture.md#kompatibilität-mit-pdal-2102).
Die Kachelquelle steht im [Herkunftsnachweis](docs/provenance.md).

Die Referenzabnahme auf macOS oder Linux verwendet PDAL 2.10.2 mit LazPerf.
Die Vergleichsziele
`build/results/ground-crop.las`, `polygon-ground-crop.las`, `decimate.las` und `so-2014.las` müssen
vor dem Start frei sein.

```sh
./gradlew downloadTestData -Ptile=2014
python3 tools/check-real-data.py /pfad/zu/pdal
```

Für Qualitätskontrollen lassen sich LAS-Flags wie `Withheld == 0`
in `filters.expression` und Klassenhäufigkeiten mit
`count: "Classification"` in `filters.stats` verwenden. Der separate
PDAL-Vergleich prüft Solothurn 2019 und Autzen.

```sh
./gradlew downloadTestData -Ptile=autzen
python3 tools/check-las-flags.py /pfad/zu/pdal
```

Die Rasterabnahme verwendet zusätzlich GDAL für Georeferenzierung und
Float64-Zellwerte. Die GDAL-Werkzeuge müssen im `PATH` oder neben `pdal`
verfügbar sein.

```sh
python3 tools/check-ground-grid.py /pfad/zu/pdal
python3 tools/check-ground-grid.py /pfad/zu/pdal --release
python3 tools/check-dtm.py /pfad/zu/pdal
```

Der Parallelvergleich auf macOS prüft Solothurn 2014 und Autzen mit
seriellem Java, `--laz-workers 4` und PDAL mit vier Reader-Workern.

```sh
./gradlew downloadTestData -Ptile=autzen
python3 tools/check-parallel-data.py /pfad/zu/pdal .cache/experiments/parallel-real-data.csv
```

Die [Realdatenabnahme](docs/implementation-log.md#p11--konsolidierung-und-lieferung)
und [Performance-Experimente](docs/experiments.md) enthalten die ausgeführten
Prüfungen, Toolchains und Einzelmessungen.
