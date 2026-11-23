# PointCloud Java

PointCloud Java entsteht als eigenständige Java-25-Bibliothek und CLI für
lokale LAS-/LAZ-Dateien. Der [Auftrag](codex-auftrag-pointcloud-java.md) begrenzt
das Produkt auf LAS-Ausgabe und lineare Pipelines einer definierten PDAL-Teilmenge.
CRS-Reprojektion, COPC-/EPT-Raumsuche und Plugin-Graphen liegen ausserhalb
dieses Umfangs. Der aktuelle Stand ist im [Etappenprotokoll](docs/implementation-log.md)
festgehalten.

Mit einem JDK 25 im `JAVA_HOME` oder auf `PATH`:

```sh
./gradlew clean check installDist
build/install/pointcloud/bin/pointcloud --help
build/install/pointcloud/bin/pointcloud --version
```

Der Standardbuild verwendet den Gradle-Wrapper und Groovy DSL. Er benötigt
keine nativen LiDAR-Bibliotheken, Spezialhardware oder Realdaten.

Die Kachel für die separate Realdatenprüfung wird mit
`./gradlew downloadTestData` angefordert. Quelle und Cachepfad stehen im
[Herkunftsnachweis](docs/provenance.md).
