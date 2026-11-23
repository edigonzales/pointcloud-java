---
name: pointcloud-performance
description: Untersuche Performanceänderungen und SIMD-, Speicher-, Parallelitäts-, JDK-, AOT-, Native- und GPU-Varianten reproduzierbar.
---

# Pointcloud-Performance untersuchen

1. Begrenze Hypothese, Baseline, Daten und Varianten vorab. Prüfe fachliche
   Gleichheit vor der Messung. Trenne Header und I/O, Dekodierung, Rechenkern
   sowie End-to-End. Prüfe einen vollständigen Pipeline-Lauf vor Übernahmen.
2. Miss ohne konkurrierende Builds, Tests oder Benchmarks. Halte Hardware,
   Runtime, Warm-up, Wiederholungen, Streuung, Speicher und Durchsatz fest.
   Berücksichtige Setup, Kopien und Ressourcenfreigabe im passenden Messumfang.
3. Begründe die Übernahme, isolierte Aufbewahrung oder Verwerfung anhand der
   Ergebnisse. Entferne entbehrlichen Versuchscode.

[Experimente und Messregeln](../../../codex-auftrag-pointcloud-java.md#9-performance-experimente-mit-entscheidungsregeln) ·
[Etappenprotokoll](../../../docs/implementation-log.md)
