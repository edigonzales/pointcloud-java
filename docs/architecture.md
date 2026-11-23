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
Layerdaten vorhalten. Eine ausdrücklich fehlende Tabelle für feste Chunks
ist sequenziell lesbar, wenn die Recordanzahl vollständig erreicht wird.
Beliebige formal dekodierbare Bitfehler sind mit diesem Format strukturell
oft nicht erkennbar.

`LasWriter` übernimmt ausgewählte Records in der vom Aufrufer übergebenen
Reihenfolge und quantisiert sie nicht erneut. Version, Format und Scale/
Offset folgen der Quelle. Anzahl, Return-Zähler und Bounds werden aus
der Auswahl berechnet. In Format 6–8 sind die Legacy-Zähler null. Für eine
leere Ausgabe werden Null-Bounds geschrieben. `finish()` benötigt den
verifizierten Endzustand des Readers. Die vollständige Datei wird im
Zielverzeichnis temporär geschrieben und nach dem Headerabschluss
veröffentlicht. Bestehende Ziele erfordern explizites Überschreiben,
Aliase der Eingabe werden abgelehnt.

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

## Pipelines

Ein JSON-Array oder `{"pipeline":[...]}` beschreibt eine lineare Pipeline.
Dateinamen sind relativ zum aktuellen Arbeitsverzeichnis. Stages werden
einsbasiert nummeriert. Typen, Optionen und die Ausdruckssyntax werden
vor dem Punktlauf geprüft. Dimensionsnamen werden am geöffneten
Punktformat geprüft.

| Stage | Optionen |
| --- | --- |
| `readers.las` | `filename` für eine lokale LAS-/LAZ-Datei. Als erste Stage ist der Dateiname auch direkt zulässig. |
| `filters.crop` | `bounds` als `([xmin,xmax],[ymin,ymax])` mit optionalem dritten Z-Intervall. Grenzen sind inklusiv. `outside: true` invertiert die Auswahl. |
| `filters.expression` | `expression` enthält Dimensionsvergleiche mit endlichen Dezimalliteralen. Unterstützt sind `== != < <= > >=`, `!`, `&&`, `\|\|` und Klammern. Negation bindet vor UND, UND vor ODER. |
| `filters.decimation` | Ganzzahlige `step` (Standard 1), `offset` (Standard 0) und exklusives `limit` (optional). Zählung ab 0 über die Eingabe dieser Stage. |
| `filters.stats` | `dimensions` als kommaseparierte Liste der verfügbaren Dimensionen. |
| `writers.las` | `filename` endet auf `.las`, `forward: "all"`, `extra_dims: "all"` und `compression: false` (Standard). |
| `writers.null` | Keine Optionen. Konsumiert den gefilterten Strom. |

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
Punkte. `--batch-size` wählt die Batchgrösse und `--overwrite` erlaubt
das Ersetzen vorhandener Ziele. `pointcloud --diagnostics ...` aktiviert
Stacktraces.

Im Bibliotheksaufruf verwendet `Pipeline.read(Path.of("examples/stats.json"))`
dieselbe Konfiguration. `pipeline.run(32_768, false)` liefert das
typisierte Ergebnis. Ein erneuter Aufruf erstellt frischen Stage-Zustand.

[ASPRS LAS-Spezifikation](https://github.com/ASPRSorg/LAS) ·
[Herkunft und Fixtures](provenance.md)
