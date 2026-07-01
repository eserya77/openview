#!/usr/bin/env python3
"""
Genera datos BLE simulados pero realistas para OpenView.

Salida:
  - openview_ble_ruta_l.csv
  - openview_ble_ruta_triangulo.csv
  - openview_ble_ruta_linea_recta.csv

Cada CSV contiene lecturas por tiempo y por baliza. Los datos siguen el modelo
del prototipo Android: NAV_BLE_01, NAV_BLE_02 y NAV_BLE_03; nodos inicio,
esquina y destino; y deteccion por RSSI con snap al nodo dominante.
"""

from __future__ import annotations

import csv
import math
import random
from dataclasses import dataclass
from pathlib import Path


RANDOM_SEED = 20260619
SAMPLE_HZ = 2.0
POSITION_METHOD = "RSSI ponderado + snap al nodo mas cercano"


@dataclass(frozen=True)
class Node:
    node_id: str
    label: str
    beacon_name: str
    x: float
    y: float


@dataclass(frozen=True)
class RouteSpec:
    route_type: str
    file_name: str
    duration_s: float
    nodes: tuple[Node, Node, Node]
    expected_turn: str


ROUTES = (
    RouteSpec(
        route_type="L",
        file_name="openview_ble_ruta_l.csv",
        duration_s=14.0,
        nodes=(
            Node("inicio", "Posicion 1", "NAV_BLE_01", 0.0, 0.0),
            Node("esquina", "Posicion 2", "NAV_BLE_02", 0.0, 8.0),
            Node("destino", "Posicion 3", "NAV_BLE_03", 6.0, 8.0),
        ),
        expected_turn="gira a la derecha",
    ),
    RouteSpec(
        route_type="Triangulo",
        file_name="openview_ble_ruta_triangulo.csv",
        duration_s=18.0,
        nodes=(
            Node("inicio", "Posicion 1", "NAV_BLE_01", 0.0, 0.0),
            Node("esquina", "Posicion 2", "NAV_BLE_02", 4.0, 7.0),
            Node("destino", "Posicion 3", "NAV_BLE_03", 8.0, 0.0),
        ),
        expected_turn="gira a la derecha pronunciado",
    ),
    RouteSpec(
        route_type="Linea recta",
        file_name="openview_ble_ruta_linea_recta.csv",
        duration_s=8.0,
        nodes=(
            Node("inicio", "Posicion 1", "NAV_BLE_01", 0.0, 0.0),
            Node("esquina", "Posicion 2", "NAV_BLE_02", 0.0, 4.0),
            Node("destino", "Posicion 3", "NAV_BLE_03", 0.0, 8.0),
        ),
        expected_turn="sigue recto",
    ),
)


FIELDNAMES = [
    "route_type",
    "run_id",
    "timestamp_s",
    "elapsed_pct",
    "route_duration_s",
    "segment_index",
    "segment_name",
    "true_x_m",
    "true_y_m",
    "beacon_name",
    "beacon_node_id",
    "beacon_label",
    "beacon_x_m",
    "beacon_y_m",
    "true_distance_to_beacon_m",
    "rssi_dbm",
    "estimated_distance_m",
    "signal_quality",
    "dominant_beacon",
    "detected_node_id",
    "detected_label",
    "expected_instruction",
    "tts_event",
    "progress_step",
    "route_progress_pct",
    "position_method",
    "notes",
]


def interpolate(a: Node, b: Node, local_t: float) -> tuple[float, float]:
    return (
        a.x + (b.x - a.x) * local_t,
        a.y + (b.y - a.y) * local_t,
    )


def distance(ax: float, ay: float, bx: float, by: float) -> float:
    return math.hypot(ax - bx, ay - by)


def path_point(route: RouteSpec, elapsed_s: float) -> tuple[float, float, int, str]:
    first_segment_share = 0.58 if route.route_type == "Triangulo" else 0.55
    split_s = route.duration_s * first_segment_share

    if elapsed_s <= split_s:
        local_t = elapsed_s / split_s if split_s else 0.0
        x, y = interpolate(route.nodes[0], route.nodes[1], local_t)
        return x, y, 1, "Posicion 1 a Posicion 2"

    local_t = (elapsed_s - split_s) / (route.duration_s - split_s)
    x, y = interpolate(route.nodes[1], route.nodes[2], min(local_t, 1.0))
    return x, y, 2, "Posicion 2 a Posicion 3"


def rssi_from_distance(distance_m: float, rng: random.Random) -> float:
    # Modelo simple de perdida logaritmica: RSSI aprox a 1 m = -58 dBm.
    # Se agrega ruido realista por cuerpo/paredes/reflejos interiores.
    d = max(distance_m, 0.35)
    path_loss_n = 2.15
    noise = rng.gauss(0.0, 2.4)
    body_shadow = rng.choice([0.0, 0.0, 0.0, -2.5, 1.2])
    return -58.0 - 10.0 * path_loss_n * math.log10(d) + noise + body_shadow


def estimated_distance_from_rssi(rssi_dbm: float) -> float:
    measured_power = -58.0
    path_loss_n = 2.15
    return 10 ** ((measured_power - rssi_dbm) / (10.0 * path_loss_n))


def signal_quality(rssi_dbm: float) -> str:
    if rssi_dbm >= -67:
        return "estable"
    if rssi_dbm >= -82:
        return "debil"
    return "perdida"


def expected_instruction(route: RouteSpec, segment_index: int) -> str:
    if segment_index == 1:
        return "Avanza hasta Posicion 2"
    if route.route_type == "Linea recta":
        return "Sigue recto hasta Posicion 3"
    if route.route_type == "L":
        return "Gira a la derecha y avanza hasta Posicion 3"
    return "Gira a la derecha de forma pronunciada hasta Posicion 3"


def tts_event(route: RouteSpec, elapsed_s: float, segment_index: int) -> str:
    if elapsed_s == 0:
        return "inicio_ruta"
    split_s = route.duration_s * (0.58 if route.route_type == "Triangulo" else 0.55)
    if abs(elapsed_s - split_s) <= (1.0 / SAMPLE_HZ):
        return "instruccion_giro"
    if abs(elapsed_s - route.duration_s) < 0.001:
        return "llegada"
    return ""


def dominant_detection(rows_for_timestamp: list[dict]) -> tuple[str, str, str]:
    strongest = max(rows_for_timestamp, key=lambda row: float(row["rssi_dbm"]))
    return (
        strongest["beacon_name"],
        strongest["beacon_node_id"],
        strongest["beacon_label"],
    )


def generate_route_rows(route: RouteSpec, rng: random.Random) -> list[dict]:
    rows: list[dict] = []
    sample_count = int(route.duration_s * SAMPLE_HZ) + 1
    run_id = f"openview_{route.route_type.lower().replace(' ', '_')}_001"

    for sample in range(sample_count):
        elapsed_s = min(round(sample / SAMPLE_HZ, 2), route.duration_s)
        x, y, segment_index, segment_name = path_point(route, elapsed_s)
        timestamp_rows: list[dict] = []

        for beacon in route.nodes:
            true_distance = distance(x, y, beacon.x, beacon.y)
            rssi = rssi_from_distance(true_distance, rng)
            estimated_distance = estimated_distance_from_rssi(rssi)
            timestamp_rows.append(
                {
                    "route_type": route.route_type,
                    "run_id": run_id,
                    "timestamp_s": f"{elapsed_s:.2f}",
                    "elapsed_pct": f"{elapsed_s / route.duration_s:.3f}",
                    "route_duration_s": f"{route.duration_s:.2f}",
                    "segment_index": segment_index,
                    "segment_name": segment_name,
                    "true_x_m": f"{x:.2f}",
                    "true_y_m": f"{y:.2f}",
                    "beacon_name": beacon.beacon_name,
                    "beacon_node_id": beacon.node_id,
                    "beacon_label": beacon.label,
                    "beacon_x_m": f"{beacon.x:.2f}",
                    "beacon_y_m": f"{beacon.y:.2f}",
                    "true_distance_to_beacon_m": f"{true_distance:.2f}",
                    "rssi_dbm": f"{rssi:.1f}",
                    "estimated_distance_m": f"{estimated_distance:.2f}",
                    "signal_quality": signal_quality(rssi),
                    "dominant_beacon": "",
                    "detected_node_id": "",
                    "detected_label": "",
                    "expected_instruction": expected_instruction(route, segment_index),
                    "tts_event": tts_event(route, elapsed_s, segment_index),
                    "progress_step": f"{segment_index}/2",
                    "route_progress_pct": f"{100.0 * elapsed_s / route.duration_s:.1f}",
                    "position_method": POSITION_METHOD,
                    "notes": f"duracion observada de referencia: {route.duration_s:.0f}s; giro esperado: {route.expected_turn}",
                }
            )

        dominant_beacon, detected_node_id, detected_label = dominant_detection(timestamp_rows)
        for row in timestamp_rows:
            row["dominant_beacon"] = dominant_beacon
            row["detected_node_id"] = detected_node_id
            row["detected_label"] = detected_label
            rows.append(row)

    return rows


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        # Excel en configuraciones regionales ES a veces no detecta la coma
        # como separador CSV. Esta directiva mantiene el archivo separado por
        # comas y fuerza a Excel a abrir cada campo en su columna.
        handle.write("sep=,\n")
        writer = csv.DictWriter(handle, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    rng = random.Random(RANDOM_SEED)
    output_dir = Path(__file__).resolve().parent

    for route in ROUTES:
        rows = generate_route_rows(route, rng)
        out_path = output_dir / route.file_name
        write_csv(out_path, rows)
        print(f"Generado: {out_path} ({len(rows)} filas)")


if __name__ == "__main__":
    main()
