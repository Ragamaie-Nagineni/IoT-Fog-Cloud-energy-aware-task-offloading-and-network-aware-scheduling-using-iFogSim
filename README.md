# Energy and Network-Aware Task Offloading in 5G Vehicular Edge Computing Using Multi-Objective Arithmetic Optimization Algorithm

- **Student:** Nagineni Ragamaie  
- **Roll No:** S20230010158  
- **Guide:** Dr. Neha Agrawal  
- **Institution:** Indian Institute of Information Technology, Sricity  

---

## 📌 Project Overview

This project extends our original IoT–Fog–Cloud task offloading work into a **5G Vehicular Edge Computing (VEC)** scenario. It implements a **Multi-Objective Arithmetic Optimization Algorithm (MoAOA)** for joint optimization of task offloading and scheduling across **Vehicle → RSU (Roadside Unit) → Cloud** tiers. The simulation is built on **iFogSim** (CloudSim-based) and run in **Eclipse IDE**.

Vehicles continuously generate safety, traffic, and infotainment data. Sending everything to the cloud causes high latency (unsafe for accident-critical tasks) and high network energy consumption. MoAOA decides, task by task, whether it should be processed on the vehicle, at the RSU, or in the cloud — jointly minimizing delay, compute energy, network transmission energy, and task failure probability.

Key improvements over the baseline MoAOA paper (Ali et al., IEEE Access 2024):
- ✅ Vehicular topology with deadline-aware task types (Safety / Traffic / Infotainment)
- ✅ 4-objective fitness function (delay + compute energy + network energy + failure probability)
- ✅ TPC — Transmission Power Control (adjusts vehicle transmit power based on signal/RSU load)
- ✅ DPS — Dynamic Placement Scaling (scales RSU capacity up/down with workload)
- ✅ EDF (Earliest-Deadline-First) priority scheduling
- ✅ Dynamic re-scheduling under burst / rush-hour load

---

## 📚 Reference Papers

1. **Joint Optimization of Computation Offloading and Task Scheduling Using
   Multi-Objective Arithmetic Optimization Algorithm in Cloud-Fog Computing**
   — IEEE Access, 2024 (Asad Ali et al.)

2. **EcoCloud: Green Computing Through Energy and Carbon Efficient Task Scheduling
   in Industrial IoT-Enabled Cloud Environments**
   — Cluster Computing, 2024 (Umit Demirbaga)

3. **An Archive-Based Multi-Objective Arithmetic Optimization Algorithm for Solving
   Industrial Engineering Problems**

4. **Enhancing Public Safety in Intelligent Transportation Systems: Energy-Efficient
   Task Orchestration in 5G Vehicular Edge Networks**

5. **A Novel Energy-Efficient Routing Scheme for LoRa Networks**

---

## 🎯 Motivation

Vehicles generate a huge amount of data continuously — detecting obstacles, checking traffic, playing navigation — all at the same time. Sending all of this to the cloud causes two problems:

- **High latency** — the cloud is far away, so response is slow. For safety applications like obstacle detection, even a small delay can cause accidents.
- **High energy use** — sending data to the cloud over long distances wastes a lot of power.

The question: how do we decide which task goes where, in a way that saves energy and reduces delay?

## ❗ Problem Statement

Vehicles generate three types of tasks with different urgency:

| Task Type | Example | Deadline |
|-----------|---------|----------|
| Safety | Obstacle detection | 10 ms |
| Traffic | Road condition checks | 100 ms |
| Infotainment | Music / maps / navigation | 1000 ms |

The challenge is deciding which task goes to which computing tier while **minimizing energy, minimizing delay, and minimizing task failures** — simultaneously. This is a **Multi-Objective Optimization Problem**.

## 🔍 Gap in Existing Work

Existing papers mostly use simple ML methods (Naive Bayes, Linear Regression) for offloading decisions. Limitations:
- They optimize only one objective at a time (either energy or delay), not both together.
- They ignore network transmission energy (energy used sending data between vehicle and server).
- They don't account for task failures caused by weak signal or low vehicle battery.

Our work addresses all three gaps.

---

## 🧠 Proposed Solution — MoAOA + TPC + DPS

**MoAOA** (Multi-Objective Arithmetic Optimization Algorithm) explores and refines candidate solutions over multiple iterations, simultaneously optimizing four objectives:

1. Minimize delay
2. Minimize compute energy
3. Minimize network transmission energy
4. Minimize task failure probability

### Fitness Function
```
OF = W1×Delay + W2×Energy + W3×NetworkEnergy + W4×FailureProbability
```
Pareto-optimal solution selected each round; tasks scheduled by **EDF (Earliest Deadline First)** priority.

### Supporting Mechanisms
- **TPC — Transmission Power Control:** dynamically adjusts vehicle transmission power based on signal strength / RSU network load. Strong signal → less power; weak signal → more power.
- **DPS — Dynamic Placement Scaling:** dynamically adjusts RSU capacity according to workload. Scales down when utilization is low (saves energy), scales up when utilization is high (handles extra load).

---

## 🏗️ System Architecture

```
Vehicle Layer (Level 3)        RSU / Fog Layer (Level 1-2)      Cloud Layer (Level 0)
────────────────────           ─────────────────────────        ─────────────────────
Vehicle 1 - SAFETY (10ms)                                        Cloud Server
Vehicle 2 - TRAFFIC (100ms)  →   MoAOA Controller (RSU)   →       44800 MIPS, 40000 MB
Vehicle 3 - INFOTAINMENT          Proxy Server / Router            via WAN / 5G
Vehicle N - mixed (TPC + DPS)     2800 MIPS, 4000 MB
```

### Layers
| Layer | Device | Role |
|-------|--------|------|
| 3 — Vehicle | On-Board Unit (OBU) | Safety tasks processed locally (too urgent to offload) |
| 1/2 — RSU (Roadside Unit) | Proxy Server / Router (2800 MIPS, 4000 MB) | Traffic tasks processed here (~0.5 ms response); hosts the MoAOA controller |
| 0 — Cloud | Cloud Server (44800 MIPS, 40000 MB) | Heavy infotainment tasks (more compute power, higher latency) |

The MoAOA controller sits at the RSU layer and decides, for every incoming task, whether it stays at the RSU or is offloaded to the cloud.

---

## 📂 Project Structure

```
iFogSim-main/                        [repository: master]
├── src/                             # Java source (MoAOA controller, TPC, DPS, topology, tasks)
├── Referenced Libraries
├── dataset/                         # Vehicular task datasets (if any)
├── graphs/                          # Performance comparison graphs
│   ├── graph1_energy_comparison.png
│   ├── graph2_delay_comparison.png
│   ├── graph3_task_distribution.png
│   ├── graph4_tpc_power.png
│   └── graph5_per_task_metrics.png
├── iFogSim-main/                    # Core iFogSim framework
├── jars/                            # iFogSim dependencies
├── output/                          # Simulation output files
├── results/                         # Result logs
├── topologies/                      # Vehicle / RSU / Cloud topology definitions
├── sample/                          # Sample configs / test cases
├── .classpath
├── .gitignore
├── .project
├── LICENSE.txt
├── placement_debug.txt              # Module/task placement debug log
├── simulation_output.txt            # Latest simulation output
└── README.md
```

---

## 🔧 Setup Instructions

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
4. **Add JAR files** to build path (if not already added):
   - Right click project → Build Path → Add External JARs
   - Select all JARs from `/jars` folder
5. **Run the simulation:**
   - Open `VehicularEdgeMoAOA.java`
   - Right click → Run As → Java Application
   - This single file runs the entire simulation including:
     - Vehicle, RSU, and Cloud topology creation
     - Task generation (Safety / Traffic / Infotainment)
     - MoAOA task offloading and scheduling (with TPC + DPS)
     - Result collection and logging

> **Note:** `CloudOnlyBaseline.java` is available separately for reference
> to compare cloud-only results against MoAOA optimized results,
> but is **not required** to run the main simulation.

### How the Simulation Works Internally:
```
VehicularEdgeMoAOA.java (Entry Point)
    → Creates topology (Vehicles, RSUs, Cloud)
    → Creates task sources (SAFETY, TRAFFIC, INFOTAINMENT)
    → FogOffloadingPlacement.java (Initial module placement)
    → Controller.java (MoAOA runs here dynamically)
        → Receives tasks from vehicles
        → Applies TPC (transmission power control)
        → Decides: Vehicle, RSU, or Cloud?
        → Applies DPS (RSU capacity scaling)
        → Schedules tasks by EDF priority (shortest deadline first)
    → Results logged to simulation_output.txt
```

---

## 📊 Metrics Measured

| Metric | Description |
|--------|-------------|
| Total Energy (J) | Total compute energy consumption across tiers |
| Network Energy (J) | Energy used transmitting data vehicle ↔ RSU ↔ cloud |
| Total / Avg Delay (ms) | End-to-end response delay per task and overall |
| Task Failure Rate | % of tasks failing due to weak signal / deadline miss |
| TPC Energy Savings | Energy saved via adaptive transmission power control |
| Task Distribution | Tasks routed to Vehicle vs RSU vs Cloud |

---

## 🔄 Evaluation Timeline

| Evaluation | Status | Description |
|------------|--------|-------------|
| Evaluation 1 | ✅ Done | Idea presentation + IoT device modeling |
| Evaluation 2 | ✅ Done | MoAOA baseline implementation in iFogSim |
| Evaluation 3 | ✅ Done | Extended to 5G Vehicular Edge Computing — vehicle nodes, RSU/MEC layer, 4-objective fitness (delay + energy + network energy + failure), TPC, DPS, EDF scheduling |
| Evaluation 4 | 🔄 Next | Vehicle handover, task re-scheduling, load balancing, dynamic re-scheduling under burst load, final performance graphs & comparison |

---

## 🚀 What Changed in Evaluation 3

- Replaced static sensor nodes with **vehicle nodes**
- Added an **RSU / MEC layer** (Level 2)
- Replaced TEMP/VIB sensor tasks with **SAFETY / TRAFFIC / INFOTAINMENT** vehicular tasks
- Updated the **MoAOA decision logic** for the vehicular scenario
- Updated the **fitness function** to include network energy and failure probability
- Added new simulation events for vehicular task generation

## 🚀 Planned Improvements (Evaluation 4)

- [ ] Add vehicle handover between RSUs
- [ ] Add task re-scheduling
- [ ] Add load balancing across RSUs
- [ ] Add dynamic re-scheduling under burst/rush-hour load
- [ ] Generate performance graphs (Energy, Delay, Failure Rate, TPC savings vs Naive Bayes baseline)
- [ ] Final comparison: Cloud-Only vs MoAOA-Static vs MoAOA-Dynamic

---

## 📈 Results (Evaluation 3)

Three-way comparison against the Ali et al. (IEEE Access 2024) MoAOA baseline:

- **[1] Cloud-Only** — all tasks → cloud, no offloading (worst case)
- **[2] MoAOA-Static** — normal vehicle load, full RSU capacity (best case)
- **[3] MoAOA-Dynamic** — burst load (1.5×), halved RSU capacity (stress test)

| Metric | Cloud-Only | MoAOA-Static | MoAOA-Dynamic |
|--------|-----------:|-------------:|---------------:|
| Total Tasks | 2073 | 2073 | 2135 |
| Task Compute Energy (J) | 199541.06 | 78230.09 (↓ 60.8%) | 153884.97 (↓ 25.1%) |
| Total Delay (ms) | 63624.30 | 14663.28 (↓ 77.0%) | 86153.64 (↓ 36.4%) |
| Avg Energy / Task (J) | 96.26 | 37.74 | 72.08 |
| Avg Delay / Task (ms) | 30.69 | 7.07 | 40.35 |
| Tasks → Vehicle | 0 | 54 | 263 |
| Tasks → RSU | 0 | 1223 | 400 |
| Tasks → Cloud | 2073 | 569 | 1472 |
| Overloaded Rounds | N/A | 0 | 0 / 1 |

**Summary:** Compared to the cloud-only baseline, MoAOA-Static reduces energy by **60.8%** and delay by **77%**, while MoAOA-Dynamic remains robust under rush-hour stress conditions.

---

## 👩‍💻 Author

- **Nagineni Ragamaie**
- S20230010158
- Indian Institute of Information Technology, Sricity
- Under guidance of Dr. Neha Agrawal
