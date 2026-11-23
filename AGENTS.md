# Repository-Anweisungen

PointCloud Java ist eine Java-25-Bibliothek und CLI für lokale LAS-/LAZ-Eingaben,
LAS-Ausgabe und lineare Pipelines aus einer definierten PDAL-Teilmenge. Der
[Implementierungsauftrag](codex-auftrag-pointcloud-java.md) ist verbindlich.
Reprojektion, räumliche Indexabfragen, Plugin-Graphen und ein neuer LAZ-Encoder
liegen ausserhalb des Produktumfangs.

- Prüfe Branch, Index und Arbeitsbaum und bewahre fremde Änderungen. Arbeite
  B0 → P0 → … → P11 autonom ab. Schliesse jede Etappe vollständig ab, prüfe,
  dokumentiere und committe sie, bevor du die nächste beginnst. Behebe
  fehlgeschlagene Pflichtprüfungen in der laufenden Etappe. Bei einer objektiv
  unauflösbaren Voraussetzung dokumentiere den Blocker und halte an. Push,
  Merge, Release und externe Code-Reviews gehören nicht zum Auftrag. Die
  unabhängigen PDAL-Produkttests bleiben verpflichtend.
- Verwende Java 25, Gradle Groovy DSL und lokale Codecquellen im Produkt. Der
  CPU-Standardbuild braucht keine experimentellen JVM-Module, nativen
  LiDAR-Bibliotheken, GPU oder Realdatendownloads. Verwende
  `./gradlew clean check` für die Standardtests und `./gradlew installDist`
  für die installierbare CLI. Realdaten beziehen wir separat mit
  `./gradlew downloadTestData`.
- Wende KISS und Ockham's Razor an. Suche vorhandenen Code und bevorzuge
  direkte Lösungen und Standardwerkzeuge. Jede neue Klasse, Schnittstelle,
  Modulgrenze, Abhängigkeit und Buildaufgabe braucht einen heutigen Verbraucher
  oder eine konkret notwendige Grenze.
- Erhalte Rohwerte und Double-Präzision, Punktreihenfolge, Attribute und
  übertragbare Metadaten. Fehler müssen bis zum Aufrufer gelangen und dürfen
  keine Teilausgabe veröffentlichen. Schliesse Ressourcen zuverlässig,
  begrenze den Speicher und beachte das Datenbudget des Auftrags.
- Prüfe tatsächliche Produktergebnisse und Fehler mit lokalen Fixtures und
  den vorgesehenen Referenzläufen. Verwende JUnit-Discovery, Gradle-Selektion
  und native Berichte. Keine Tests von Tests, eigene Test-/Gateverwaltung
  oder Schwächung von Assertions. Verwende gültige Prüfungen erneut, wenn
  alle relevanten Eingaben unverändert sind. Halte tatsächliche Ergebnisse,
  Fehlerbehebungen und Metriken nach Abschnitt 8.4 im
  [Etappenprotokoll](docs/implementation-log.md) fest.

Lies und nutze die Repository-Skills ab P0 bei ihrem jeweiligen Auslöser.

- [simplify-change](.agents/skills/simplify-change/SKILL.md) vor neuen
  Abstraktionen, grösseren Strukturänderungen und jeder Etappenabnahme.
- [pointcloud-correctness](.agents/skills/pointcloud-correctness/SKILL.md)
  bei Codec, LAS-/LAZ-I/O, Batchlayout, Pipeline-Semantik, Writer und Fehlerpfaden.
- [pointcloud-performance](.agents/skills/pointcloud-performance/SKILL.md)
  bei Performanceänderungen und SIMD-, Speicher-, Parallelitäts-, JDK-, AOT-,
  Native- und GPU-Versuchen.
