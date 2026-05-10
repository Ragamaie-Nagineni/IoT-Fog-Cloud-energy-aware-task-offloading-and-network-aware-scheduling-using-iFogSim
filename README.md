# Energy- and Network-Aware Task Offloading and Scheduling in IoT–Fog–Cloud Environments

**Student:** Nagineni Ragamaie
**Roll No:** S20230010158
**Guide:** Dr. Neha Agrawal
**Institution:** Indian Institute of Information Technology, Sricity

---

## 📌 Project Overview

This project implements a **Multi-Objective Arithmetic Optimization Algorithm (MoAOA)**
for joint optimization of task offloading and scheduling in an IoT–Fog–Cloud environment.
The simulation is built on **iFogSim** (CloudSim-based) and run in **Eclipse IDE**.

The key improvements over the baseline MoAOA paper include:
- ✅ Network-aware optimization (energy + latency + network transfer cost)
- ✅ Load-balanced task scheduling across fog and cloud nodes
- ✅ Dynamic task arrival handling
- ✅ Dynamic re-scheduling for changing network conditions
- ✅ Enhanced fitness function

---

## 📚 Reference Papers

1. **Joint Optimization of Computation Offloading and Task Scheduling Using
   Multi-Objective Arithmetic Optimization Algorithm in Cloud-Fog Computing**
   — IEEE Access, 2024 (Asad Ali et al.)

2. **EcoCloud: Green Computing Through Energy and Carbon Efficient Task Scheduling
   in Industrial IoT-Enabled Cloud Environments**
   — IEEE Internet of Things Journal, 2025 (Umit Demirbaga)

---

## 🏗️ System Architecture

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

## 📂 Project Structure

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
5. **Run Baseline first:**
   - Open `CloudOnlyBaseline.java`
   - Right click → Run As → Java Application
   - Note down the baseline results
6. **Run MoAOA simulation:**
   - Open `IndustrialIoTFog.java`
   - Right click → Run As → Java Application
   - Compare results with baseline

---

## 🧠 Algorithm — MoAOA

### How it works:
1. IoT sensors generate tasks (TEMP every 3s, VIB every 10s)
2. Tasks arrive at Fog Controller
3. MoAOA evaluates each task:
   - **Delay-sensitive** → offload to Fog node (Level 3)
   - **Compute-intensive** → offload to Cloud (Level 0)
4. Fitness function evaluated:
   ```
   OF = W * Delay + (1 - W) * Energy
   ```
   where W = 0.5 (equal weight to both objectives)
5. Pareto-optimal solution selected
6. Tasks scheduled based on priority (shorter deadline = higher priority)

### Module Placement:
| Module | Placed On | Reason |
|--------|-----------|--------|
| data_preprocessor | Sensor Nodes (Level 3) | Delay-sensitive |
| analytics | Cloud (Level 0) | Compute-intensive |
| cloud_storage | Cloud (Level 0) | Storage-intensive |

---

## 📊 Metrics Measured

| Metric | Description |
|--------|-------------|
| Latency (ms) | Total transmission delay across all tiers |
| Energy (J) | Total energy consumption of all devices |
| Throughput | Number of successfully completed tasks |
| Task Completion Rate | % of tasks completed within deadline |
| Fairness Index | How fairly resources are distributed |
| Cost ($) | Total computation cost |

---

## 🔄 Evaluation Timeline

| Evaluation | Status | Description |
|------------|--------|-------------|
| Evaluation 1 | ✅ Done | Idea presentation + IoT device modeling |
| Evaluation 2 | ✅ Done | MoAOA baseline implementation in iFogSim |
| Evaluation 3 | 🔄 Next | Network-aware optimization + Load balancing + Dynamic scheduling |
| Evaluation 4 | ⏳ Pending | EcoCloud integration + Final graphs + Performance comparison |

---

## 🚀 Planned Improvements (Evaluation 3 & 4)

### Evaluation 3:
- [ ] Network-aware optimization (consider bandwidth + latency in offloading decision)
- [ ] Load-balanced task scheduling (distribute tasks evenly across nodes)
- [ ] Dynamic task arrival handling (handle varying task rates)
- [ ] Enhanced fitness function (include network transfer energy)

### Evaluation 4:
- [ ] EcoCloud energy model integration (CPU-based energy prediction)
- [ ] Carbon-efficient scheduling
- [ ] Performance graphs:
  - Latency vs Number of Tasks
  - Energy vs Number of Tasks
  - Throughput vs Number of Tasks
  - Task Completion Rate vs Number of Tasks
  - Fairness Index vs Number of Tasks
- [ ] Final comparison: Baseline vs MoAOA vs Optimized MoAOA

---

## 📈 Expected Results

Based on reference papers, the optimized MoAOA is expected to outperform baseline by:
- **~25% improvement** in energy consumption
- **~51% improvement** in latency
- **~96% task completion rate**
- **Higher fairness index** compared to baseline

---

## 👩‍💻 Author

**Nagineni Ragamaie**
S20230010158
Indian Institute of Information Technology, Sricity
Under guidance of Dr. Neha Agrawal
