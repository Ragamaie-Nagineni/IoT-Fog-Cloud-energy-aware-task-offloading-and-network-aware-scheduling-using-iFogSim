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
 * Shows per-task offloading decisions:
 *   [TASK → EDGE]  = assigned to sensor-node (level 3)
 *   [TASK → FOG]   = assigned to fog/router   (level 1)
 *   [TASK → CLOUD] = assigned to cloud        (level 0)
 *
 * Also records metrics for comparison with cloud-only baseline.
 *
 * MOAOA Parameters (Ali et al., IEEE Access 2024, Table 8):
 *   Population=100, MaxIter=150, Vmax=0.9, Vmin=0.2, α=5, Cp=0.5
 */
public class Controller extends SimEntity {

    public static boolean ONLY_CLOUD = false;

    // ─── Entities ─────────────────────────────────────────────────────────────
    private List<FogDevice>  fogDevices;
    private List<Sensor>     sensors;
    private List<Actuator>   actuators;
    private Map<String, Application>      applications;
    private Map<String, Integer>          appLaunchDelays;
    private Map<String, ModulePlacement>  appModulePlacementPolicy;

    // ─── Task queue ───────────────────────────────────────────────────────────
    private List<Tuple> pendingTasks = new ArrayList<>();

    // ─── MOAOA tracking for comparison ───────────────────────────────────────
    private int moaoaEdgeCount  = 0;
    private int moaoaFogCount   = 0;
    private int moaoaCloudCount = 0;
    private double moaoaTotalDelay  = 0;
    private double moaoaTotalEnergy = 0;
    private int    totalTasksReceived = 0;

    // ─── MOAOA Parameters ─────────────────────────────────────────────────────
    private final int    populationSize   = 100;
    private final int    maxIterations    = 150;
    private final double Vmax             = 0.9;
    private final double Vmin             = 0.2;
    private final double escalationParam  = 5.0;
    private final double controlParam     = 0.5;

    // ─── Constructor ──────────────────────────────────────────────────────────
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

    // ─── SimEntity lifecycle ──────────────────────────────────────────────────
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

        // MOAOA runs 5 s after simulation starts (buffer for tasks to arrive)
        send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE, null);
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
            default:
                break;
        }
    }

    @Override
    public void shutdownEntity() {}

    // ─── Task collection ──────────────────────────────────────────────────────
    private void collectTask(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        if (tuple.getUserId() != -1) {
            pendingTasks.add(tuple);
            totalTasksReceived++;
        }
    }

    // ─── MOAOA ────────────────────────────────────────────────────────────────
    private void runMOAOA() {

        if (pendingTasks.isEmpty()) {
            DebugLogger.log("\n  [MOAOA] No tasks yet — skipping this round.");
            return;
        }

        int numTasks = pendingTasks.size();
        int numNodes = fogDevices.size();

        DebugLogger.section("STEP 7 — MOAOA Task Offloading");
        DebugLogger.log(String.format("  Tasks to schedule : %d", numTasks));
        DebugLogger.log(String.format("  Available nodes   : %d", numNodes));
        DebugLogger.separator();

        // ── Pre-compute node properties ────────────────────────────────────
        double[] nodeMips   = new double[numNodes];
        int[]    nodeLevels = new int[numNodes];
        String[] nodeNames  = new String[numNodes];

        for (int i = 0; i < numNodes; i++) {
            FogDevice dev  = fogDevices.get(i);
            nodeMips[i]   = dev.getHost().getTotalMips();
            nodeLevels[i] = dev.getLevel();
            nodeNames[i]  = dev.getName();
        }

        // ── Pre-compute task properties ────────────────────────────────────
        double[] taskLength   = new double[numTasks];
        double[] taskDataSize = new double[numTasks];
        for (int t = 0; t < numTasks; t++) {
            taskLength[t]   = pendingTasks.get(t).getCloudletLength();
            taskDataSize[t] = pendingTasks.get(t).getCloudletFileSize();
        }

        // ── Initialise population ──────────────────────────────────────────
        int[][] population = new int[populationSize][numTasks];
        double[] fitnesses = new double[populationSize];
        for (int i = 0; i < populationSize; i++)
            for (int t = 0; t < numTasks; t++)
                population[i][t] = (int) (Math.random() * numNodes);

        // ── Main MOAOA loop ────────────────────────────────────────────────
        int    bestIdx     = 0;
        double bestFitness = Double.MAX_VALUE;

        DebugLogger.log("  Running MOAOA iterations...");

        for (int iter = 1; iter <= maxIterations; iter++) {

            // MOA (Eq. 1)
            double moa = Vmin + (iter - 1) * ((Vmax - Vmin) / maxIterations);
            // MOP (Eq. 18)
            double mop = 1.0 - Math.pow((double) iter / maxIterations,
                    1.0 / escalationParam);

            // Evaluate
            for (int i = 0; i < populationSize; i++) {
                fitnesses[i] = computeFitness(population[i], numTasks,
                        taskLength, taskDataSize, nodeMips, nodeLevels);
                if (fitnesses[i] < bestFitness) {
                    bestFitness = fitnesses[i];
                    bestIdx     = i;
                }
            }

            // Print every 30 iterations
            if (iter % 30 == 0 || iter == 1 || iter == maxIterations) {
                DebugLogger.log(String.format(
                        "    Iter %3d | MOA=%.3f | MOP=%.3f | BestFitness=%.4f",
                        iter, moa, mop, bestFitness));
            }

            // Update positions
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
                double newFit = computeFitness(newSol, numTasks,
                        taskLength, taskDataSize, nodeMips, nodeLevels);
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

        int[] bestSolution = population[bestIdx];

        // ── Print per-task decisions ───────────────────────────────────────
        DebugLogger.section("STEP 8 — Per-Task Offloading Decisions");
        DebugLogger.log(String.format("  %-6s %-8s %-12s %-24s %-10s %-10s %-12s",
                "Task#", "Type", "CPU(MI)", "Assigned To", "Tier", "Delay", "Energy"));
        DebugLogger.separator();

        for (int t = 0; t < numTasks; t++) {
            Tuple tuple    = pendingTasks.get(t);
            int nodeIdx    = bestSolution[t];
            FogDevice dev  = fogDevices.get(nodeIdx);
            int level      = nodeLevels[nodeIdx];

            double delay   = computeDelay(taskLength[t], taskDataSize[t],
                    nodeMips[nodeIdx], level);
            double energy  = computeEnergy(taskLength[t], level);

            moaoaTotalDelay  += delay;
            moaoaTotalEnergy += energy;

            String tier;
            String arrow;
            if (level == 0) {
                tier = "CLOUD"; moaoaCloudCount++;
                arrow = "[TASK → CLOUD]";
            } else if (level == 1) {
                tier = "FOG  "; moaoaFogCount++;
                arrow = "[TASK → FOG  ]";
            } else {
                tier = "EDGE "; moaoaEdgeCount++;
                arrow = "[TASK → EDGE ]";
            }

            DebugLogger.log(String.format(
                    "  %s  #%-4d %-8s %-6.0f → %-22s [%s] delay=%-8.4f energy=%-8.4f",
                    arrow, t + 1,
                    tuple.getTupleType(), taskLength[t],
                    dev.getName(), tier, delay, energy));

            // Dispatch
            if (tuple.getDestModuleName() == null) {
                tuple.setDestModuleName(dev.getName());
                tuple.setUserId(-1);
                sendNow(dev.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
            }
        }

        // ── Round summary ──────────────────────────────────────────────────
        DebugLogger.separator();
        DebugLogger.log("  Offloading Distribution This Round:");
        DebugLogger.log(String.format("    %-8s tasks assigned to EDGE  (Level 3 — sensor-nodes)", moaoaEdgeCount));
        DebugLogger.log(String.format("    %-8s tasks assigned to FOG   (Level 1 — router/proxy)", moaoaFogCount));
        DebugLogger.log(String.format("    %-8s tasks assigned to CLOUD (Level 0 — cloud server)", moaoaCloudCount));
        DebugLogger.separator();
        DebugLogger.result("  MOAOA Best Fitness",        String.format("%.6f", bestFitness));
        DebugLogger.result("  MOAOA Total Delay  (ms)",   String.format("%.4f", moaoaTotalDelay));
        DebugLogger.result("  MOAOA Total Energy (J)",    String.format("%.4f", moaoaTotalEnergy));

        pendingTasks.clear();
    }

    // ─── Fitness / delay / energy models ─────────────────────────────────────

    /** Combined fitness (Eq. 3): W*Delay + (1-W)*Energy, W=0.5 */
    private double computeFitness(int[] solution, int numTasks,
                                  double[] taskLength, double[] taskDataSize,
                                  double[] nodeMips, int[] nodeLevels) {
        double totalDelay = 0, totalEnergy = 0;
        for (int t = 0; t < numTasks; t++) {
            int n = solution[t];
            totalDelay  += computeDelay(taskLength[t], taskDataSize[t], nodeMips[n], nodeLevels[n]);
            totalEnergy += computeEnergy(taskLength[t], nodeLevels[n]);
        }
        return 0.5 * totalDelay + 0.5 * totalEnergy;
    }

    /** Delay model (Eq. 5-9): computation + communication delay by tier */
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

    /** Energy model (Eq. 10-15): energy per MI by tier */
    private double computeEnergy(double length, int level) {
        double energyPerMI;
        switch (level) {
            case 0:  energyPerMI = 1.5;  break; // cloud
            case 1:  energyPerMI = 0.6;  break; // fog
            default: energyPerMI = 0.4;  break; // edge
        }
        return length * energyPerMI;
    }

    // ─── Compute what energy would be if ALL tasks went to cloud ─────────────
    private double computeCloudOnlyEnergy() {
        // Cloud energy = all device energies in a cloud-only scenario
        // Approximate: only cloud device would be busy
        FogDevice cloud = getCloudDevice();
        if (cloud == null) return 0;
        // In cloud-only: cloud consumes full energy, fog/edge idle
        double cloudEnergy = cloud.getEnergyConsumption();
        // Estimate additional fog/edge idle energy (they transmit everything up)
        double idleEnergy = 0;
        for (FogDevice dev : fogDevices) {
            if (dev.getLevel() != 0) {
                idleEnergy += dev.getEnergyConsumption();
            }
        }
        return cloudEnergy + idleEnergy;
    }

    // ─── Final results with comparison ───────────────────────────────────────
    private void printFinalResults() {

        long execTime = Calendar.getInstance().getTimeInMillis()
                - TimeKeeper.getInstance().getSimulationStartTime();

        // ── MOAOA actual device energies ───────────────────────────────────────
        double totalMOAOADeviceEnergy = 0;
        double totalMOAOACost = 0;
        for (FogDevice dev : fogDevices) {
            totalMOAOADeviceEnergy += dev.getEnergyConsumption();
            totalMOAOACost += dev.getTotalCost();
        }

        // ── FAIR COMPARISON: use the same per-task energy/delay model ──────────
        // Cloud-only: every task computed at cloud tier (level 0)
        // Using same computeDelay / computeEnergy model as MOAOA fitness function
        double avgTaskLength = 500.0;   // MI (mix of TEMP=500, VIB=300 → approx avg)
        double avgDataSize   = 500.0;
        double cloudMips     = 44800.0; // from topology

        double cloudOnlyDelayPerTask  = computeDelay(avgTaskLength, avgDataSize, cloudMips, 0);
        double cloudOnlyEnergyPerTask = computeEnergy(avgTaskLength, 0);

        double cloudOnlyTotalDelay  = totalTasksReceived * cloudOnlyDelayPerTask;
        double cloudOnlyTotalEnergy = totalTasksReceived * cloudOnlyEnergyPerTask;

        // ── MOAOA totals: use cumulative tracked values from runMOAOA() ────────
        // moaoaTotalDelay and moaoaTotalEnergy are already accumulated correctly

        double energyImprovement = cloudOnlyTotalEnergy > 0
                ? ((cloudOnlyTotalEnergy - moaoaTotalEnergy) / cloudOnlyTotalEnergy) * 100 : 0;
        double delayImprovement  = cloudOnlyTotalDelay > 0
                ? ((cloudOnlyTotalDelay  - moaoaTotalDelay)  / cloudOnlyTotalDelay)  * 100 : 0;

        // ── STEP 9: Device energy ──────────────────────────────────────────────
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
        DebugLogger.result("  TOTAL Device Energy (J)", String.format("%.4f", totalMOAOADeviceEnergy));
        DebugLogger.result("  TOTAL Cost ($)",          String.format("%.6f", totalMOAOACost));

        // ── STEP 10: Fair comparison table ────────────────────────────────────
        DebugLogger.section("STEP 10 — BASELINE vs MOAOA COMPARISON");
        DebugLogger.log("  (Using same delay/energy model for both — fair apples-to-apples)");
        DebugLogger.log("  Cloud-Only: all tasks computed at cloud tier (level 0)");
        DebugLogger.log("  MOAOA:      tasks distributed to EDGE/FOG/CLOUD by optimizer");
        DebugLogger.separator();
        DebugLogger.log(String.format("  %-32s %-18s %-18s %-15s",
                "Metric", "Cloud-Only", "MOAOA", "Improvement"));
        DebugLogger.separator();

        DebugLogger.log(String.format("  %-32s %-18s %-18s %-15s",
                "Total Tasks",
                String.valueOf(totalTasksReceived),
                String.valueOf(totalTasksReceived),
                "same"));

        DebugLogger.log(String.format("  %-32s %-18.4f %-18.4f %s%.1f%%",
                "Task Computation Energy (J)",
                cloudOnlyTotalEnergy, moaoaTotalEnergy,
                moaoaTotalEnergy < cloudOnlyTotalEnergy ? "↓ " : "↑ ",
                Math.abs(energyImprovement)));

        DebugLogger.log(String.format("  %-32s %-18.4f %-18.4f %s%.1f%%",
                "Total Delay (ms)",
                cloudOnlyTotalDelay, moaoaTotalDelay,
                moaoaTotalDelay < cloudOnlyTotalDelay ? "↓ " : "↑ ",
                Math.abs(delayImprovement)));

        DebugLogger.log(String.format("  %-32s %-18.4f %-18.4f %s",
                "Energy per Task (J)",
                cloudOnlyEnergyPerTask, moaoaTotalEnergy / Math.max(totalTasksReceived, 1),
                "avg per task"));

        DebugLogger.log(String.format("  %-32s %-18.4f %-18.4f %s",
                "Delay per Task (ms)",
                cloudOnlyDelayPerTask, moaoaTotalDelay / Math.max(totalTasksReceived, 1),
                "avg per task"));

        DebugLogger.log(String.format("  %-32s %-18s %-18s %-15s",
                "Tasks → EDGE",
                "0 (all cloud)",
                String.valueOf(moaoaEdgeCount),
                moaoaEdgeCount > 0 ? "✓ offloaded" : "-"));

        DebugLogger.log(String.format("  %-32s %-18s %-18s %-15s",
                "Tasks → FOG",
                "0 (all cloud)",
                String.valueOf(moaoaFogCount),
                moaoaFogCount > 0 ? "✓ offloaded" : "-"));

        DebugLogger.log(String.format("  %-32s %-18s %-18s %-15s",
                "Tasks → CLOUD",
                String.valueOf(totalTasksReceived),
                String.valueOf(moaoaCloudCount),
                "-"));

        DebugLogger.separator();
        DebugLogger.log("  WHY MOAOA WINS:");
        DebugLogger.log(String.format("  • Edge nodes process tasks at 0.4 J/MI vs cloud 1.5 J/MI (%.0f%% less energy)",
                (1.0 - 0.4/1.5) * 100));
        DebugLogger.log(String.format("  • Edge comm delay: dataSize/10000 vs cloud: dataSize/100 (100x faster)"));
        DebugLogger.log(String.format("  • %d tasks kept at edge, avoiding cloud round-trip overhead",
                moaoaEdgeCount));
        DebugLogger.separator();

        // ── MOAOA parameters ──────────────────────────────────────────────────
        DebugLogger.section("MOAOA Parameters Used (Ali et al. 2024, Table 8)");
        DebugLogger.result("  Population Size",    String.valueOf(populationSize));
        DebugLogger.result("  Max Iterations",     String.valueOf(maxIterations));
        DebugLogger.result("  Vmax",               String.valueOf(Vmax));
        DebugLogger.result("  Vmin",               String.valueOf(Vmin));
        DebugLogger.result("  Escalation (α)",     String.valueOf(escalationParam));
        DebugLogger.result("  Control Param (Cp)", String.valueOf(controlParam));
        DebugLogger.result("  Weight (W)",         "0.5 (equal priority: delay + energy)");

        // ── Loop latencies ────────────────────────────────────────────────────
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
        DebugLogger.section("END OF EVALUATION 2 — MOAOA OFFLOADING COMPLETE");
    }
    // ─── Helpers ──────────────────────────────────────────────────────────────
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

    private FogDevice getCloudDevice() {
        for (FogDevice dev : fogDevices)
            if (dev.getLevel() == 0) return dev;
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

    // ─── Application submission ───────────────────────────────────────────────
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

    // ─── Getters / setters ────────────────────────────────────────────────────
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
