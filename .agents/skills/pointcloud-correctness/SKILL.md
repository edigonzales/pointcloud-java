---
name: pointcloud-correctness
description: Sichere Produktverträge bei Änderungen an Codec, LAS-/LAZ-I/O, Batchlayout, Pipeline-Semantik, Writer oder Fehlerpfaden.
---

# Pointcloud-Korrektheit prüfen

1. Bestimme den betroffenen Vertrag und wähle die kleinste geeignete Fixture.
   Prüfe rohe XYZ und skalierte Double-Koordinaten getrennt. Berücksichtige
   Unsigned-Felder, Flags, Extra Bytes und übertragbare VLRs und EVLRs.
2. Prüfe bei Auswahlfiltern Punktreihenfolge und unveränderte Records. Prüfe
   Batchübergänge, EOF gegenüber Dekodierfehler, Ressourcenschluss und das
   Verhalten einer begonnenen Ausgabe bei Abbruch.
3. Führe an den vorgesehenen Abnahmepunkten den unabhängigen Referenzvergleich
   aus. Halte tatsächlich verglichene Werte, Abweichungen und Formatgrenzen fest.

[Dateiverträge und Abnahme](../../../codex-auftrag-pointcloud-java.md#7-korrektheit-und-dateierhaltung) ·
[Etappenprotokoll](../../../docs/implementation-log.md)
