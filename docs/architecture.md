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
| `filters.hag_dem` | `raster` für ein lokales GeoTIFF. Erzeugt `HeightAboveGround` für nachfolgende Ausdrücke, Statistiken und Raster. [Weitere Optionen](#höhe-über-grund) |
| `filters.stats` | `dimensions` als kommaseparierte Liste der verfügbaren Dimensionen. Optional `count: "Classification"`. |
| `writers.las` | `filename` endet auf `.las`, `forward: "all"`, `compression: false` (Standard). `extra_dims: "all"` reicht die Originalrecords durch. Für den Export von `HeightAboveGround` siehe [LAS-Export](#las-export-der-höhe-über-grund). |
| `writers.null` | Keine Optionen. Konsumiert den gefilterten Strom. |
| `writers.raster` | Float64-GeoTIFF. `filename` mit `.tif` oder `.tiff`, `binmode: true`, `resolution`, `origin_x`, `origin_y`, `width` und `height`. Optionen für Bandwahl, Dimension, Füllfenster, NoData und Kompression stehen unter [Rasterausgabe](#rasterausgabe). |
| `writers.dtm` | Float64-GeoTIFF aus einem Delaunay-Netz. `filename` mit `.tif` oder `.tiff`, `resolution`, `origin_x`, `origin_y`, `width` und `height`. Weitere Optionen unter [TIN-Terrainmodell](#tin-terrainmodell). |

`polygon` verwendet die XY-Koordinaten im CRS der Eingabedatei.
JTS liest und validiert das Polygon beim Aufbau der Filterstage. Punkte
auf der Aussenkante und auf Lochrändern gelten als ausgewählt.
Punkte im Inneren eines Lochs gelten als ausserhalb. Das Polygon muss
gültig und nicht leer sein. Alle ausgewählten Rohrecords und übertragbaren
Metadaten bleiben in der LAS-Ausgabe erhalten.

Ausdrücke und Statistik akzeptieren `X`, `Y`, `Z`, `Intensity`,
`Classification`, `ReturnNumber`, `NumberOfReturns`, `Synthetic`,
`KeyPoint`, `Withheld`, `Overlap` sowie formatabhängig
`GpsTime`, `Red`, `Green`, `Blue`, `Infrared`. Statistik wird dort
erhoben, wo die Stage steht. Der laufende Mittelwert und das zweite
zentrale Moment folgen derselben stabilen Online-Formel wie PDAL.
Varianz und Standardabweichung sind Stichprobenwerte. Ein einziger
Punkt hat undefinierte Stichprobenstreuung. Die JSON-Ausgabe verwendet
`null` für undefinierte und nicht endliche Werte. NaN zählt als Probe,
kann die Extrema nicht ändern und macht die laufenden Momente undefiniert.
Bei leerer Auswahl sind alle fünf statistischen Werte `null`.

Die vier Flags haben die Werte 0 und 1. In den Formaten 0–3
wird die alte Überlappungsklasse 12 als `Classification == 0` und
`Overlap == 1` ausgewertet. In den Formaten 6–8 wird Overlap aus
dem separaten Flag gelesen. `PointBatch.classification()` liefert den
kodierten Klassenwert. Die LAS-Ausgabe erhält die Originalrecords.
[PDAL 2.10.2 LasReader](https://github.com/PDAL/PDAL/blob/2.10.2/io/LasReader.cpp)

`pointcloud pipeline` schreibt `pointsRead`, `pointsSelected`,
`pointsWritten` und `statistics` als JSON auf stdout beziehungsweise in
den mit `--metadata` angegebenen Pfad. Jede Statistikstage enthält
`stage` und `dimensions` mit `name`, `count`, `minimum`, `maximum`,
`average`, `variance` und `stddev`. Mit `count: "Classification"`
enthält die Statistik für `Classification` zusätzlich `bins`, etwa
`{"0": 3, "2": 27}`. Die Klassenschlüssel stehen in numerischer
Reihenfolge. Die Häufigkeiten sind 64-Bit-Zähler. Eine leere Selektion
ergibt `{}`. Bei `writers.null` ist
`pointsWritten` gleich 0 und `pointsSelected` die Anzahl konsumierter
Punkte. `--laz-workers` wählt die maximale Zahl der LAZ-Decoder-Worker
(Standard 1). Im Parallelbetrieb bestimmt die Datei die Chunkgrösse.
`--batch-size` wählt die Batchgrösse für die serielle Verarbeitung.
`--overwrite` erlaubt das Ersetzen vorhandener Ziele.
`pointcloud --diagnostics ...` aktiviert Stacktraces.

Mit `--metadata` schreibt die CLI zuerst das JSON in eine temporäre Datei.
Sie veröffentlicht die Metadaten vor der LAS- oder GeoTIFF-Datei.
Schlägt der Abschluss der Ausgabe fehl, entfernt sie die neuen Metadaten
oder stellt die vorherige Datei wieder her. Die Veröffentlichung erfolgt in zwei Dateisystemoperationen.

Im Bibliotheksaufruf verwendet `Pipeline.read(Path.of("examples/stats.json"))`
dieselbe Konfiguration. `pipeline.run(32_768, false)` liefert das
typisierte Ergebnis. Ein erneuter Aufruf erstellt frischen Stage-Zustand.

### Rasterausgabe

`writers.raster` rastert ausgewählte Werte der gefilterten Punkte in ein explizites,
nordorientiertes XY-Gitter mit höchstens 1'000'000 Zellen. `resolution` ist
die Zellbreite in den Einheiten des horizontalen CRS. Der Ursprung ist
die linke untere Ecke. Linker und unterer Rand sind inklusive, rechter und
oberer Rand exklusiv. Innere Zellgrenzen gehören zur östlichen beziehungsweise
nördlichen Zelle. Auch eine leere Selektion schreibt das vollständige Gitter.

`output_type` wählt eine kommaseparierte Teilmenge der Bänder. Die gewählten
Bänder stehen im GeoTIFF in der Reihenfolge `min,max,mean,count,stdev`.
Standard sind `min,max,mean,count`. `stdev` gibt die
Populationsstandardabweichung der Zellwerte an. Bei einem einzigen Punkt
beträgt sie 0.

`dimension` wählt `Z` (Standard), den vorzeichenlosen LAS-Rohwert
`Intensity` oder `HeightAboveGround` nach `filters.hag_dem`.
Der Ausgabetyp ist `double` (Float64). `compression` akzeptiert
`none` (Standard) und das verlustfreie `deflate`.

`window_size` ist der maximale Abstand in Rasterzellen je Zeilen- oder
Spaltenrichtung. `0` ist der Standard. Bei einem positiven Wert erhält eine
leere Zelle einen gewichteten Mittelwert aus ursprünglich gemessenen Nachbarn,
separat für jedes ausgewählte Statistikband. Das Gewicht ist der Kehrwert
des grösseren Zeilen- oder Spaltenabstands. Bei Fenster 1 sind alle bis zu
acht Nachbarn gleich gewichtet. Die Suche endet am Gitterrand. Die Laufzeit
wächst mit der Zahl der leeren Zellen und der geprüften Fensterfläche.
`count` zählt die direkt eingerasterten Punkte, mit 0 in leeren oder
interpolierten Zellen.

`nodata` setzt die gemeinsame endliche GeoTIFF-Kennung (Standard `-9999`).
Zellen ohne Messung und ohne geeignete Nachbarn erhalten diese Kennung
in den gewählten Statistikbändern. Bei ausgewähltem `count` muss sie
ungleich 0 sein. Eine Übereinstimmung mit einem gültigen Mess- oder Füllwert
führt vor der Veröffentlichung zu einem Fehler.

GeoTools liest das horizontale CRS aus
LAS-WKT oder direkten EPSG-GeoKeys und schreibt ein GeoTIFF mit
`PixelIsArea`. Die Ausgabe übernimmt die XY-Koordinaten der Eingabe.
CRS-Angaben, die sich nicht auflösen lassen, ergeben einen Stagefehler.

`pointsSelected` zählt alle Punkte nach dem letzten Filter.
`pointsBinned` zählt die davon ins Gitter gefallenen Punkte.
`pointsWritten` bleibt bei einer Rasterausgabe 0. Die CLI veröffentlicht
das GeoTIFF nach verifiziertem Dateiende. Zur JVM-Distribution gehören
GeoTools und die [zugehörigen Lizenzhinweise](../licenses/geotools/NOTICE.md).

Das [Solothurner Kronenhöhenmodell](../examples/chm-solothurn-forest.json)
rastert ausgewählte Vegetationspunkte ab 5 m Höhe über Grund.
`output_type: "max,count"` liefert die grösste gemessene relative Höhe
und die Zahl der Stützpunkte pro 0,5-m-Zelle. Unbelegte Zellen erhalten
NoData im Höhenband und 0 im Zählband.

Mit `height_thresholds` hängt `writers.raster` für jede Schwelle ein
Float64-Band `fraction_above_<Schwelle>` an die regulären Bänder an.
Die Option akzeptiert eine bis acht endliche, strikt aufsteigende
Zahlen in der Z-Einheit der Eingabe. Sie setzt `dimension: "HeightAboveGround"`,
ein vorgängiges `filters.hag_dem` und ein `count`-Band voraus. HAG muss
für jedes verarbeitete Echo vorliegen. `nodata_hag` ist in diesem Modus
unzulässig, da ein endlicher Ersatzwert als Höhe in die Zählung eingehen würde.

Der Anteil von 0 bis 1 entspricht der Zahl der Echos **strikt über** der
Schwelle geteilt durch `count`. Der Nenner zählt alle nach den Filtern
in die Zelle fallenden Echos. Eine Zelle mit Echos bis zur Schwellenhöhe
hat Anteil 0. Ohne Echos steht im Zählband 0 und in den Anteilsbändern
NoData. Ein Füllfenster interpoliert nur die regulären Statistikbänder.
Das [Riedholzer Beispiel](../examples/height-thresholds-riedholz.json)
verwendet Erstreflexionen der Boden- und Vegetationsklassen 2–5 und
Schwellen von 0,5 m, 2 m und 5 m.

Für bestehende Java-Pipelines mit `"type": "writers.gdal"` reicht es,
den Typ der letzten Stage auf `"type": "writers.raster"` zu setzen.
Die Stage-Optionen sind direkt übertragbar.

## TIN-Terrainmodell

`writers.dtm` interpoliert Z linear in der Mitte jeder Zelle. Die
Zellgeometrie ist nordorientiert (`PixelIsArea`), mit Ursprung an der
linken unteren Ecke. Das GeoTIFF enthält das Float64-Band `elevation`
und das projizierte horizontale CRS der LAS-Datei. Die Z-Werte behalten
Einheit und Höhenbezug der Eingabe. Für Bodenmodelle wählt ein
vorgelagerter Filter die Bodenpunkte. Ein Rand um das Zielgitter kann
zusätzliche Stützpunkte beisteuern.

Die erste ausgewählte Höhe je rohem LAS-XY gewinnt. Bei gleichen positiven
X- und Y-Skalen und höchstens 35'000 Rohkoordinateneinheiten Spannweite pro
Achse baut `writers.dtm` das Delaunay-Netz mit Delaunator auf. Massgebend
sind die ausgewählten, deduplizierten Stützpunkte. In den übrigen Fällen
übernimmt SemiVirtual-Tinfour 2.1.9. Beide Wege verwenden dieselbe
Rasterisierung und dieselbe Ersatzregel für unbelegte Zellen. Die Höhen
werden separat als Double geführt.
Bis zu 1'000'000 ausgewählte Punkte vor der Deduplizierung und 1'000'000
Rasterzellen sind zulässig. Das Dreiecksnetz benötigt mindestens drei
unterschiedliche, nicht kollineare Stützpunkte.

`max_triangle_edge_length` begrenzt optional alle drei XY-Kanten eines
Dreiecks in den horizontalen CRS-Einheiten. Das Limit ist positiv und
inklusiv. Ist die Option weggelassen, kann das Netz auch weit auseinander
liegende Bodenpunkte verbinden. Ausserhalb der Hülle und bei verworfenen
Dreiecken bleibt `nodata` (endlicher Wert, Standard `-9999`). Ein
gültiger interpolierter Wert, der mit der NoData-Kennung kollidiert,
führt vor Veröffentlichung zu einem Fehler. `compression` akzeptiert
`none` (Standard) und `deflate`.

Mit `fill_nodata: "nearest"` erhält jede unbelegte Zelle den Z-Wert
des nächstgelegenen ausgewählten Stützpunkts. Die Distanz wird in XY
ab der Zellmitte gemessen. Bei Gleichstand gewinnt der erste Punkt
in Dateireihenfolge. Das gilt ausserhalb der konvexen Hülle und
bei durch das Kantenlimit verworfenen Dreiecken. Bereits interpolierte
Höhen bleiben erhalten. Der räumliche Index entsteht erst bei
der ersten unbelegten Zelle nach Freigabe der Dreiecke. Mit einem,
zwei oder nur kollinearen Stützpunkten füllt die Suche das gesamte
Raster. Bei leerer Selektion meldet der Writer einen Fehler.

Im vollständigen Modus enthält jede veröffentlichte Zelle eine
endliche Höhe. Das GeoTIFF hat keinen NoData-Tag. Ein konfigurierter
`nodata`-Wert bleibt in diesem Modus ohne Auswirkung auf die Ausgabe.

Die JSON-Metadaten zählen unter `dtm` die eindeutigen Stützpunkte
(`supportPoints`) und die gültigen beziehungsweise unbelegten Zellen
(`cellsCovered` und `cellsNoData`). Im vollständigen Modus zählt
`cellsFilledNearest` die ergänzten Zellen und `cellsNoData` ist 0.
`pointsSelected` zählt alle
selektierten Punkte vor der Deduplizierung, `pointsWritten` ist 0.

## Höhe über Grund

`filters.hag_dem` berechnet `HeightAboveGround = Z - DEM(X,Y)` als
Double. Pro Pipeline ist eine HAG-Stage zulässig. Nachfolgende
`filters.expression` und `filters.stats` können auf die Dimension
zugreifen. Die LAS-Ausgabe enthält die ausgewählten Originalrecords
mit ihren bisherigen Dimensionen und Extra Bytes.

Das lokale, nordorientierte GeoTIFF wird in Originalauflösung ausgelesen.
`band` zählt ab 1 und hat den Standardwert 1. Das ausgewählte Band darf
bis zu 4 Millionen Zellen enthalten. Der Filter verwendet den Wert der
Zelle am Punkt-XY. West und Nord sind inklusive, Ost und Süd exklusiv.
Die Daten müssen horizontal dasselbe CRS und dieselbe Achsenreihenfolge
haben. Der Höhenbezug und die Z-Einheit müssen ebenfalls übereinstimmen.
Deklarierte Konflikte meldet die Stage als Fehler. Wenn Quellen den
Höhenbezug offenlassen, stellt der Aufrufer dessen Übereinstimmung sicher.

Deklarieren LAS und DEM horizontal EPSG:2056, prüft die Stage beide
Definitionen beim Öffnen gegen das kanonische LV95 aus GeoTools. Nur
die Breite und Länge des Projektionszentrums dürfen um bis zu acht
Double-Rundungseinheiten (ULP) abweichen. So funktioniert das gerundete
PDAL-WKT auch mit dem amtlichen Solothurner DTM. Achsen, Einheiten,
Basis-CRS, Datum und die übrigen Projektionsparameter müssen
übereinstimmen. Deklarierte EPSG-Kennungen von Basis-CRS und Datum sowie
explizite `TOWGS84`-Parameter müssen zur kanonischen Definition passen.
Die vertikale CRS-Prüfung folgt separat. Die ungeänderten Punkt-XY gehen
direkt in die Rasterabfrage ein.

- `zero_ground` hat Standardwert `true` und setzt Bodenpunkte vor der
  Rasterabfrage auf 0. `class` bestimmt die Bodenklasse, Standardwert 2.
- `min_clamp` und `max_clamp` begrenzen gültige Differenzen optional.
  Ohne Grenzen bleiben vorzeichenbehaftete Höhen erhalten.
- Ein endlicher `nodata_hag` ist der Ersatzwert für NoData und fehlende
  Rasterabdeckung. Ohne ihn liefert die Stage dort einen Fehler.

Der [Solothurn-Workflow](../examples/hag-dem-solothurn-urban.json)
verwendet das vollständige Stadt-DTM. Er wählt Vegetationspunkte über
5 m und exportiert deren Originalrecords mit zusätzlicher Höhe über Grund.

### LAS-Export der Höhe über Grund

`extra_dims: "HeightAboveGround=double"` aktiviert den Export nach
`filters.hag_dem`. `HeightAboveGround=float64` ist ein Alias. Jeder
ausgewählte Record erhält acht Little-Endian-Bytes hinter seinen
Originalbytes. Der Writer übernimmt LAS-Version, Punktformat,
Quantisierung und Reihenfolge. Er ergänzt den Double-Deskriptor vom
Typ 10 im VLR `LASF_Spec/4`. Vorhandene Deskriptoren werden übernommen.
Unbeschriebene Originalbytes erhalten vor dem neuen Attribut
Typ-0-Deskriptoren mit je höchstens 255 Bytes.

Für den Export ist höchstens ein vorhandener Extra-Bytes-VLR zulässig.
Ein gleichnamiges Attribut, ein Extra-Bytes-EVLR, ungültige Deskriptoren
und Überschreitungen der LAS-Feldgrenzen führen zu einem Writerfehler.
`extra_dims: "all"` übernimmt auch beim Wiedereinlesen die gespeicherten
HAG-Bytes. Für Ausdrücke und Statistik berechnet `filters.hag_dem` die
Dimension weiterhin aus dem angegebenen DEM.

## Kompatibilität mit PDAL 2.10.2

- Für einen Vergleich von HAG mit vorzeichenbehafteten Höhen setzt PDAL
  eine explizite negative `min_clamp`-Grenze. Java meldet fehlende
  DEM-Werte standardmässig als Fehler. Bei getaggtem NoData gilt die
  analytische Java-Referenz, da PDAL 2.10.2 in unserer GeoTIFF-Probe
  den NoData-Wert als Höhe verrechnet hat.
  [PDAL HAG-Implementierung](https://github.com/PDAL/PDAL/blob/2.10.2/filters/HagDemFilter.cpp)
- PDAL erkennt archiviertes HAG in LAS 1.4 direkt über den Extra-Bytes-VLR.
  Bei LAS 1.2 setzt der Reader `use_eb_vlr: true`. Damit verwendet er
  die Feldreihenfolge des VLR auch bei zusätzlichen Originalattributen.
  Eine explizite Reader-Liste in `extra_dims` ersetzt die Deskriptoren.
  Die Realdatenprüfung startet mit `tools/check-hag-dem.py --las-extra-bytes`.
  [PDAL LAS-Reader](https://pdal.io/en/stable/stages/readers.las.html#options) ·
  [HAG-Abnahme](../tools/check-hag-dem.py)
- `tools/check-hag-dem.py <pdal> --lv95-wkt` prüft das von PDAL erzeugte
  LV95-WKT mit LAS 1.4 im Punktformat 8 und dem amtlichen Solothurner
  DTM. PDAL liest `Confidence` und den exportierten Float64-HAG-Wert mit
  den Standardeinstellungen zurück. [HAG-Abnahme](../tools/check-hag-dem.py)
- Das Gegenstück zu `writers.dtm` ist die PDAL-Stagefolge
  `filters.delaunay`, `filters.faceraster`, `writers.raster`.
  Kozyklische Stützpunkte können verschiedene Delaunay-Diagonalen und daher
  verschiedene interpolierte Höhen ergeben. Java führt die XY-Geometrie
  lokal auf Basis der LAS-Rohkoordinaten. PDAL trianguliert die skalierten
  Weltkoordinaten. Für doppelte XY dokumentiert Java die Auswahl in
  Dateireihenfolge. [TIN-Realdatenabnahme](../tools/check-dtm.py)
- Der GeoTIFF-Writer heisst in PointCloud Java `writers.raster`. Für den
  PDAL-Referenzlauf setzt das Abnahmeskript den Typ auf `writers.gdal`.
  Deflate entspricht beim Referenzlauf `gdalopts: "COMPRESS=DEFLATE"`.
  Gemeinsame Pipelines setzen `output_type` und `binmode` explizit.
  [Rasterabnahme](../tools/check-ground-grid.py)
- PointCloud Java akzeptiert in `filters.expression` die Literale `+2.1e1`
  und `.5`. PDAL 2.10.2 weist diese Schreibweisen zurück. Verwende `2.1e1`
  und `0.5` für Pipelines, die mit beiden Engines laufen sollen.
- Beim Format-8-Fixture schreibt PointCloud Java die ursprünglichen 40 Bytes
  pro Punkt unverändert. PDAL 2.10.2 schreibt mit `extra_dims: "all"` 42 Bytes
  pro Punkt, weil es `Infrared` zusätzlich zum NIR-Standardfeld als Extra
  Bytes ablegt. [Fixture-Erzeugung](provenance.md#kleine-fixtures)
- `count: "Classification"` greift wie bei PDAL nur, wenn
  `Classification` in `dimensions` steht. Java gibt Klassenschlüssel als
  ganze Zahlen im JSON-Objekt `bins` aus. PDAL 2.10.2 verwendet
  Dezimaldarstellung wie `"2.000000"` und bietet zusätzlich `counts`.
  [PDAL-Statistikausgabe](https://github.com/PDAL/PDAL/blob/2.10.2/filters/StatsFilter.cpp#L1080-L1133)
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
