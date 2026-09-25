# Energy- and Network-Aware Task Offloading and Scheduling in IoT–Fog–Cloud Environments

- **Student:** Nagineni Ragamaie
- **Roll No:** S20230010158
- **Guide:** Dr. Neha Agrawal
- **Institution:** Indian Institute of Information Technology, Sricity

---

## 📌 Project Overview

This project implements a **Multi-Objective Arithmetic Optimization Algorithm (MoAOA)**
for joint optimization of task offloading and scheduling — extended across three evaluations:

| Evaluation | Environment | Focus |
|------------|-------------|-------|
| Eval 1–2 | Industrial IoT → Fog → Cloud | Baseline MoAOA, 2-objective fitness |
| Eval 3 | **5G Vehicular Edge Computing** | 4-objective MoAOA + TPC + DPS + RSU topology |
| Eval 4 | Vehicular + Graphs + Comparison | Performance graphs, final analysis |

The simulation is built on **iFogSim** (CloudSim-based) and run in **Eclipse IDE**.

---

## 📚 Reference Papers

1. **Joint Optimization of Computation Offloading and Task Scheduling Using
   Multi-Objective Arithmetic Optimization Algorithm in Cloud-Fog Computing**
   — IEEE Access, 2024 (Asad Ali et al.)  
   DOI: 10.1109/ACCESS.2024.3512191

2. **EcoCloud: Green Computing Through Energy and Carbon Efficient Task Scheduling
   in Industrial IoT-Enabled Cloud Environments**
   — IEEE Internet of Things Journal, 2025 (Umit Demirbaga)

3. **The Arithmetic Optimization Algorithm**
   — Computer Methods in Applied Mechanics and Engineering, 2021 (Abualigah et al.)

---

## 🏗️ System Architecture (Evaluation 3 — Vehicular)

```
Vehicle Layer (Level 3)       RSU Layer (Level 2)        Cloud Layer (Level 0)
────────────────────          ──────────────────         ─────────────────────
Vehicle_0                     RSU_0                      Cloud
Vehicle_1    → (5G, 0.5ms) →  RSU_1       → (backhaul) → (44800 MIPS, 40GB)
...                           RSU_2
Vehicle_199                   RSU_3
(200 vehicles)                RSU_4
                              (5 Roadside Units)
```

### Task Types (Vehicular):
| Task | Deadline | Priority | Preferred Node |
|------|----------|----------|----------------|
| SAFETY (collision alerts) | 10 ms | Highest | Vehicle |
| TRAFFIC (road conditions) | 100 ms | Medium | RSU |
| INFOTAINMENT (maps, streaming) | 1000 ms | Low | Cloud |

### Device Specifications:
| Level | Device | MIPS | RAM | Uplink Latency |
|-------|--------|------|-----|----------------|
| 0 | Cloud | 44800 | 40000 MB | — |
| 2 | RSU (Roadside Unit) | 20000 | 8000 MB | — |
| 3 | Vehicle | 2000 | 2048 MB | 0.5 ms (5G) |

---

## 📂 Project Structure

```
iFogSim-main/
├── src/
│   ├── org/fog/
│   │   ├── entities/
│   │   │   ├── FogDevice.java              # Fog device base class (all 206 nodes)
│   │   │   ├── Sensor.java                 # Vehicle sensor (SAFETY/TRAFFIC/INFO)
│   │   │   └── Actuator.java
│   │   ├── placement/
│   │   │   ├── Controller.java             # MoAOA + TPC + DPS + RSU Handover
│   │   │   └── custom/
│   │   │       └── FogOffloadingPlacement.java  # 3-tier module placement
│   │   ├── utils/
│   │   │   ├── DebugLogger.java            # Custom logger
│   │   │   ├── FogEvents.java              # Event definitions (TPC, DPS, Handover)
│   │   │   └── FogLinearPowerModel.java
│   │   └── test/perfeval/
│   │       ├── VehicularFogSimulation.java # Eval 3: Main vehicular simulation
│   │       ├── IndustrialIoTFog.java       # Eval 2: Industrial IoT simulation
│   │       └── CloudOnlyBaseline.java      # Baseline: all tasks to cloud
├── dataset/
│   └── vehicular_dataset.csv              # 200 vehicles × 10,000 task records
├── graphs/
│   ├── graph1_energy_comparison.png       # Total energy bar chart
│   ├── graph2_delay_comparison.png        # Total delay bar chart
│   ├── graph3_task_distribution.png       # Task distribution stacked bar
│   ├── graph4_tpc_power.png               # TPC RSSI vs Tx power over time
│   └── graph5_per_task_metrics.png        # Avg energy & delay per task
├── jars/                                  # iFogSim dependencies
├── simulation_output.txt                  # Latest simulation output
└── README.md
```

---

## 🔧 Setup Instructions

### Prerequisites:
- Java JDK 8 or above
- Eclipse IDE
- Python 3.x with matplotlib (for graphs only)
- iFogSim library (included in `/jars`)

### Steps to Run (Evaluation 3 — Vehicular Simulation):

1. **Clone the repository**
2. **Open Eclipse IDE**
3. **Import project:**
   - File → Import → Existing Projects into Workspace
   - Select the `iFogSim-main` folder
4. **Add JAR files** to build path:
   - Right click project → Build Path → Add External JARs
   - Select all JARs from `/jars` folder
5. **Run the simulation:**
   - Open `VehicularFogSimulation.java`
   - Right click → Run As → Java Application

### Internal Flow:
```
VehicularFogSimulation.java  (Entry Point)
    → Reads 200 vehicles from dataset/vehicular_dataset.csv
    → Creates topology: 1 Cloud + 5 RSUs + 200 Vehicles
    → Creates 200 sensors (SAFETY / TRAFFIC / INFOTAINMENT)
    → FogOffloadingPlacement.java (Initial module placement)
        → safety_processor   → Vehicle (Level 3)
        → traffic_processor  → RSU     (Level 2)
        → info_processor     → Cloud   (Level 0)
    → Controller.java (MoAOA runs here)
        → Static rounds:  t=5s to t=95s (normal load)
        → Dynamic round:  t=71s (rush-hour burst, 1.5x tasks)
        → TPC event:      every 10s (RSSI-based power control)
        → DPS event:      every 15s (RSU capacity scaling)
        → RSU Handover:   t=30s, t=70s (vehicle mobility)
    → THREE-WAY COMPARISON printed to console
```

### Generate Graphs:
```bash
pip install matplotlib numpy
python generate_graphs.py
```
Graphs will be saved in the `graphs/` folder.

---

## 🧠 Algorithm — MoAOA (Evaluation 3 Extension)

### 4-Objective Fitness Function:

```
OF = W1×Delay + W2×ComputeEnergy + W3×NetworkEnergy + W4×FailureProb
```

| Weight | Objective | Value |
|--------|-----------|-------|
| W1 | Minimize Delay | 0.30 |
| W2 | Minimize Compute Energy | 0.30 |
| W3 | Minimize Network Transmission Energy | 0.20 |
| W4 | Minimize Task Failure Probability | 0.20 |

### MoAOA Steps:
1. **Initialize Population** — 100 candidate solutions (task-to-node assignments)
2. **EDF Priority Sort** — SAFETY (10ms) first, INFOTAINMENT (1000ms) last
3. **Evaluate Fitness** — 4-objective score per solution
4. **Update via Arithmetic Operators** — Explore (early) → Exploit (late)
5. **Select Best Solution** — Lowest fitness score wins
6. **Dispatch Tasks** — Vehicle / RSU / Cloud based on best assignment

### New 5G Mechanisms:
| Mechanism | Description |
|-----------|-------------|
| **TPC** (Transmission Power Control) | Adjusts vehicle Tx power based on RSSI signal strength. Strong signal → 17 dBm. Weak signal → 23 dBm. Fires every 10s. |
| **DPS** (Dynamic Placement Scaling) | Scales RSU capacity up/down based on utilization. >80% load → scale up. <30% load → scale down. Fires every 15s. |
| **RSU Handover** | Models 5% of vehicles switching RSU zones at t=30s and t=70s. |
| **EDF Scheduling** | Earliest Deadline First priority ensures SAFETY tasks always processed first. |

---

## 📊 Results — THREE-WAY COMPARISON

| Metric | [1] Cloud-Only | [2] MoAOA-Static | [3] MoAOA-Dynamic |
|--------|---------------|------------------|-------------------|
| Total Tasks | 2073 | 2073 | 2135 |
| Total Energy (J) | 199,541.06 | 78,230.09 | 153,884.97 |
| vs Cloud-Only | baseline | **↓ 60.8%** | **↓ 25.1%** |
| Total Delay (ms) | 63,624.30 | 14,663.28 | 86,153.64 |
| vs Cloud-Only | baseline | **↓ 77.0%** | **↓ 36.4%** |
| Avg Energy/Task (J) | 96.26 | 37.74 | 72.08 |
| Avg Delay/Task (ms) | 30.69 | 7.07 | 40.35 |
| Tasks → Vehicle | 0 | 54 | 263 |
| Tasks → RSU | 0 | 1223 | 400 |
| Tasks → Cloud | 2073 | 569 | 1472 |

**Scenarios:**
- **Cloud-Only:** All tasks sent to cloud. No edge offloading. Worst case.
- **MoAOA-Static:** Normal load, full RSU capacity. Best case — 60.8% energy saved.
- **MoAOA-Dynamic:** Rush-hour 1.5x burst, DPS scales RSU capacity ×2. Still beats cloud-only.

---

## 📈 Performance Graphs

All graphs are in the `graphs/` folder:

| Graph | Description |
|-------|-------------|
| `graph1_energy_comparison.png` | Total energy — Cloud-Only vs Static vs Dynamic |
| `graph2_delay_comparison.png` | Total delay — Cloud-Only vs Static vs Dynamic |
| `graph3_task_distribution.png` | Stacked bar — Vehicle / RSU / Cloud task split |
| `graph4_tpc_power.png` | RSSI degradation → TPC power adaptation over time |
| `graph5_per_task_metrics.png` | Avg energy/task and avg delay/task comparison |

---

## 🔄 Evaluation Timeline

| Evaluation | Status | Description |
|------------|--------|-------------|
| Evaluation 1 | ✅ Done | Project idea, architecture design, literature review |
| Evaluation 2 | ✅ Done | MoAOA baseline in iFogSim — Industrial IoT scenario (TEMP/VIB sensors, 2-objective fitness) |
| Evaluation 3 | ✅ Done | Extended to 5G Vehicular — 200 vehicles, 5 RSUs, 4-objective MoAOA, TPC, DPS, RSU Handover |
| Evaluation 4 | ✅ Done | Performance graphs generated, THREE-WAY comparison finalized, GitHub updated |

---

## 👩‍💻 Author

- **Nagineni Ragamaie**
- Roll No: S20230010158
- Indian Institute of Information Technology, Sricity
- Guide: Dr. Neha Agrawal
