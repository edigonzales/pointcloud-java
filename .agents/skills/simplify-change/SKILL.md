---
name: simplify-change
description: Prüfe neue Abstraktionen, grössere Strukturänderungen und den Diff vor jeder Etappenabnahme auf unnötige Komplexität.
---

# Änderungen vereinfachen

1. Benenne den aktuellen Verbraucher und die benötigte Funktion oder Grenze.
   Lies betroffenen Code und Aufrufer. Vergleiche mit der einfachsten direkten
   Lösung, die den Vertrag erfüllt.
2. Entferne unnötige Wrapper, Factories, Doppelmodelle und Kompatibilitätspfade
   samt ihren Aufrufern. Nutze vorhandene Java-, Gradle- und JUnit-Funktionen
   anstelle eigener Build-, Prüf- oder Testmechanismen.
3. Erhalte nötige Produktverträge und echte Regressionstests. Halte bei
   grösseren Entwürfen den konkreten Grund für verbleibende Struktur fest.

[Entwurfsregeln](../../../codex-auftrag-pointcloud-java.md#14-einfachheit-ist-ein-abnahmekriterium) ·
[Etappenprotokoll](../../../docs/implementation-log.md)
