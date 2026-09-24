#!/usr/bin/env python3
"""
Analiza el experimento de ROTACIÓN de posiciones: varias vueltas de la prueba guiada v3 (CSV v2 con fila
LAYOUT) en las que las mismas placas se colocan en posiciones distintas. Separa

    efecto de la PLACA (unidad)   frente a   efecto de la POSICIÓN (muebles, pared, alimentación)

con un modelo aditivo ajustado por mínimos cuadrados:

    RSSI propio  (baliza b, parada en su propia posición p)   = μ + placa[b] + posición[p]
    RSSI ajeno   (baliza b oída desde la posición p)          = μ + placa[b] + oyente[p] + β·log10(distancia)

Uso:
    python tools/analyze_rotation.py "logs de prueba/rot_*.csv"
    python tools/analyze_rotation.py a.csv b.csv c.csv --json resultado.json

Requiere numpy. Cada CSV debe tener una fila LAYOUT (la escribe la prueba guiada v3) y marcas ARRIVE/DEPART.
"""
from __future__ import annotations

import argparse
import csv
import glob
import io
import json
import math
import re
import sys
from collections import defaultdict

import numpy as np

SETTLE_S = 1.5
CENTER = "CENTRO"


def read_rows(path: str) -> list[dict]:
    with open(path, encoding="utf-8-sig", newline="") as fh:
        lines = [ln for ln in fh.read().splitlines() if ln.strip() and not ln.startswith("sep=")]
    return list(csv.DictReader(io.StringIO("\n".join(lines))))


def fnum(v):
    try:
        return float(v) if v not in (None, "") else None
    except ValueError:
        return None


def parse_layout(note: str) -> dict:
    d = {}
    for part in note.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def load_run(path: str) -> dict | None:
    rows = read_rows(path)
    layout = next((parse_layout(r["note"]) for r in rows if r.get("event") == "LAYOUT"), None)
    if not layout:
        print(f"[omitido] {path}: no tiene fila LAYOUT (¿no es de la prueba guiada v3?)", file=sys.stderr)
        return None
    spot_beacon = {k: v for k, v in layout.items() if re.fullmatch(r"P\d+", k)}
    marks = sorted(
        [(fnum(r["timestamp_s"]), r["event"], r["true_node"], fnum(r["true_x_m"]), fnum(r["true_y_m"]))
         for r in rows if r.get("event") in ("ARRIVE", "DEPART") and r.get("true_node")],
        key=lambda m: m[0],
    )
    coords = {m[2]: (m[3], m[4]) for m in marks}
    wins = []
    for i, m in enumerate(marks):
        if m[1] != "ARRIVE":
            continue
        end = next((x[0] for x in marks[i + 1:] if x[1] == "DEPART" and x[2] == m[2]), None)
        if end is None:
            continue
        wins.append((m[2], m[0], end))
    samples = defaultdict(list)  # beacon -> [(t, rssi)]
    for r in rows:
        if r.get("event") == "SAMPLE" and r["beacon_name"].startswith("NAV_"):
            t, v = fnum(r["timestamp_s"]), fnum(r["rssi_raw"])
            if t is not None and v is not None:
                samples[r["beacon_name"]].append((t, v))
    return dict(path=path, layout=layout, spot_beacon=spot_beacon, coords=coords, wins=wins, samples=samples)


def dwell_median(run, beacon, a, d):
    v = [x for t, x in run["samples"].get(beacon, []) if a + SETTLE_S <= t <= d]
    return (float(np.median(v)), len(v)) if len(v) >= 5 else (None, len(v))


def fit_additive(rows, factors, numeric=()):
    """rows: list of dict con 'y', factores categóricos y numéricos. Devuelve efectos centrados y residuo."""
    levels = {f: sorted({r[f] for r in rows}) for f in factors}
    cols, names = [np.ones(len(rows))], ["mu"]
    for f in factors:
        for lv in levels[f][1:]:
            cols.append(np.array([1.0 if r[f] == lv else 0.0 for r in rows])); names.append((f, lv))
    for nf in numeric:
        cols.append(np.array([r[nf] for r in rows])); names.append((nf, None))
    X = np.column_stack(cols)
    y = np.array([r["y"] for r in rows])
    sol, *_ = np.linalg.lstsq(X, y, rcond=None)
    resid = y - X @ sol
    eff = {}
    for f in factors:
        raw = {levels[f][0]: 0.0}
        for lv in levels[f][1:]:
            raw[lv] = float(sol[names.index((f, lv))])
        m = np.mean(list(raw.values()))
        eff[f] = {lv: round(float(v - m), 1) for lv, v in raw.items()}
    betas = {nf: round(float(sol[names.index((nf, None))]), 2) for nf in numeric}
    dof = max(1, len(rows) - X.shape[1])
    return eff, betas, round(float(np.sqrt((resid ** 2).sum() / dof)), 2)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", nargs="+", help="CSV o patrones (se expanden)")
    ap.add_argument("--json", help="Guarda el resultado en JSON")
    a = ap.parse_args()

    paths = sorted({p for pat in a.csv for p in (glob.glob(pat) or [pat])})
    runs = [r for r in (load_run(p) for p in paths) if r]
    if len(runs) < 2:
        print("Se necesitan al menos 2 vueltas con LAYOUT.", file=sys.stderr)
        return 1

    own, cross, center = [], [], []
    per_config = defaultdict(list)
    for run in runs:
        cfg = run["layout"].get("rot", "?")
        per_config[cfg].append(run["path"])
        for spot, arr, dep in run["wins"]:
            if spot == CENTER:
                med = {b: dwell_median(run, b, arr, dep)[0] for b in run["samples"]}
                med = {b: v for b, v in med.items() if v is not None}
                if med:
                    center.append((run["path"], max(med, key=med.get), med))
                continue
            own_b = run["spot_beacon"].get(spot)
            for b in run["samples"]:
                v, n = dwell_median(run, b, arr, dep)
                if v is None:
                    continue
                if b == own_b:
                    own.append(dict(y=v, placa=b, posicion=spot, run=run["path"], rot=cfg))
                else:
                    src = next((s for s, bb in run["spot_beacon"].items() if bb == b), None)
                    if src and src in run["coords"] and spot in run["coords"]:
                        d = math.dist(run["coords"][src], run["coords"][spot])
                        cross.append(dict(y=v, placa=b, oyente=spot, logd=math.log10(max(d, 0.05)), run=run["path"]))

    print(f"Vueltas leídas: {len(runs)} · configuraciones: " + ", ".join(f"{k}×{len(v)}" for k, v in sorted(per_config.items())))
    result = {"runs": len(runs), "configs": {k: len(v) for k, v in per_config.items()}}

    # ---- repetibilidad entre vueltas de la misma configuración ----
    rep = defaultdict(list)
    for r in own:
        rep[(r["rot"], r["placa"], r["posicion"])].append(r["y"])
    sds = [float(np.std(v, ddof=1)) for v in rep.values() if len(v) >= 2]
    if sds:
        print(f"\nRepetibilidad del RSSI propio entre vueltas de la MISMA configuración: sd media {np.mean(sds):.1f} dB (máx. {max(sds):.1f} dB)")
        result["repeatability_sd_mean"] = round(float(np.mean(sds)), 1)

    # ---- modelo propio ----
    if len(own) >= 6:
        eff, _, sd = fit_additive(own, ["placa", "posicion"])
        print(f"\n== RSSI PROPIO (contacto/parada en la propia baliza) · residuo {sd} dB")
        print("   efecto de la PLACA   :", eff["placa"])
        print("   efecto de la POSICIÓN:", eff["posicion"])
        pr, sr = max(eff["placa"].values()) - min(eff["placa"].values()), max(eff["posicion"].values()) - min(eff["posicion"].values())
        print(f"   rango placa {pr:.0f} dB · rango posición {sr:.0f} dB")
        result["own"] = dict(effects=eff, resid=sd, plate_range=round(pr, 1), spot_range=round(sr, 1))

    # ---- modelo ajeno (fuga) ----
    if len(cross) >= 8:
        eff, betas, sd = fit_additive(cross, ["placa", "oyente"], numeric=["logd"])
        print(f"\n== RSSI AJENO (baliza oída desde otra posición) · residuo {sd} dB")
        print("   efecto de la PLACA   :", eff["placa"])
        print("   efecto del OYENTE    :", eff["oyente"])
        print(f"   β·log10(d) = {betas['logd']} dB por década (negativo = baja con la distancia; ≈ −20 sería n = 2)")
        pr = max(eff["placa"].values()) - min(eff["placa"].values())
        print(f"   rango placa {pr:.0f} dB")
        result["cross"] = dict(effects=eff, beta=betas["logd"], resid=sd, plate_range=round(pr, 1))

    # ---- centro ----
    if center:
        cnt = defaultdict(int)
        for _, top, _ in center:
            cnt[top] += 1
        print("\n== CENTRO (inicio): baliza más fuerte en cada vuelta:", dict(cnt))
        result["center_top"] = dict(cnt)

    # ---- lectura ----
    if "own" in result and "cross" in result:
        pl, sp = result["own"]["plate_range"], result["own"]["spot_range"]
        pc = result["cross"]["plate_range"]
        print("\n== Lectura sugerida")
        if pc >= 10 and pl < 10:
            msg = "La diferencia ENTRE PLACAS aparece al oírlas desde lejos (fuga) pero no de cerca: sigue a la placa. Compensar con perfil de calibración por baliza o sustituir la placa atípica."
        elif pc < 6 and sp >= 8:
            msg = "Domina la POSICIÓN (muebles/pared/alimentación): recolocar; las placas son equivalentes."
        elif pc >= 10 and sp >= 8:
            msg = "Influyen ambas: compensar la placa por perfil y mejorar la posición."
        else:
            msg = "Diferencias pequeñas: con este montaje ni la placa ni la posición explican los fallos; mira el algoritmo."
        print("  ", msg)
        result["reading"] = msg

    if a.json:
        json.dump(result, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
