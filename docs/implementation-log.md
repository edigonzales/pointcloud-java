# Etappenprotokoll

Dieses Protokoll hält Etappenabnahmen nach [Abschnitt 8.4 des Auftrags](../codex-auftrag-pointcloud-java.md#84-verbindliche-dokumentation-und-testmetriken-je-etappe)
fest. Der Etappencommit ordnet dem Ergebnis die geprüften Quellen zu. Pro
Etappe erfassen wir Datum, Plattform, Toolchain, Eingaben, ausgeführte Befehle,
Ergebnisse, Fehlerkorrekturen und die Einfachheitsprüfung. Ein wiederverwendeter
Lauf wird mit Herkunft und unveränderten relevanten Eingaben ausgewiesen.

| Prüfung | Nachweis |
| --- | --- |
| Gradle und JUnit | Aufgabe und Selektion, ausgeführt, erfolgreich, fehlgeschlagen, Fehler und übersprungen, Laufzeit, Exitstatus, nativer Reportpfad |
| CLI und Integration | Aufruf, Soll und Ist, Exitstatus, Laufzeit und relevante Dateigrössen |
| LAS, LAZ und PDAL | Gelesene, ausgewählte, geschriebene und vollständig verglichene Records, Abweichungen, geprüfte Felder und Metadaten, Referenzversion und Decoder, Toleranzen |
| Leistung und Speicher | Anzahl Läufe, Wandzeit und Streuung, Punkte/s, Batch, Worker, Heap und maximales RSS soweit messbar, Runtime, Hardware und Cache-Zustand |
| AOT, Native und GPU | Erzeugtes Artefakt bzw. Gerät, Build, Start, Grösse bzw. Transfer und fachlicher Vergleich |
| Dokumentation | Konkret gelesene Verträge und geprüfte Verweise, Grund für nicht anwendbare Laufzeitmetriken |

## B0 · Repo-Regeln und Skills

23. November 2026, Darwin arm64. Ausgangspunkt `5e84742` auf `main`,
Index leer, einzige unversionierte Eingabe der vollständige Auftrag. Auf
`codex/pointcloud-engine` bleibt der Auftrag am ursprünglichen Pfad und wird
unverändert mitversioniert.

Auftrag vollständig gelesen. Die verlinkten Q16- und Q17-Dateien am jeweils
genannten Commit mit `curl -fsSL` gelesen. Aus GRETL stammen die Orientierung
am tatsächlichen Verbraucher und die direkte Vereinfachungsprüfung. Aus
INTERLIS stammen die direkte JUnit-Selektion, native Berichte und die
Wiederverwendung gültiger Läufe. Die Regeln für Reviews, Veröffentlichung und
Referenzsuites richten sich nach dem PointCloud-Auftrag.

Manuelle Abnahme anhand von Abschnitt 1.3 und der B0-Abnahme. Alle drei
Skill-Frontmatter aus `name` und `description`, Ordnernamen, Auslöser und
Verweise geprüft. Etappenfolge, autonome Ausführung, externe Reviews und
Testregeln sind konsistent. KISS, Ockham und das Verbot von Tests von Tests
sind explizit. Die vier Regeldateien und dieses Protokoll reichen für den
Bootstrap aus. `git diff --cached --check` prüfte den gestagten Stand auf
Whitespace-Fehler. Laufzeitmetriken sind in B0 nicht anwendbar, da diese
Etappe ausschliesslich Dokumentation anlegt. B0 abgenommen.
