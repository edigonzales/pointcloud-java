# Blindflächen für den Prominenzversuch

Zwei 100 × 100 m grosse Kerne in LV95 aus der Befliegung Solothurn 2019.
Zwei getrennte Codex-Durchgänge markierten sichtbare Spitzen vor dem
ersten Detektionslauf anhand der LiDAR-Punkte, Orthofotos und Profile
der Originalrückläufe. Die Texte unter `annotations/` enthalten die
beiden Markierungen in Metern östlich und nördlich der Südwestecke.
Der Abgleich maximiert die Zahl eindeutiger Paare innerhalb von 2,5 m
und minimiert dann deren summierten Abstand. Je Paar wurde der höchste
Originalrücklauf ab 5 m HAG gewählt, der innerhalb von 1,5 m um beide
Markierungen liegt.

| Kern | Südwestecke LV95 | Mediane Neigung | Markierungen A / B | Paare | Referenzspitzen | Ausgeschlossen |
|---|---:|---:|---:|---:|---:|---:|
| blind-steep | 2’592’110 / 1’228’360 | 31,8° | 151 / 150 | 87 | 80 | 1’545,70 m² |
| blind-mild | 2’594’242 / 1’227’657 | 12,9° | 112 / 87 | 75 | 66 | 898,74 m² |

`exclusions.json` enthält halboffene Rechtecke `[west, süd, ost, nord]`
für visuell mehrdeutige Bereiche und einen 1,5-m-Rand um ungepaarte
Markierungen. Die Flächenangabe zählt überlappende Rechtecke einmal.
Die CSVs enthalten die konsensuell markierten LAS-Spitzen ausserhalb
dieser Bereiche. Der Abgleich und die Ausschlussflächen wurden vor
der Detektion eingefroren. Die Referenz umfasst Spitzen der oberen
Kronenschicht, die in beiden visuellen Beurteilungen erkennbar waren.

[LAZ-Datenquellen](../../docs/provenance.md) ·
[Orthofoto Waldkachel](https://data.geo.admin.ch/api/stac/v1/collections/ch.swisstopo.swissimage-dop10/items/swissimage-dop10_2018_2594-1227) ·
[Orthofoto Höhenkachel](https://data.geo.admin.ch/api/stac/v1/collections/ch.swisstopo.swissimage-dop10/items/swissimage-dop10_2018_2592-1228)
