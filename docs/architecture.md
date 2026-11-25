# LAS- und LAZ-Verträge

## Lesen und Schreiben

| LAS-Version | Formate | LAS-Eingabe und -Ausgabe | LAZ-Eingabe |
| --- | --- | --- | --- |
| 1.2 | 0, 1, 2, 3 | Ja | Kompressor 2, Arithmetic Coder 0, Legacy-Items v2 |
| 1.4 | 6, 7, 8 | Ja | Kompressor 3, Arithmetic Coder 0, moderne Items v3 |

Die Signatur, Headerstruktur und Codec-Items entscheiden über das Dateiformat.
Waveform-Formate und deren Datenreferenzen, weitere Versions-/Formatkombinationen,
LAZ-Kompatibilitätsmodus und spezielle LASzip-EVLR-Referenzen werden abgelehnt.
Die Ausgabe ist LAS (`.las`). Die Formatmatrix ist durch kleine LAS- und
unabhängig erzeugte LAZ-Fixtures belegt.

`LasReader` besitzt einen FileChannel und einen wiederverwendeten
`PointBatch`. Ein Batch enthält vollständige Little-Endian-Records in
einem Byte-Array. Aufrufer können rohe signed XYZ, skalierte `double`-Werte
oder vollständige Records lesen. Der Reader darf die Daten beim nächsten
`readBatch()` überschreiben. Der Bytepuffer ist auf 8 MiB begrenzt. Der
Vorgabewert ist 32'768 Punkte, bei breiten Records verkleinert sich die
effektive Batchgrösse entsprechend. Reader und Writer gehören zum öffnenden
Thread. Reader, Writer und LAZ-Arena werden mit try-with-resources geschlossen.

Der Decoder verwendet eine explizit schliessbare, read-only Dateizuordnung.
Chunkgrenzen, Tabellenzählung und Gesamtlänge der Layer werden überprüft.
Die Tabelle ist auf 1'000'000 Chunks begrenzt. Adaptive Tabellen werden mit
32-Bit-Punktsummen interpretiert, während der Produktpfad Punktanzahlen und
Offsets als `long` führt. Ein Layer-Chunk darf bis zu 64 MiB komprimierte
Layerdaten vorhalten. Der LASzip-Abwesenheitsmarker für feste Chunks ist
sequenziell lesbar, wenn die deklarierte Recordanzahl genau am Ende des
Punktdatenbereichs erreicht wird.
Beliebige formal dekodierbare Bitfehler sind mit diesem Format strukturell
oft nicht erkennbar.

Mit `--laz-workers 1` liest die CLI seriell. Werte von 2 bis 4 erlauben
parallele Dekodierung indizierter LAZ-Chunks. Ein Worker besitzt seinen
Decoder und zwei wiederverwendete Punktpuffer. Alle Worker teilen die
einmal validierte Chunktabelle. Die Pipeline erhält die Chunks in
Dateireihenfolge und berechnet Statistik und LAS-Ausgabe in dieser
Reihenfolge. Die Workerzahl ist durch die Zahl verfügbarer Chunks begrenzt.
Lokale COPC-Dateien werden über ihre LAZ-Chunks in physischer
Dateireihenfolge gelesen.

Unkomprimierte LAS-Dateien, LAZ-Dateien mit gültig fehlender Chunktabelle
oder weniger als zwei Chunks sowie Dateien mit einem dekodierten Chunk
über 8 MiB werden seriell gelesen. Bei LAS 1.4 gilt die 8-MiB-Grenze
zusätzlich für den komprimierten Chunkbereich, da der Decoder seine Layer
in Puffern hält. Bei vier Workern sind damit höchstens acht dekodierte
Chunkpuffer mit zusammen 64 MiB unterwegs. Decoderzustand, komprimierte
Layer, Chunktabelle und Dateizugriffe erhöhen den Prozessspeicher zusätzlich.
Beschädigte Chunktabellen führen zu einem Eingabefehler.

`LasWriter` übernimmt ausgewählte Records in der vom Aufrufer übergebenen
Reihenfolge und quantisiert sie nicht erneut. Version, Format und Scale/
Offset folgen der Quelle. Anzahl, Return-Zähler und Bounds werden aus
der Auswahl berechnet. In Format 6–8 sind die Legacy-Zähler null. Für eine
leere Ausgabe werden Null-Bounds geschrieben. `finish()` benötigt den
verifizierten Endzustand des Readers. Die vollständige Datei wird im
Zielverzeichnis temporär geschrieben und nach dem Headerabschluss
veröffentlicht. Ohne `--overwrite` veröffentlicht der Writer die LAS-Datei
per Hardlink im Zielverzeichnis. Das Dateisystem muss dafür Hardlinks
unterstützen. Mit `--overwrite` erfolgt ein atomarer Rename. Der Writer
lehnt Aliase der Eingabe ab. Die CLI schützt auch die Pipeline-Konfiguration
vor Überschreiben durch die LAS-Ausgabe.

Die CLI unterbricht einen aktiven Pipelinelauf bei regulärer JVM-
Beendigung. Der Shutdown-Hook wartet auf dessen Ressourcenschluss
und die Bereinigung des temporären Outputs. Bibliotheksaufrufe
verwenden die Interrupts des aufrufenden Threads.

## Metadaten

CRS- und Extra-Bytes-VLRs sowie gewöhnliche unbekannte VLRs und EVLRs
werden direkt aus der Quelle übertragen. Der LASzip-VLR entfällt. COPC-
und LAX-Indexdaten, die LAStools-Tile- und Originalbestandsrecords
(`LAStools` 10/20 und `lastools tile`) werden entfernt, weil sie sich auf
die ursprüngliche Punktanordnung oder den ursprünglichen Bestand beziehen.
Vorhandene externe Sidecar-Indizes werden weder gelesen noch erzeugt.
Bei EVLRs bleiben Rahmen und Nutzdaten erhalten und der Anfangsoffset
wird angepasst. Der Writer lädt grosse Nutzdaten nicht in den Heap und
interpretiert die Inhalte unbekannter Metadaten nicht.

`pointcloud info` zeigt die globale Headerkodierung, die Liste der VLRs und
EVLRs sowie direkt lesbare CRS-Angaben. Es liest EPSG-Codes aus den passenden
GeoTIFF-Schlüsseln und zeigt GeoTIFF-Zitate sowie vorhandenen WKT-Text. Die
Anzeige liest höchstens 65'535 Byte pro Projektionsrecord und kennzeichnet
gekürzten Text. [ASPRS LAS 1.4 R16](https://github.com/ASPRSorg/LAS/releases/tag/1.4.R16) ·
[OGC GeoTIFF 1.1](https://docs.ogc.org/is/19-008r4/19-008r4.html)

## Pipelines

Ein JSON-Array oder `{"pipeline":[...]}` beschreibt eine lineare Pipeline.
Dateinamen sind relativ zum aktuellen Arbeitsverzeichnis. Stages werden
einsbasiert nummeriert. Typen, Optionen und die Ausdruckssyntax werden
vor dem Punktlauf geprüft. Dimensionsnamen werden am geöffneten
Punktformat geprüft.

| Stage | Optionen |
| --- | --- |
| `readers.las` | `filename` für eine lokale LAS-/LAZ-Datei. Als erste Stage ist der Dateiname auch direkt zulässig. |
| `filters.crop` | Genau eine Geometrieoption. `bounds` als `([xmin,xmax],[ymin,ymax])` mit optionalem dritten Z-Intervall oder `polygon` als einzelnes 2D-WKT-Polygon. Grenzen sind inklusiv. `outside: true` invertiert die Auswahl. |
| `filters.expression` | `expression` enthält Dimensionsvergleiche mit endlichen Dezimalliteralen. Unterstützt sind `== != < <= > >=`, `!`, `&&`, `\|\|` und Klammern. Negation bindet vor UND, UND vor ODER. |
| `filters.decimation` | Ganzzahlige `step` (Standard 1), `offset` (Standard 0) und exklusives `limit` (optional). Zählung ab 0 über die Eingabe dieser Stage. |
| `filters.stats` | `dimensions` als kommaseparierte Liste der verfügbaren Dimensionen. |
| `writers.las` | `filename` endet auf `.las`, `forward: "all"`, `extra_dims: "all"` und `compression: false` (Standard). |
| `writers.null` | Keine Optionen. Konsumiert den gefilterten Strom. |

`polygon` verwendet die XY-Koordinaten im CRS der Eingabedatei.
JTS liest und validiert das Polygon beim Aufbau der Filterstage. Punkte
auf der Aussenkante und auf Lochrändern gelten als ausgewählt.
Punkte im Inneren eines Lochs gelten als ausserhalb. Das Polygon muss
gültig und nicht leer sein. Alle ausgewählten Rohrecords und übertragbaren
Metadaten bleiben in der LAS-Ausgabe erhalten.

Ausdrücke und Statistik akzeptieren `X`, `Y`, `Z`, `Intensity`,
`Classification`, `ReturnNumber`, `NumberOfReturns` sowie formatabhängig
`GpsTime`, `Red`, `Green`, `Blue`, `Infrared`. Statistik wird dort
erhoben, wo die Stage steht. Der laufende Mittelwert und das zweite
zentrale Moment folgen derselben stabilen Online-Formel wie PDAL.
Varianz und Standardabweichung sind Stichprobenwerte. Ein einziger
Punkt hat undefinierte Stichprobenstreuung. Die JSON-Ausgabe verwendet
`null` für undefinierte und nicht endliche Werte. NaN zählt als Probe,
kann die Extrema nicht ändern und macht die laufenden Momente undefiniert.
Bei leerer Auswahl sind alle fünf statistischen Werte `null`.

Im PDAL-Pipeline-Schema steht bei LAS 1.2 die alte Überlappungsklasse 12
als `Classification == 0` zur Verfügung. Der Batch-Zugriff
`classification()` liefert weiterhin den kodierten Klassenwert.
Die Ausgabedatei erhält das zugehörige Rohbyte einschliesslich der Flags.
[PDAL 2.10.2 LasReader](https://github.com/PDAL/PDAL/blob/2.10.2/io/LasReader.cpp)

`pointcloud pipeline` schreibt `pointsRead`, `pointsSelected`,
`pointsWritten` und `statistics` als JSON auf stdout beziehungsweise in
den mit `--metadata` angegebenen Pfad. Jede Statistikstage enthält
`stage` und `dimensions` mit `name`, `count`, `minimum`, `maximum`,
`average`, `variance` und `stddev`. Bei `writers.null` ist
`pointsWritten` gleich 0 und `pointsSelected` die Anzahl konsumierter
Punkte. `--laz-workers` wählt die maximale Zahl der LAZ-Decoder-Worker
(Standard 1). Im Parallelbetrieb bestimmt die Datei die Chunkgrösse.
`--batch-size` wählt die Batchgrösse für die serielle Verarbeitung.
`--overwrite` erlaubt das Ersetzen vorhandener Ziele.
`pointcloud --diagnostics ...` aktiviert Stacktraces.

Mit `--metadata` schreibt die CLI zuerst das JSON in eine temporäre Datei.
Sie veröffentlicht die Metadaten vor der LAS-Datei. Schlägt der LAS-Abschluss
fehl, entfernt sie die neuen Metadaten oder stellt die vorherige Datei wieder
her. Die Veröffentlichung erfolgt in zwei Dateisystemoperationen.

Im Bibliotheksaufruf verwendet `Pipeline.read(Path.of("examples/stats.json"))`
dieselbe Konfiguration. `pipeline.run(32_768, false)` liefert das
typisierte Ergebnis. Ein erneuter Aufruf erstellt frischen Stage-Zustand.

## Kompatibilität mit PDAL 2.10.2

- PointCloud Java akzeptiert in `filters.expression` die Literale `+2.1e1`
  und `.5`. PDAL 2.10.2 weist diese Schreibweisen zurück. Verwende `2.1e1`
  und `0.5` für Pipelines, die mit beiden Engines laufen sollen.
- Beim Format-8-Fixture schreibt PointCloud Java die ursprünglichen 40 Bytes
  pro Punkt unverändert. PDAL 2.10.2 schreibt mit `extra_dims: "all"` 42 Bytes
  pro Punkt, weil es `Infrared` zusätzlich zum NIR-Standardfeld als Extra
  Bytes ablegt. [Fixture-Erzeugung](provenance.md#kleine-fixtures)
- Die Realdatenabnahme vergleicht die vier mitgelieferten Pipelines und eine
  zusätzliche Pipeline für die 2014-Kachel mit PDAL im Streaming-Modus
  (`--stream`). Für Polygon-Ground-Crop prüft sie zusätzlich bytegleiche
  LAS- und JSON-Ausgaben mit einem und vier Java-Workern.
  [Referenzprüfung](../tools/check-real-data.py)
- Die Abnahme des Parallelpfads vergleicht Solothurn 2014 und Autzen
  in Dateireihenfolge mit seriellem Java und PDAL `readers.las`.
  Für berechnete LAS-1.4-Ausgabegrenzen toleriert dieser Vergleich
  höchstens eine Gleitkommaeinheit (ULP) Unterschied zu PDAL.
  Rohrecords, Quantisierung und Java-Ausgaben werden exakt geprüft.
  [Parallelvergleich](../tools/check-parallel-data.py)

[ASPRS LAS-Spezifikation](https://github.com/ASPRSorg/LAS) ·
[Herkunft und Fixtures](provenance.md)
