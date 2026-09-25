# Energy- and Network-Aware Task Offloading and Scheduling in IoT–Fog–Cloud Environments

- **Student:** Nagineni Ragamaie  
- **Roll No:** S20230010158  
- **Guide:** Dr. Neha Agrawal  
- **Institution:** Indian Institute of Information Technology, Sricity  

---

## 📌 Project Overview

This project implements a **Multi-Objective Arithmetic Optimization Algorithm (MoAOA)**
for joint optimization of task offloading and scheduling in an IoT–Fog–Cloud environment.
The simulation is built on **iFogSim** (CloudSim-based) and run in **Eclipse IDE**.

The project evolved across four evaluations — starting from a basic Industrial IoT scenario
and extending to a full 5G Vehicular Edge Computing environment.

---

## 📚 Reference Papers

1. **Joint Optimization of Computation Offloading and Task Scheduling Using
   Multi-Objective Arithmetic Optimization Algorithm in Cloud-Fog Computing**
   — IEEE Access, 2024 (Asad Ali et al.)

2. **EcoCloud: Green Computing Through Energy and Carbon Efficient Task Scheduling
   in Industrial IoT-Enabled Cloud Environments**
   — IEEE Internet of Things Journal, 2025 (Umit Demirbaga)

---

---

# 📘 EVALUATION 1 & 2 — Industrial IoT–Fog–Cloud (Baseline)

---

## 🏗️ System Architecture (Eval 1 & 2)

```
IoT Layer (Level 3)         Fog Layer (Level 1)         Cloud Layer (Level 0)
─────────────────           ─────────────────           ─────────────────────
sensor-node-0-0             proxy-server                cloud
sensor-node-0-1      →      router-0            →       (44800 MIPS, 40000 MB)
sensor-node-0-2             (2800 MIPS, 4000 MB)
sensor-node-0-3
(500 MIPS, 1000 MB)
```

### Device Levels:
| Level | Device | MIPS | RAM |
|-------|--------|------|-----|
| 0 | Cloud | 44800 | 40000 MB |
| 1 | Proxy Server / Router | 2800 | 4000 MB |
| 3 | Sensor Nodes (Edge) | 500 | 1000 MB |

---

## 📂 Project Structure (Eval 1 & 2)

```
iFogSim-main/
├── src/
│   ├── org/fog/
│   │   ├── entities/
│   │   │   ├── FogDevice.java          # Fog device representation
│   │   │   ├── Sensor.java             # IoT sensor entity
│   │   │   └── Actuator.java
│   │   ├── placement/
│   │   │   ├── Controller.java         # Main controller with MoAOA logic
│   │   │   ├── ModuleMapping.java
│   │   │   └── custom/
│   │   │       └── FogOffloadingPlacement.java  # Module placement logic
│   │   ├── utils/
│   │   │   ├── DebugLogger.java        # Custom logger
│   │   │   ├── FogEvents.java          # Event definitions
│   │   │   └── FogLinearPowerModel.java
│   │   └── test/perfeval/
│   │       ├── IndustrialIoTFog.java   # Main simulation (MoAOA)
│   │       └── CloudOnlyBaseline.java  # Baseline (all tasks to cloud)
├── jars/                               # iFogSim dependencies
├── dataset/                            # IoT datasets (if any)
├── output/                             # Simulation output files
├── results/                            # Result logs
├── simulation_output.txt               # Latest simulation output
└── README.md
```

---

## 🔧 Setup & Run (Eval 1 & 2)

### Prerequisites:
- Java JDK 8 or above
- Eclipse IDE
- iFogSim library (included in `/jars`)

### Steps to Run:

1. **Clone or download** the project
2. **Open Eclipse IDE**
3. **Import project:**
   - File → Import → Existing Projects into Workspace
   - Select the `iFogSim-main` folder
4. **Add JAR files** to build path
5. **Run:** Open `IndustrialIoTFog.java` → Right click → Run As → Java Application

### How the Simulation Works (Eval 2):
```
IndustrialIoTFog.java (Entry Point)
    → Creates topology (Cloud, Fog, Edge devices)
    → Creates sensors (TEMP every 3s, VIB every 10s)
    → FogOffloadingPlacement.java (Initial module placement)
        → data_preprocessor → Edge (Level 3)
        → analytics, cloud_storage → Cloud (Level 0)
    → Controller.java (MoAOA runs here dynamically)
        → Receives tasks from sensors
        → Decides: Fog or Cloud?
        → Schedules tasks by priority
    → Results logged to simulation_output.txt
```

---

## 🧠 Algorithm — MoAOA (Eval 1 & 2)

### How it works:
1. IoT sensors generate tasks (TEMP every 3s, VIB every 10s)
2. Tasks arrive at Fog Controller
3. MoAOA evaluates each task:
   - **Delay-sensitive** → offload to Fog node (Level 3)
   - **Compute-intensive** → offload to Cloud (Level 0)
4. Fitness function:
   ```
   OF = W * Delay + (1 - W) * Energy
   ```
   where W = 0.5 (equal weight to both objectives)
5. Pareto-optimal solution selected
6. Tasks scheduled based on priority (shorter deadline = higher priority)

### Module Placement (Eval 2):
| Module | Placed On | Reason |
|--------|-----------|--------|
| data_preprocessor | Sensor Nodes (Level 3) | Delay-sensitive |
| analytics | Cloud (Level 0) | Compute-intensive |
| cloud_storage | Cloud (Level 0) | Storage-intensive |

---

## 📊 Metrics Measured (Eval 1 & 2)

| Metric | Description |
|--------|-------------|
| Latency (ms) | Total transmission delay across all tiers |
| Energy (J) | Total energy consumption of all devices |
| Throughput | Number of successfully completed tasks |
| Task Completion Rate | % of tasks completed within deadline |
| Cost ($) | Total computation cost |

---

---

# 📗 EVALUATION 3 — Extended to 5G Vehicular Edge Computing

---

## 🆕 What Changed from Eval 2 to Eval 3

| Aspect | Eval 2 (Industrial IoT) | Eval 3 (5G Vehicular) |
|--------|------------------------|----------------------|
| Devices | Static sensor nodes | 200 moving vehicles |
| Middle tier | Fog proxy/router | 5 RSUs (Roadside Units) |
| Task types | TEMP, VIB | SAFETY, TRAFFIC, INFOTAINMENT |
| Deadlines | Generic | 10ms / 100ms / 1000ms |
| Fitness objectives | 2 (Delay + Energy) | 4 (+ Network Energy + Failure Prob) |
| 5G uplink | Not modeled | 0.5ms V2I latency |
| New mechanisms | None | TPC + DPS + RSU Handover |
| Dataset | Hardcoded | 200-vehicle CSV (10,000 records) |

---

## 🏗️ System Architecture (Eval 3 — Vehicular)

```
Vehicle Layer (Level 3)        RSU Layer (Level 2)         Cloud Layer (Level 0)
───────────────────            ──────────────────          ─────────────────────
Vehicle_0                      RSU_0                       Cloud
Vehicle_1   → (5G, 0.5ms) →   RSU_1       → (backhaul) → (44800 MIPS, 40 GB)
...                            RSU_2
Vehicle_199                    RSU_3
(200 vehicles)                 RSU_4
                               (5 Roadside Units)
```

### Task Types (Vehicular):
| Task | Deadline | Priority | Preferred Node |
|------|----------|----------|----------------|
| SAFETY (collision alerts) | 10 ms | Highest | Vehicle |
| TRAFFIC (road conditions) | 100 ms | Medium | RSU |
| INFOTAINMENT (maps, streaming) | 1000 ms | Low | Cloud |

---

## 📂 New Files Added (Eval 3)

```
iFogSim-main/
├── src/org/fog/test/perfeval/
│   └── VehicularFogSimulation.java     # NEW: Main vehicular simulation entry point
├── src/org/fog/placement/custom/
│   └── FogOffloadingPlacement.java     # UPDATED: 3-tier vehicular module placement
├── dataset/
│   └── vehicular_dataset.csv           # NEW: 200 vehicles × 10,000 task records
```

---

## 🔧 Run (Eval 3)

Open `VehicularFogSimulation.java` → Right click → Run As → Java Application

### Internal Flow:
```
VehicularFogSimulation.java
    → Reads 200 vehicles from dataset/vehicular_dataset.csv
    → Creates: 1 Cloud + 5 RSUs + 200 Vehicles (206 total nodes)
    → 200 sensors: one per vehicle (SAFETY / TRAFFIC / INFOTAINMENT)
    → FogOffloadingPlacement.java
        → safety_processor   → Vehicle (Level 3)
        → traffic_processor  → RSU     (Level 2)
        → info_processor     → Cloud   (Level 0)
    → Controller.java
        → Static rounds:  t=5s to t=95s (normal load, 9 rounds)
        → Dynamic round:  t=71s (rush-hour burst — 1.5x tasks)
        → TPC event:      every 10s (RSSI-based Tx power control)
        → DPS event:      every 15s (RSU capacity auto-scaling)
        → RSU Handover:   t=30s, t=70s (vehicle mobility simulation)
    → THREE-WAY COMPARISON printed to console
```

---

## 🧠 Algorithm — MoAOA (Eval 3 — 4-Objective Extension)

### Extended Fitness Function:

```
OF = W1×Delay + W2×ComputeEnergy + W3×NetworkEnergy + W4×FailureProb
```

| Weight | Objective | Value |
|--------|-----------|-------|
| W1 | Minimize Delay | 0.30 |
| W2 | Minimize Compute Energy | 0.30 |
| W3 | Minimize Network Transmission Energy *(new)* | 0.20 |
| W4 | Minimize Task Failure Probability *(new)* | 0.20 |

### New 5G Mechanisms:
| Mechanism | Description |
|-----------|-------------|
| **TPC** | Adjusts vehicle Tx power based on RSSI. Strong signal → 17 dBm. Weak → 23 dBm |
| **DPS** | Scales RSU capacity. >80% load → scale up ×2. <30% load → scale down |
| **RSU Handover** | 5% of vehicles switch RSU zones at t=30s and t=70s |
| **EDF Scheduling** | SAFETY (10ms) always processed before TRAFFIC and INFOTAINMENT |

---

---

# 📙 EVALUATION 4 — Graphs, Final Comparison & Completion

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
- **[1] Cloud-Only:** All tasks to cloud. No offloading. Worst case baseline.
- **[2] MoAOA-Static:** Normal load, full RSU capacity. Best case — 60.8% energy saved, 77% delay reduced.
- **[3] MoAOA-Dynamic:** Rush-hour 1.5x burst, DPS scales RSU capacity ×2. Still beats cloud-only on both metrics.

---

## 📈 Performance Graphs (Eval 4)

All 5 graphs are in the `graphs/` folder:

| Graph | File | Description |
|-------|------|-------------|
| Graph 1 | `graph1_energy_comparison.png` | Total energy — all 3 scenarios |
| Graph 2 | `graph2_delay_comparison.png` | Total delay — all 3 scenarios |
| Graph 3 | `graph3_task_distribution.png` | Vehicle / RSU / Cloud task split |
| Graph 4 | `graph4_tpc_power.png` | RSSI degradation → TPC power adaptation |
| Graph 5 | `graph5_per_task_metrics.png` | Avg energy/task & avg delay/task |

### Generate Graphs:
```bash
pip install matplotlib numpy
python generate_graphs.py
```

---

## 🔄 Evaluation Timeline

| Evaluation | Status | Description |
|------------|--------|-------------|
| Evaluation 1 | ✅ Done | Project idea, architecture design, literature review |
| Evaluation 2 | ✅ Done | MoAOA baseline in iFogSim — Industrial IoT (TEMP/VIB, 2-objective fitness) |
| Evaluation 3 | ✅ Done | Extended to 5G Vehicular — 200 vehicles, 5 RSUs, 4-objective MoAOA, TPC, DPS |
| Evaluation 4 | ✅ Done | 5 performance graphs, THREE-WAY comparison finalized, README updated |

---

## 👩‍💻 Author

- **Nagineni Ragamaie**
- Roll No: S20230010158
- Indian Institute of Information Technology, Sricity
- Guide: Dr. Neha Agrawal
