#!/usr/bin/env python3
"""
Analiza un CSV exportado por la app OpenView (formato v1 o v2) y resume:

  1. Estadística por baliza: muestras nuevas por segundo, RSSI mediano, desviación.
  2. Ventanas "estoy en la baliza X" (de las marcas ARRIVE/DEPART del modo prueba guiado,
     de un truth.json, o deducidas de notas tipo "en 6" buscando el pico de RSSI).
  3. Por ventana: RSSI mediano de la baliza propia (¿son comparables las unidades?),
     acierto del nodo detectado por la app y cambios de nodo.
  4. Aviso si las balizas difieren > 5 dB entre sí (diferencia de hardware/potencia).

Solo usa la biblioteca estándar. Ejemplos:

    python tools/analyze_log.py prueba.csv
    python tools/analyze_log.py prueba.csv --truth truth.json
    python tools/analyze_log.py prueba.csv --nodes "NAV_BLE_06=node_1790200467446,NAV_BLE_05=node_1790200806256"

truth.json: [{"beacon": "NAV_BLE_06", "start": 3.0, "end": 5.5}, ...]
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
import statistics as st
import sys
from collections import Counter, defaultdict


def read_rows(path: str) -> list[dict]:
    with open(path, encoding="utf-8-sig", newline="") as fh:
        lines = [ln for ln in fh.read().splitlines() if ln.strip() and not ln.startswith("sep=")]
    rows = list(csv.DictReader(io.StringIO("\n".join(lines))))
    v2 = bool(rows) and "event" in rows[0]
    out = []
    for r in rows:
        def f(k):
            v = (r.get(k) or "").strip()
            try:
                return float(v) if v != "" else None
            except ValueError:
                return None
        out.append({
            "t": f("timestamp_s"), "beacon": (r.get("beacon_name") or "").strip(),
            "raw": f("rssi_raw"), "node": (r.get("detected_node_id") or "").strip(),
            "note": (r.get("note") or "").strip(), "event": (r.get("event") or "").strip() if v2 else "",
            "true_node": (r.get("true_node") or "").strip() if v2 else "",
        })
    return out


def new_samples(rows: list[dict]) -> dict[str, list[tuple[float, float]]]:
    """(t, rssi) por baliza. En v1 solo cuentan los cambios de valor (el resto son repeticiones)."""
    v2 = any(r["event"] for r in rows)
    per: dict[str, list[tuple[float, float]]] = defaultdict(list)
    last: dict[str, float] = {}
    for r in rows:
        if not r["beacon"].startswith("NAV_") or r["raw"] is None or r["t"] is None:
            continue
        if v2 or last.get(r["beacon"]) != r["raw"]:
            per[r["beacon"]].append((r["t"], r["raw"]))
        last[r["beacon"]] = r["raw"]
    return per


def windows_from_marks(rows: list[dict], beacon_of_node: dict[str, str]) -> list[dict]:
    marks = sorted((r for r in rows if r["event"] in ("ARRIVE", "DEPART")), key=lambda r: r["t"])
    wins = []
    for i, m in enumerate(marks):
        if m["event"] != "ARRIVE":
            continue
        end = next((x["t"] for x in marks[i + 1:] if x["event"] == "DEPART" and x["true_node"] == m["true_node"]), None)
        end = end if end is not None else max(r["t"] for r in rows if r["t"] is not None)
        wins.append({"beacon": beacon_of_node.get(m["true_node"], m["true_node"]), "node": m["true_node"], "start": m["t"], "end": end})
    return wins


def windows_from_notes(rows: list[dict], samples: dict[str, list[tuple[float, float]]]) -> list[dict]:
    """Para logs v1 con notas 'en N': busca el pico de RSSI de esa baliza en los 15 s previos a la nota."""
    wins = []
    for r in rows:
        m = re.fullmatch(r"\s*en\s+(\d+)\s*", r["note"], re.I) if r["beacon"] == "_NOTE_" else None
        if not m:
            continue
        beacon = f"NAV_BLE_{int(m.group(1)):02d}"
        pts = [(t, v) for t, v in samples.get(beacon, []) if r["t"] - 15 <= t <= r["t"] + 2]
        if len(pts) < 5:
            continue
        bins: dict[int, list[float]] = defaultdict(list)
        for t, v in pts:
            bins[int(t)].append(v)
        means = {b: st.mean(v) for b, v in bins.items()}
        peak_bin = max(means, key=means.get)
        good = sorted(b for b, mu in means.items() if mu >= means[peak_bin] - 4)
        # tramo contiguo alrededor del pico
        lo = hi = peak_bin
        while lo - 1 in good:
            lo -= 1
        while hi + 1 in good:
            hi += 1
        wins.append({"beacon": beacon, "start": float(lo), "end": float(hi + 1), "note_t": r["t"], "peak": means[peak_bin]})
    return wins


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv")
    ap.add_argument("--truth", help="JSON con ventanas [{beacon,start,end}]")
    ap.add_argument("--nodes", help="Mapa baliza=nodo separado por comas (para medir acierto del nodo detectado)")
    a = ap.parse_args()

    rows = read_rows(a.csv)
    if not rows:
        print("CSV vacío o ilegible", file=sys.stderr)
        return 1
    dur = max((r["t"] for r in rows if r["t"] is not None), default=0)
    samples = new_samples(rows)
    node_of = dict(p.split("=", 1) for p in a.nodes.split(",")) if a.nodes else {}
    if not node_of:  # sin mapa explícito: se infiere de true_node en las marcas (v2)
        for r in rows:
            pass
    beacon_of_node = {v: k for k, v in node_of.items()}

    print(f"Archivo: {a.csv}\nDuración: {dur:.0f} s · formato {'v2' if any(r['event'] for r in rows) else 'v1'}\n")
    print("== Por baliza")
    print(f"{'baliza':12} {'muestras':>8} {'/s':>6} {'mediana':>8} {'sd':>5} {'min':>5} {'max':>5}")
    for b in sorted(samples):
        v = [x for _, x in samples[b]]
        print(f"{b:12} {len(v):8d} {len(v)/max(dur,1):6.1f} {st.median(v):8.0f} {st.pstdev(v):5.1f} {min(v):5.0f} {max(v):5.0f}")
    missing = [f"NAV_BLE_0{i}" for i in range(1, 7) if f"NAV_BLE_0{i}" not in samples]
    if missing:
        print(f"\nBalizas NAV_BLE_01..06 que NO aparecen en el log: {', '.join(missing)}")

    if a.truth:
        wins = json.load(open(a.truth, encoding="utf-8"))
        src = "truth.json"
    else:
        wins = windows_from_marks(rows, beacon_of_node)
        src = "marcas ARRIVE/DEPART"
        if not wins:
            wins = windows_from_notes(rows, samples)
            src = "notas 'en N' alineadas al pico de RSSI (aprox.)"
    if not wins:
        print("\nNo hay ventanas de verdad de terreno (ni marcas, ni truth.json, ni notas 'en N').")
        return 0

    print(f"\n== Ventanas ({src})")
    own = {}
    for w in wins:
        v = [x for t, x in samples.get(w["beacon"], []) if w["start"] <= t <= w["end"]]
        line = f"{w['beacon']:12} {w['start']:6.1f}-{w['end']:6.1f} s"
        if v:
            own[w["beacon"]] = st.median(v)
            line += f" · RSSI propio mediano {st.median(v):5.0f} dBm (sd {st.pstdev(v):.1f}, n={len(v)})"
        if "note_t" in w:
            line += f" · nota puesta en {w['note_t']:.1f} s (retraso {w['note_t'] - w['end']:+.1f} s)"
        if w["beacon"] in node_of:
            det = [r["node"] for r in rows if r["beacon"].startswith("NAV_") and r["t"] is not None and w["start"] <= r["t"] <= w["end"] and r["node"]]
            if det:
                ok = sum(1 for n in det if n == node_of[w["beacon"]]) / len(det)
                flips = sum(1 for x, y in zip(det, det[1:]) if x != y)
                line += f" · acierto nodo {ok*100:.0f} % · cambios {flips} · confunde con {Counter(n for n in det if n != node_of[w['beacon']]).most_common(1)}"
        print(line)

    if len(own) >= 2:
        spread = max(own.values()) - min(own.values())
        print(f"\n== Consistencia entre balizas: diferencia máxima {spread:.0f} dB entre unidades a la misma distancia")
        if spread > 5:
            hi = max(own, key=own.get); lo = min(own, key=own.get)
            print(f"   ⚠ > 5 dB: {hi} suena {spread:.0f} dB más que {lo}. Revisa firmware/potencia real (Monitor Serie), "
                  f"orientación de la antena y alimentación; si persiste, compénsalo con calibración por baliza.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
