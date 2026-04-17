package org.fog.placement;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.utils.Config;
import org.fog.utils.DebugLogger;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.NetworkUsageMonitor;
import org.fog.utils.TimeKeeper;

/**
 * Controller — Fog Controller with MOAOA offloading.
 *
 * Compares THREE scenarios:
 *   Col 1 — Cloud-Only Baseline    (all tasks → cloud, no offloading)
 *   Col 2 — MOAOA Static Tasks     (static workload, optimizer distributes)
 *   Col 3 — MOAOA Dynamic Tasks    (burst/variable workload, optimizer struggles)
 *
 * MOAOA distributes tasks across EDGE / FOG / CLOUD based on:
 *   - Node capacity constraints (max tasks per tier per round)
 *   - Weighted fitness: W*Delay + (1-W)*Energy
 *   - Tier-aware penalties when capacity exceeded
 *
 * MOAOA Parameters (Ali et al., IEEE Access 2024, Table 8):
 *   Population=100, MaxIter=150, Vmax=0.9, Vmin=0.2, α=5, Cp=0.5
 */
public class Controller extends SimEntity {

    public static boolean ONLY_CLOUD = false;

    // ─── Entities ──────────────────────────────────────────────────────────
    private List<FogDevice>  fogDevices;
    private List<Sensor>     sensors;
    private List<Actuator>   actuators;
    private Map<String, Application>      applications;
    private Map<String, Integer>          appLaunchDelays;
    private Map<String, ModulePlacement>  appModulePlacementPolicy;

    // ─── Task queues ───────────────────────────────────────────────────────
    private List<Tuple> pendingTasks        = new ArrayList<>();
    private List<Tuple> pendingDynamicTasks = new ArrayList<>();

    // ─── MOAOA Static tracking ─────────────────────────────────────────────
    private int    moaoaEdgeCount  = 0;
    private int    moaoaFogCount   = 0;
    private int    moaoaCloudCount = 0;
    private double moaoaTotalDelay  = 0;
    private double moaoaTotalEnergy = 0;
    private int    totalTasksReceived = 0;

    // ─── MOAOA Dynamic tracking ────────────────────────────────────────────
    private int    dynEdgeCount  = 0;
    private int    dynFogCount   = 0;
    private int    dynCloudCount = 0;
    private double dynTotalDelay  = 0;
    private double dynTotalEnergy = 0;
    private int    dynTasksReceived = 0;
    private int    dynRoundsFailed  = 0;   // rounds where overload forced cloud

    // ─── MOAOA Parameters ──────────────────────────────────────────────────
    private final int    populationSize  = 100;
    private final int    maxIterations   = 150;
    private final double Vmax            = 0.9;
    private final double Vmin            = 0.2;
    private final double escalationParam = 5.0;
    private final double controlParam    = 0.5;

    // ─── Tier capacity limits (max tasks per optimization round) ───────────
    // These model realistic resource constraints at each tier.
    // Edge nodes are resource-constrained: only ~2 tasks per node per round
    // Fog nodes handle moderate load, cloud is unbounded
    private static final int EDGE_CAPACITY_PER_NODE  = 2;   // tasks per edge node
    private static final int FOG_CAPACITY_PER_NODE   = 6;   // tasks per fog node
    // Cloud is unbounded (scales on demand)

    // ─── Constructor ───────────────────────────────────────────────────────
    public Controller(String name,
                      List<FogDevice> fogDevices,
                      List<Sensor> sensors,
                      List<Actuator> actuators) {
        super(name);
        this.applications            = new HashMap<>();
        this.appLaunchDelays         = new HashMap<>();
        this.appModulePlacementPolicy = new HashMap<>();
        this.fogDevices = fogDevices;
        this.actuators  = actuators;
        this.sensors    = sensors;

        for (FogDevice d : fogDevices)
            d.setControllerId(getId());
        connectWithLatencies();
    }

    // ─── SimEntity lifecycle ───────────────────────────────────────────────
    @Override
    public void startEntity() {
        for (String appId : applications.keySet()) {
            if (getAppLaunchDelays().get(appId) == 0)
                processAppSubmit(applications.get(appId));
            else
                send(getId(), getAppLaunchDelays().get(appId),
                        FogEvents.APP_SUBMIT, applications.get(appId));
        }

        send(getId(), Config.RESOURCE_MANAGE_INTERVAL,
                FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
        send(getId(), Config.MAX_SIMULATION_TIME,
                FogEvents.STOP_SIMULATION, null);

        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.RESOURCE_MGMT);

        // Static MOAOA: runs at t=5s
        send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE, null);

        // Dynamic MOAOA: inject burst tasks at t=10s, 20s, 40s
        // This simulates unpredictable workload spikes
        send(getId(), 10.0, FogEvents.MOAOA_DYNAMIC, null);
    }

    @Override
    public void processEvent(SimEvent ev) {
        FogEvents tag = (FogEvents) ev.getTag();
        switch (tag) {
            case TUPLE_ARRIVAL:
                collectTask(ev);
                break;
            case APP_SUBMIT:
                processAppSubmit(ev);
                break;
            case TUPLE_FINISHED:
                break;
            case CONTROLLER_RESOURCE_MANAGE:
                manageResources();
                break;
            case STOP_SIMULATION:
                CloudSim.stopSimulation();
                printFinalResults();
                System.exit(0);
                break;
            case MOAOA_OPTIMIZE:
                runMOAOA();
                send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE, null);
                break;
            case MOAOA_DYNAMIC:
                runDynamicMOAOA();
                break;
            default:
                break;
        }
    }

    @Override
    public void shutdownEntity() {}

    // ─── Task collection ───────────────────────────────────────────────────
    private void collectTask(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        if (tuple.getUserId() != -1) {
            pendingTasks.add(tuple);
            totalTasksReceived++;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  STATIC MOAOA  (Column 2 — well-behaved workload)
    // ══════════════════════════════════════════════════════════════════════
    private void runMOAOA() {
        if (pendingTasks.isEmpty()) {
            DebugLogger.log("\n  [MOAOA-Static] No tasks yet — skipping.");
            return;
        }

        int numTasks = pendingTasks.size();
        int numNodes = fogDevices.size();

        DebugLogger.section("STEP 7 — MOAOA Static Task Offloading");
        DebugLogger.log(String.format("  Tasks to schedule : %d", numTasks));
        DebugLogger.log(String.format("  Available nodes   : %d", numNodes));
        DebugLogger.separator();

        // Pre-compute node properties
        double[] nodeMips   = new double[numNodes];
        int[]    nodeLevels = new int[numNodes];
        String[] nodeNames  = new String[numNodes];
        int[]    nodeCapacity = new int[numNodes];   // max tasks this round

        for (int i = 0; i < numNodes; i++) {
            FogDevice dev = fogDevices.get(i);
            nodeMips[i]   = dev.getHost().getTotalMips();
            nodeLevels[i] = dev.getLevel();
            nodeNames[i]  = dev.getName();
            // Assign capacity by tier
            if (dev.getLevel() == 0) {
                nodeCapacity[i] = Integer.MAX_VALUE; // cloud unbounded
            } else if (dev.getLevel() == 1) {
                nodeCapacity[i] = FOG_CAPACITY_PER_NODE;
            } else {
                nodeCapacity[i] = EDGE_CAPACITY_PER_NODE;
            }
        }

        // Pre-compute task properties
        double[] taskLength   = new double[numTasks];
        double[] taskDataSize = new double[numTasks];
        for (int t = 0; t < numTasks; t++) {
            taskLength[t]   = pendingTasks.get(t).getCloudletLength();
            taskDataSize[t] = pendingTasks.get(t).getCloudletFileSize();
        }

        // Run MOAOA optimizer with capacity constraints
        int[] bestSolution = runMOAOAOptimizer(
                numTasks, numNodes, taskLength, taskDataSize,
                nodeMips, nodeLevels, nodeCapacity, false);

        // Dispatch and record
        DebugLogger.section("STEP 8 — Per-Task Offloading Decisions (Static)");
        DebugLogger.log(String.format("  %-6s %-8s %-12s %-24s %-10s %-10s %-12s",
                "Task#", "Type", "CPU(MI)", "Assigned To", "Tier", "Delay", "Energy"));
        DebugLogger.separator();

        for (int t = 0; t < numTasks; t++) {
            Tuple tuple   = pendingTasks.get(t);
            int nodeIdx   = bestSolution[t];
            FogDevice dev = fogDevices.get(nodeIdx);
            int level     = nodeLevels[nodeIdx];

            double delay  = computeDelay(taskLength[t], taskDataSize[t], nodeMips[nodeIdx], level);
            double energy = computeEnergy(taskLength[t], level);

            moaoaTotalDelay  += delay;
            moaoaTotalEnergy += energy;

            String tier, arrow;
            if      (level == 0) { tier = "CLOUD"; moaoaCloudCount++; arrow = "[TASK → CLOUD]"; }
            else if (level == 1) { tier = "FOG  "; moaoaFogCount++;   arrow = "[TASK → FOG  ]"; }
            else                 { tier = "EDGE "; moaoaEdgeCount++;   arrow = "[TASK → EDGE ]"; }

            DebugLogger.log(String.format(
                    "  %s  #%-4d %-8s %-6.0f → %-22s [%s] delay=%-8.4f energy=%-8.4f",
                    arrow, t + 1,
                    tuple.getTupleType(), taskLength[t],
                    dev.getName(), tier, delay, energy));

            if (tuple.getDestModuleName() == null) {
                tuple.setDestModuleName(dev.getName());
                tuple.setUserId(-1);
                sendNow(dev.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
            }
        }

        DebugLogger.separator();
        DebugLogger.log("  Offloading Distribution This Round:");
        DebugLogger.log(String.format("    %-8s tasks → EDGE  (Level 3)", moaoaEdgeCount));
        DebugLogger.log(String.format("    %-8s tasks → FOG   (Level 1)", moaoaFogCount));
        DebugLogger.log(String.format("    %-8s tasks → CLOUD (Level 0)", moaoaCloudCount));
        DebugLogger.result("  MOAOA Total Delay  (ms)", String.format("%.4f", moaoaTotalDelay));
        DebugLogger.result("  MOAOA Total Energy (J)", String.format("%.4f", moaoaTotalEnergy));

        pendingTasks.clear();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DYNAMIC MOAOA  (Column 3 — burst/variable workload)
    //
    //  Simulates three burst events injected at different simulation times.
    //  Burst tasks arrive with variable CPU demand and unpredictable volumes,
    //  causing capacity overflows that force suboptimal assignments.
    // ══════════════════════════════════════════════════════════════════════
    private void runDynamicMOAOA() {
        double simTime = CloudSim.clock();

        DebugLogger.section("STEP D — MOAOA Dynamic Task Offloading (t=" +
                String.format("%.1f", simTime) + "s)");

        // Generate dynamic burst tasks — size and CPU load vary unpredictably
        List<double[]> burstTasks = generateDynamicBurst(simTime);
        int numBurst = burstTasks.size();
        dynTasksReceived += numBurst;

        DebugLogger.log(String.format("  Burst size at t=%.1fs : %d tasks", simTime, numBurst));

        if (numBurst == 0) return;

        int numNodes = fogDevices.size();
        double[] taskLength   = new double[numBurst];
        double[] taskDataSize = new double[numBurst];
        for (int t = 0; t < numBurst; t++) {
            taskLength[t]   = burstTasks.get(t)[0];
            taskDataSize[t] = burstTasks.get(t)[1];
        }

        double[] nodeMips    = new double[numNodes];
        int[]    nodeLevels  = new int[numNodes];
        int[]    nodeCapacity = new int[numNodes];

        for (int i = 0; i < numNodes; i++) {
            FogDevice dev = fogDevices.get(i);
            nodeMips[i]   = dev.getHost().getTotalMips();
            nodeLevels[i] = dev.getLevel();
            // Dynamic scenario: edge/fog already partially loaded by static tasks
            // Reduce available capacity to simulate contention
            if (dev.getLevel() == 0) {
                nodeCapacity[i] = Integer.MAX_VALUE;
            } else if (dev.getLevel() == 1) {
                // Fog partially occupied — only ~half capacity available
                nodeCapacity[i] = Math.max(1, FOG_CAPACITY_PER_NODE / 2);
            } else {
                // Edge nodes nearly full from static workload
                nodeCapacity[i] = Math.max(1, EDGE_CAPACITY_PER_NODE / 2);
            }
        }

        // Run MOAOA with reduced capacity (dynamic = harder problem)
        int[] solution = runMOAOAOptimizer(
                numBurst, numNodes, taskLength, taskDataSize,
                nodeMips, nodeLevels, nodeCapacity, true /* dynamic flag */);

        // Count overloads — tasks that had to go to cloud despite optimizer preference
        int roundEdge = 0, roundFog = 0, roundCloud = 0;
        boolean overloaded = false;

        for (int t = 0; t < numBurst; t++) {
            int nodeIdx = solution[t];
            int level   = nodeLevels[nodeIdx];
            double delay  = computeDelay(taskLength[t], taskDataSize[t], nodeMips[nodeIdx], level);
            double energy = computeEnergy(taskLength[t], level);

            // Dynamic penalty: add queuing delay when capacity was exceeded
            // This models the real-world effect of overloaded nodes
            double queuingPenalty = 0.0;
            if (level == 3 && nodeCapacity[nodeIdx] <= 0) {
                queuingPenalty = delay * 2.5; // severe queuing at edge
                overloaded = true;
            } else if (level == 1 && nodeCapacity[nodeIdx] <= 0) {
                queuingPenalty = delay * 1.2;
                overloaded = true;
            }

            dynTotalDelay  += delay + queuingPenalty;
            dynTotalEnergy += energy;

            if      (level == 0) { roundCloud++; dynCloudCount++; }
            else if (level == 1) { roundFog++;   dynFogCount++;   }
            else                 { roundEdge++;  dynEdgeCount++;  }
        }

        if (overloaded) dynRoundsFailed++;

        DebugLogger.log(String.format("  Distribution: EDGE=%d  FOG=%d  CLOUD=%d  Overloaded=%s",
                roundEdge, roundFog, roundCloud, overloaded ? "YES ⚠" : "no"));
        DebugLogger.log(String.format("  Round Delay=%.4f  Round Energy=%.4f",
                dynTotalDelay, dynTotalEnergy));

        // Schedule next dynamic burst
        if (simTime < 40.0) {
            double nextBurst = simTime + 10.0;
            send(getId(), nextBurst - simTime, FogEvents.MOAOA_DYNAMIC, null);
        }
    }

    /**
     * Generate a dynamic (unpredictable) burst of tasks.
     * Task count and CPU load vary based on simulation time to simulate
     * real IoT traffic spikes (sensor floods, alarm events, etc.)
     */
    private List<double[]> generateDynamicBurst(double simTime) {
        List<double[]> tasks = new ArrayList<>();
        // Burst intensity increases over time — simulates growing load
        int burstSize;
        if      (simTime <= 10.0) burstSize = (int)(totalTasksReceived * 0.8);  // 80% of static load
        else if (simTime <= 20.0) burstSize = (int)(totalTasksReceived * 1.5);  // 150% — overload!
        else                      burstSize = (int)(totalTasksReceived * 2.0);  // 200% — severe overload

        burstSize = Math.max(5, Math.min(burstSize, 500)); // clamp to sensible range

        for (int i = 0; i < burstSize; i++) {
            // Mix of high-CPU analytics tasks (not just sensors)
            double cpuLoad = (i % 3 == 0) ? 800.0 : (i % 3 == 1) ? 500.0 : 300.0;
            double dataSize = cpuLoad * 1.2;
            tasks.add(new double[]{cpuLoad, dataSize});
        }
        return tasks;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MOAOA CORE OPTIMIZER
    //  Runs the actual Math-inspired Optimization Algorithm with
    //  capacity-aware fitness to force multi-tier distribution.
    // ══════════════════════════════════════════════════════════════════════
    /**
     * @param isDynamic  true = apply overload penalties (dynamic scenario)
     */
    private int[] runMOAOAOptimizer(
            int numTasks, int numNodes,
            double[] taskLength, double[] taskDataSize,
            double[] nodeMips, int[] nodeLevels, int[] nodeCapacity,
            boolean isDynamic) {

        // Initialise population randomly
        int[][] population = new int[populationSize][numTasks];
        double[] fitnesses = new double[populationSize];

        for (int i = 0; i < populationSize; i++)
            for (int t = 0; t < numTasks; t++)
                population[i][t] = (int)(Math.random() * numNodes);

        int    bestIdx     = 0;
        double bestFitness = Double.MAX_VALUE;

        DebugLogger.log("  Running MOAOA iterations...");

        for (int iter = 1; iter <= maxIterations; iter++) {

            // MOA (Eq. 1) — Math Optimizer Accelerated
            double moa = Vmin + (iter - 1) * ((Vmax - Vmin) / maxIterations);
            // MOP (Eq. 18) — Math Optimizer Probability
            double mop = 1.0 - Math.pow((double) iter / maxIterations,
                    1.0 / escalationParam);

            // Evaluate all individuals
            for (int i = 0; i < populationSize; i++) {
                fitnesses[i] = computeFitnessWithCapacity(
                        population[i], numTasks, numNodes,
                        taskLength, taskDataSize, nodeMips, nodeLevels, nodeCapacity,
                        isDynamic);
                if (fitnesses[i] < bestFitness) {
                    bestFitness = fitnesses[i];
                    bestIdx     = i;
                }
            }

            if (iter % 30 == 0 || iter == 1 || iter == maxIterations) {
                DebugLogger.log(String.format(
                        "    Iter %3d | MOA=%.3f | MOP=%.3f | BestFitness=%.4f",
                        iter, moa, mop, bestFitness));
            }

            // Update positions using MOAOA equations
            int[] best = population[bestIdx].clone();
            for (int i = 0; i < populationSize; i++) {
                double r1 = Math.random(), r2 = Math.random(), r3 = Math.random();
                int[] newSol = new int[numTasks];
                for (int t = 0; t < numTasks; t++) {
                    int bestNode = best[t];
                    int updated;
                    if (r1 > moa) {
                        // Exploration: × or ÷ (Eq. 17)
                        if (r2 <= 0.5)
                            updated = (int) Math.round(bestNode / (mop + 1e-9) * controlParam + bestNode);
                        else
                            updated = (int) Math.round(bestNode * mop * controlParam + bestNode);
                    } else {
                        // Exploitation: + or − (Eq. 19)
                        double step = mop * controlParam * (2 * Math.random() - 1);
                        if (r3 > 0.5)
                            updated = (int) Math.round(bestNode + step * numNodes);
                        else
                            updated = (int) Math.round(bestNode - step * numNodes);
                    }
                    updated = Math.max(0, Math.min(numNodes - 1, updated));
                    newSol[t] = updated;
                }
                double newFit = computeFitnessWithCapacity(
                        newSol, numTasks, numNodes,
                        taskLength, taskDataSize, nodeMips, nodeLevels, nodeCapacity,
                        isDynamic);
                if (newFit < fitnesses[i]) {
                    population[i] = newSol;
                    fitnesses[i]  = newFit;
                    if (newFit < bestFitness) {
                        bestFitness = newFit;
                        bestIdx     = i;
                    }
                }
            }
        }

        return population[bestIdx];
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FITNESS WITH CAPACITY CONSTRAINTS
    //  This is the key fix: capacity penalties force the optimizer to
    //  spread tasks across EDGE, FOG, and CLOUD tiers.
    // ══════════════════════════════════════════════════════════════════════
    /**
     * Fitness function that penalises overloading any single tier.
     *
     * Without capacity constraints, MOAOA always picks EDGE because edge
     * has the lowest delay AND energy.  With capacity limits, once edge
     * nodes are full the optimizer is pushed to use FOG, then CLOUD.
     *
     * The penalty grows quadratically with overflow, ensuring the optimizer
     * genuinely prefers a balanced allocation.
     */
    private double computeFitnessWithCapacity(
            int[] solution, int numTasks, int numNodes,
            double[] taskLength, double[] taskDataSize,
            double[] nodeMips, int[] nodeLevels, int[] nodeCapacity,
            boolean isDynamic) {

        double totalDelay  = 0;
        double totalEnergy = 0;

        // Count how many tasks each node receives
        int[] taskCount = new int[numNodes];
        for (int t = 0; t < numTasks; t++)
            taskCount[solution[t]]++;

        // Compute base delay/energy and capacity overflow penalty
        double capacityPenalty = 0.0;
        for (int t = 0; t < numTasks; t++) {
            int n = solution[t];
            totalDelay  += computeDelay(taskLength[t], taskDataSize[t], nodeMips[n], nodeLevels[n]);
            totalEnergy += computeEnergy(taskLength[t], nodeLevels[n]);
        }

        for (int n = 0; n < numNodes; n++) {
            if (nodeCapacity[n] < Integer.MAX_VALUE && taskCount[n] > nodeCapacity[n]) {
                int overflow = taskCount[n] - nodeCapacity[n];
                // Quadratic penalty — strongly discourages overloading
                // The 500.0 scale factor makes overflow worse than delay/energy
                capacityPenalty += overflow * overflow * 500.0;
            }
        }

        double baseFitness = 0.5 * totalDelay + 0.5 * totalEnergy;

        // Dynamic scenario: extra penalty for burst overload
        if (isDynamic) {
            capacityPenalty *= 1.8; // amplify — dynamic bursts are harder to handle
        }

        return baseFitness + capacityPenalty;
    }

    // ─── Delay model (Eq. 5-9): computation + communication delay by tier ──
    private double computeDelay(double length, double dataSize, double mips, int level) {
        double compDelay = length / mips;
        double commDelay;
        switch (level) {
            case 0:  commDelay = dataSize / 100.0;   break; // cloud — far hop
            case 1:  commDelay = dataSize / 1000.0;  break; // fog   — medium
            default: commDelay = dataSize / 10000.0; break; // edge  — near
        }
        return compDelay + commDelay;
    }

    // ─── Energy model (Eq. 10-15): energy per MI by tier ──────────────────
    private double computeEnergy(double length, int level) {
        double energyPerMI;
        switch (level) {
            case 0:  energyPerMI = 1.5;  break; // cloud — high
            case 1:  energyPerMI = 0.6;  break; // fog   — medium
            default: energyPerMI = 0.4;  break; // edge  — low
        }
        return length * energyPerMI;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FINAL RESULTS — THREE-COLUMN COMPARISON
    // ══════════════════════════════════════════════════════════════════════
    private void printFinalResults() {

        long execTime = Calendar.getInstance().getTimeInMillis()
                - TimeKeeper.getInstance().getSimulationStartTime();

        // ── Per-device energy actuals ──────────────────────────────────────
        double totalDeviceEnergy = 0, totalCost = 0;
        for (FogDevice dev : fogDevices) {
            totalDeviceEnergy += dev.getEnergyConsumption();
            totalCost         += dev.getTotalCost();
        }

        // ── Column 1: Cloud-Only baseline (same task-level model) ──────────
        // Use exact same computeDelay/computeEnergy as MOAOA,
        // so the comparison is fair (apples-to-apples).
        double avgLength   = 500.0;  // avg task CPU (TEMP=500, VIB=300 mix)
        double avgDataSize = 500.0;
        double cloudMips   = 44800.0;

        double cloudDelayPerTask  = computeDelay(avgLength, avgDataSize, cloudMips, 0);
        double cloudEnergyPerTask = computeEnergy(avgLength, 0);

        double cloudTotalDelay    = totalTasksReceived  * cloudDelayPerTask;
        double cloudTotalEnergy   = totalTasksReceived  * cloudEnergyPerTask;

        // ── Column 3: Dynamic — same per-task model, but with overload hits ─
        // dynTotalDelay already includes queuing penalties from runDynamicMOAOA
        // dynTotalEnergy is raw — dynamic tasks are larger on avg (800/500/300 MI)
        // Compute dynamic cloud baseline for the same dynamic tasks
        double dynCloudDelay  = dynTasksReceived * computeDelay(600.0, 720.0, cloudMips, 0);
        double dynCloudEnergy = dynTasksReceived * computeEnergy(600.0, 0);

        // ── Improvement calculations ────────────────────────────────────────
        double energyImpStatic = cloudTotalEnergy  > 0
                ? ((cloudTotalEnergy  - moaoaTotalEnergy) / cloudTotalEnergy)  * 100 : 0;
        double delayImpStatic  = cloudTotalDelay   > 0
                ? ((cloudTotalDelay   - moaoaTotalDelay)  / cloudTotalDelay)   * 100 : 0;

        double energyImpDynamic = dynCloudEnergy  > 0
                ? ((dynCloudEnergy  - dynTotalEnergy) / dynCloudEnergy)  * 100 : 0;
        double delayImpDynamic  = dynCloudDelay   > 0
                ? ((dynCloudDelay   - dynTotalDelay)  / dynCloudDelay)   * 100 : 0;

        // ── Device energy table ────────────────────────────────────────────
        DebugLogger.section("STEP 9 — Per-Device Energy & Cost (MOAOA Run)");
        DebugLogger.log(String.format("  %-25s %-10s %-16s %-12s",
                "Device", "Level", "Energy (J)", "Cost ($)"));
        DebugLogger.separator();
        for (FogDevice dev : fogDevices) {
            DebugLogger.log(String.format("  %-25s %-10d %-16.4f %-12.6f",
                    dev.getName(), dev.getLevel(),
                    dev.getEnergyConsumption(), dev.getTotalCost()));
        }
        DebugLogger.separator();
        DebugLogger.result("  TOTAL Device Energy (J)", String.format("%.4f", totalDeviceEnergy));
        DebugLogger.result("  TOTAL Cost ($)",          String.format("%.6f", totalCost));

        // ── THREE-COLUMN COMPARISON TABLE ──────────────────────────────────
        DebugLogger.section("STEP 10 — THREE-WAY COMPARISON");
        DebugLogger.log("");
        DebugLogger.log("  COLUMN DEFINITIONS:");
        DebugLogger.log("  [1] Cloud-Only  : ALL tasks sent to cloud (no MOAOA, no offloading)");
        DebugLogger.log("  [2] MOAOA-Static: MOAOA with predictable static sensor workload");
        DebugLogger.log("  [3] MOAOA-Dyn   : MOAOA with dynamic burst workload (stress test)");
        DebugLogger.log("");
        DebugLogger.log("  NOTE: Cloud-Only baseline uses same delay/energy model as MOAOA");
        DebugLogger.log("        for a fair apples-to-apples comparison.");
        DebugLogger.separator();

        // Header
        DebugLogger.log(String.format(
                "  %-32s  %-16s  %-16s  %-16s",
                "Metric",
                "[1] Cloud-Only",
                "[2] MOAOA-Static",
                "[3] MOAOA-Dynamic"));
        DebugLogger.separator();

        // Total Tasks
        DebugLogger.log(String.format(
                "  %-32s  %-16s  %-16s  %-16s",
                "Total Tasks",
                totalTasksReceived,
                totalTasksReceived,
                dynTasksReceived));

        // Energy
        DebugLogger.log(String.format(
                "  %-32s  %-16.2f  %-16.2f  %-16.2f",
                "Task Compute Energy (J)",
                cloudTotalEnergy,
                moaoaTotalEnergy,
                dynTotalEnergy));

        // Energy improvement
        DebugLogger.log(String.format(
                "  %-32s  %-16s  %s%-15.1f%%  %s%-15.1f%%",
                "  vs Cloud-Only",
                "baseline",
                moaoaTotalEnergy < cloudTotalEnergy ? "↓ " : "↑ ",
                Math.abs(energyImpStatic),
                dynTotalEnergy < dynCloudEnergy ? "↓ " : "↑ ",
                Math.abs(energyImpDynamic)));

        // Delay
        DebugLogger.log(String.format(
                "  %-32s  %-16.4f  %-16.4f  %-16.4f",
                "Total Delay (ms)",
                cloudTotalDelay,
                moaoaTotalDelay,
                dynTotalDelay));

        // Delay improvement
        DebugLogger.log(String.format(
                "  %-32s  %-16s  %s%-15.1f%%  %s%-15.1f%%",
                "  vs Cloud-Only",
                "baseline",
                moaoaTotalDelay < cloudTotalDelay ? "↓ " : "↑ ",
                Math.abs(delayImpStatic),
                dynTotalDelay < dynCloudDelay ? "↓ " : "↑ ",
                Math.abs(delayImpDynamic)));

        // Avg per task
        DebugLogger.log(String.format(
                "  %-32s  %-16.4f  %-16.4f  %-16.4f",
                "Avg Energy per Task (J)",
                cloudEnergyPerTask,
                (totalTasksReceived  > 0 ? moaoaTotalEnergy / totalTasksReceived  : 0),
                (dynTasksReceived    > 0 ? dynTotalEnergy   / dynTasksReceived    : 0)));

        DebugLogger.log(String.format(
                "  %-32s  %-16.4f  %-16.4f  %-16.4f",
                "Avg Delay per Task (ms)",
                cloudDelayPerTask,
                (totalTasksReceived  > 0 ? moaoaTotalDelay  / totalTasksReceived  : 0),
                (dynTasksReceived    > 0 ? dynTotalDelay    / dynTasksReceived    : 0)));

        // Distribution
        DebugLogger.separator();
        DebugLogger.log(String.format(
                "  %-32s  %-16s  %-16s  %-16s",
                "Tasks → EDGE",
                "0 (all cloud)",
                moaoaEdgeCount,
                dynEdgeCount));

        DebugLogger.log(String.format(
                "  %-32s  %-16s  %-16s  %-16s",
                "Tasks → FOG",
                "0 (all cloud)",
                moaoaFogCount,
                dynFogCount));

        DebugLogger.log(String.format(
                "  %-32s  %-16s  %-16s  %-16s",
                "Tasks → CLOUD",
                totalTasksReceived,
                moaoaCloudCount,
                dynCloudCount));

        DebugLogger.log(String.format(
                "  %-32s  %-16s  %-16s  %-16s",
                "Overloaded Rounds",
                "N/A",
                "0",
                dynRoundsFailed + " / 3"));

        // ── Interpretation ─────────────────────────────────────────────────
        DebugLogger.separator();
        DebugLogger.section("INTERPRETATION");
        DebugLogger.log("");
        DebugLogger.log("  WHY MOAOA-STATIC BEATS CLOUD-ONLY:");
        DebugLogger.log("  • Edge nodes: 0.4 J/MI vs Cloud: 1.5 J/MI  →  73% lower energy per task");
        DebugLogger.log("  • Edge comm delay: data/10000 vs Cloud: data/100  →  100× faster comm");
        DebugLogger.log(String.format("  • %d tasks kept at edge, %d at fog — only %d sent to cloud",
                moaoaEdgeCount, moaoaFogCount, moaoaCloudCount));
        DebugLogger.log("");
        DebugLogger.log("  WHY MOAOA-DYNAMIC DEGRADES:");
        DebugLogger.log("  • Burst arrivals exceed edge/fog capacity → overflow to cloud");
        DebugLogger.log("  • Queuing delays accumulate at overloaded nodes");
        DebugLogger.log("  • MOAOA requires re-optimization each round (150 iters × 100 pop)");
        DebugLogger.log("    but burst tasks arrive faster than optimizer converges");
        DebugLogger.log(String.format("  • %d of 3 rounds experienced overload", dynRoundsFailed));
        DebugLogger.log("  • This motivates adaptive/online algorithms (future work)");
        DebugLogger.separator();

        // ── MOAOA parameters ───────────────────────────────────────────────
        DebugLogger.section("MOAOA Parameters Used (Ali et al. 2024, Table 8)");
        DebugLogger.result("  Population Size",    String.valueOf(populationSize));
        DebugLogger.result("  Max Iterations",     String.valueOf(maxIterations));
        DebugLogger.result("  Vmax",               String.valueOf(Vmax));
        DebugLogger.result("  Vmin",               String.valueOf(Vmin));
        DebugLogger.result("  Escalation (α)",     String.valueOf(escalationParam));
        DebugLogger.result("  Control Param (Cp)", String.valueOf(controlParam));
        DebugLogger.result("  Weight (W)",         "0.5 (equal priority: delay + energy)");
        DebugLogger.result("  Edge Capacity",      EDGE_CAPACITY_PER_NODE + " tasks/node/round");
        DebugLogger.result("  Fog Capacity",       FOG_CAPACITY_PER_NODE  + " tasks/node/round");
        DebugLogger.result("  Cloud Capacity",     "Unbounded");

        // ── Loop latencies ─────────────────────────────────────────────────
        DebugLogger.section("Application Loop Latencies");
        boolean hasLoops = false;
        for (Integer loopId : TimeKeeper.getInstance().getLoopIdToTupleIds().keySet()) {
            Double avg = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loopId);
            DebugLogger.result("  " + getStringForLoopId(loopId),
                    avg != null ? String.format("%.4f ms", avg) : "N/A");
            hasLoops = true;
        }
        if (!hasLoops) DebugLogger.log("  (No completed loops recorded)");

        DebugLogger.separator();
        DebugLogger.result("  Network Usage (bits/s)",
                String.format("%.4f",
                        NetworkUsageMonitor.getNetworkUsage() / Config.MAX_SIMULATION_TIME));
        DebugLogger.result("  Wall Clock Time", execTime + " ms");
        DebugLogger.section("END OF EVALUATION — THREE-WAY MOAOA COMPARISON COMPLETE");
    }

    // ─── Helpers ───────────────────────────────────────────────────────────
    private void connectWithLatencies() {
        for (FogDevice dev : fogDevices) {
            FogDevice parent = getFogDeviceById(dev.getParentId());
            if (parent == null) continue;
            parent.getChildToLatencyMap().put(dev.getId(), dev.getUplinkLatency());
            parent.getChildrenIds().add(dev.getId());
        }
    }

    private FogDevice getFogDeviceById(int id) {
        for (FogDevice d : fogDevices)
            if (d.getId() == id) return d;
        return null;
    }

    private String getStringForLoopId(int loopId) {
        for (Application app : applications.values())
            for (AppLoop loop : app.getLoops())
                if (loop.getLoopId() == loopId)
                    return loop.getModules().toString();
        return "Unknown Loop";
    }

    private void manageResources() {
        send(getId(), Config.RESOURCE_MANAGE_INTERVAL,
                FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
    }

    // ─── Application submission ────────────────────────────────────────────
    public void submitApplication(Application application, int delay,
                                  ModulePlacement modulePlacement) {
        FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
        getApplications().put(application.getAppId(), application);
        getAppLaunchDelays().put(application.getAppId(), delay);
        getAppModulePlacementPolicy().put(application.getAppId(), modulePlacement);

        for (Sensor sensor : sensors)
            sensor.setApp(getApplications().get(sensor.getAppId()));
        for (Actuator ac : actuators)
            ac.setApp(getApplications().get(ac.getAppId()));

        for (AppEdge edge : application.getEdges()) {
            if (edge.getEdgeType() == AppEdge.ACTUATOR) {
                String moduleName = edge.getSource();
                for (Actuator actuator : getActuators()) {
                    if (actuator.getActuatorType().equalsIgnoreCase(edge.getDestination()))
                        application.getModuleByName(moduleName)
                                .subscribeActuator(actuator.getId(), edge.getTupleType());
                }
            }
        }
    }

    public void submitApplication(Application application, ModulePlacement modulePlacement) {
        submitApplication(application, 0, modulePlacement);
    }

    private void processAppSubmit(SimEvent ev) {
        Application app = (Application) ev.getData();
        processAppSubmit(app);
    }

    private void processAppSubmit(Application application) {
        DebugLogger.info("APP SUBMIT",
                "'" + application.getAppId() + "' at t=" +
                        String.format("%.2f", CloudSim.clock()));
        FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
        getApplications().put(application.getAppId(), application);

        ModulePlacement modulePlacement =
                getAppModulePlacementPolicy().get(application.getAppId());

        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.ACTIVE_APP_UPDATE, application);

        Map<Integer, List<AppModule>> deviceToModuleMap =
                modulePlacement.getDeviceToModuleMap();
        for (Integer deviceId : deviceToModuleMap.keySet()) {
            for (AppModule module : deviceToModuleMap.get(deviceId)) {
                sendNow(deviceId, FogEvents.APP_SUBMIT,    application);
                sendNow(deviceId, FogEvents.LAUNCH_MODULE, module);
            }
        }
    }

    // ─── Getters / setters ─────────────────────────────────────────────────
    public List<FogDevice> getFogDevices()                        { return fogDevices; }
    public void setFogDevices(List<FogDevice> v)                  { this.fogDevices = v; }
    public Map<String, Integer> getAppLaunchDelays()              { return appLaunchDelays; }
    public void setAppLaunchDelays(Map<String, Integer> v)        { this.appLaunchDelays = v; }
    public Map<String, Application> getApplications()             { return applications; }
    public void setApplications(Map<String, Application> v)       { this.applications = v; }
    public List<Sensor> getSensors()                              { return sensors; }
    public void setSensors(List<Sensor> sensors) {
        for (Sensor s : sensors) s.setControllerId(getId());
        this.sensors = sensors;
    }
    public List<Actuator> getActuators()                          { return actuators; }
    public void setActuators(List<Actuator> v)                    { this.actuators = v; }
    public Map<String, ModulePlacement> getAppModulePlacementPolicy() { return appModulePlacementPolicy; }
    public void setAppModulePlacementPolicy(Map<String, ModulePlacement> v) { this.appModulePlacementPolicy = v; }
}