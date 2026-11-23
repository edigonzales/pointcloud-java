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

[ASPRS LAS-Spezifikation](https://github.com/ASPRSorg/LAS) ·
[Herkunft und Fixtures](provenance.md)
