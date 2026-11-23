# PointCloud Java

PointCloud Java ist eine eigenständige Java-25-Bibliothek und CLI für
lokale LAS-/LAZ-Dateien. Der [Auftrag](codex-auftrag-pointcloud-java.md) begrenzt
das Produkt auf LAS-Ausgabe und lineare Pipelines einer definierten PDAL-Teilmenge.
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

Die Realdatenbeispiele laufen ab Repositorywurzel.

```sh
./gradlew downloadTestData
"$cli" pipeline examples/ground-crop.json --metadata build/results/java-metadata.json
```

Die [Formatmatrix und Pipeline-Optionen](docs/architecture.md) beschreiben
den unterstützten Umfang. Die Kachelquelle steht im [Herkunftsnachweis](docs/provenance.md).

Die Referenzabnahme verwendet PDAL 2.10.2 mit LazPerf. Die Vergleichsziele
`build/results/ground-crop.las`, `decimate.las` und `so-2014.las` müssen
vor dem Start frei sein.

```sh
./gradlew downloadTestData -Ptile=2014
python3 tools/check-real-data.py /pfad/zu/pdal
```
