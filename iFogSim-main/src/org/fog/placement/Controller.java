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
 * Controller — Fog Controller / Fog Node (FC in paper terminology).
 *
 * Responsibilities:
 *   1. Receives tasks from sensor nodes via TUPLE_ARRIVAL.
 *   2. Runs MOAOA to find optimal task-to-node assignment.
 *   3. Sends tasks to chosen devices and reports metrics.
 *
 * MOAOA Parameters (from Ali et al., IEEE Access 2024, Table 8):
 *   Population = 100, MaxIter = 150, Vmax = 0.9, Vmin = 0.2
 *   Escalation α = 5, Control Cp = 0.5
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

    // ─── MOAOA Parameters (from paper Table 8) ────────────────────────────────
    private final int    populationSize      = 100;
    private final int    maxIterations       = 150;
    private final double Vmax               = 0.9;
    private final double Vmin               = 0.2;
    private final double escalationParam    = 5.0;   // α
    private final double controlParam       = 0.5;   // Cp

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

        for (FogDevice d : fogDevices) {
            d.setControllerId(getId());
        }
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

        send(getId(), Config.RESOURCE_MANAGE_INTERVAL, FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
        send(getId(), Config.MAX_SIMULATION_TIME, FogEvents.STOP_SIMULATION, null);

        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.RESOURCE_MGMT);

        // Schedule MOAOA once tasks have arrived (5 s buffer)
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
                break;
            default:
                break;
        }
    }

    @Override
    public void shutdownEntity() {}

    // ─── Task collection ──────────────────────────────────────────────────────

    /**
     * Collects tuples arriving from sensor nodes into pendingTasks queue.
     * Only accepts tuples that haven't already been assigned (userId != -1).
     */
    private void collectTask(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        if (tuple.getUserId() != -1) {
            pendingTasks.add(tuple);
            DebugLogger.info("TASK RECEIVED",
                    "Type=" + tuple.getTupleType()
                            + " | CPU=" + tuple.getCloudletLength()
                            + " | DataSize=" + tuple.getCloudletFileSize());
        }
    }

    // ─── MOAOA Core ───────────────────────────────────────────────────────────

    /**
     * MOAOA — Multi-Objective Arithmetic Optimization Algorithm.
     *
     * Implements the algorithm from Ali et al. (2024) with:
     *   - Binary offloading matrix [tasks × nodes]
     *   - MOA / MOP control functions (Eq. 1 and 18)
     *   - Exploration  : multiplication / division operators (Eq. 17)
     *   - Exploitation : addition / subtraction operators   (Eq. 19)
     *   - Combined fitness (Eq. 3) : W*Delay + (1-W)*Energy, W=0.5
     */
    private void runMOAOA() {

        if (pendingTasks.isEmpty()) {
            DebugLogger.info("MOAOA", "No tasks in queue — skipping optimisation.");
            return;
        }

        DebugLogger.section("STEP 7 — MOAOA Task Offloading Optimisation");

        int numTasks = pendingTasks.size();
        int numNodes = fogDevices.size();

        DebugLogger.info("MOAOA", "Tasks to schedule : " + numTasks);
        DebugLogger.info("MOAOA", "Available nodes    : " + numNodes);
        DebugLogger.info("MOAOA", "Population size    : " + populationSize);
        DebugLogger.info("MOAOA", "Max iterations     : " + maxIterations);
        DebugLogger.separator();

        // ── Pre-compute node properties ────────────────────────────────────
        double[] nodeMips   = new double[numNodes];
        String[] nodeNames  = new String[numNodes];
        int[]    nodeLevels = new int[numNodes];

        for (int i = 0; i < numNodes; i++) {
            FogDevice dev = fogDevices.get(i);
            nodeMips[i]   = dev.getHost().getTotalMips();
            nodeNames[i]  = dev.getName();
            nodeLevels[i] = dev.getLevel();
        }

        // ── Pre-compute task properties ────────────────────────────────────
        double[] taskLength   = new double[numTasks];
        double[] taskDataSize = new double[numTasks];

        for (int t = 0; t < numTasks; t++) {
            taskLength[t]   = pendingTasks.get(t).getCloudletLength();
            taskDataSize[t] = pendingTasks.get(t).getCloudletFileSize();
        }

        // ── Initialise population (random binary assignment matrix) ────────
        // Each solution[t] = index of node assigned to task t
        int[][] population  = new int[populationSize][numTasks];
        double[] fitnesses  = new double[populationSize];

        for (int i = 0; i < populationSize; i++) {
            for (int t = 0; t < numTasks; t++) {
                population[i][t] = (int) (Math.random() * numNodes);
            }
        }

        // ── Main iteration loop ────────────────────────────────────────────
        int    bestIdx     = 0;
        double bestFitness = Double.MAX_VALUE;

        DebugLogger.log("  Iteration Log (printed every 10 iterations):");
        DebugLogger.log(String.format("  %-10s %-10s %-10s %-14s", "Iteration", "MOA", "MOP", "BestFitness"));
        DebugLogger.separator();

        for (int iter = 1; iter <= maxIterations; iter++) {

            // MOA  (Eq. 1) — linearly increases Vmin → Vmax
            double moa = Vmin + (iter - 1) * ((Vmax - Vmin) / maxIterations);

            // MOP (Eq. 18) — decreases 1 → 0 with escalation
            double mop = 1.0 - Math.pow((double) iter / maxIterations,
                    1.0 / escalationParam);

            // ── Evaluate all solutions ─────────────────────────────────────
            for (int i = 0; i < populationSize; i++) {
                fitnesses[i] = computeFitness(population[i], numTasks, numNodes,
                        taskLength, taskDataSize, nodeMips, nodeLevels);
                if (fitnesses[i] < bestFitness) {
                    bestFitness = fitnesses[i];
                    bestIdx     = i;
                }
            }

            DebugLogger.iterLog(iter, moa, mop, bestFitness);

            // ── Update positions ───────────────────────────────────────────
            int[] best = population[bestIdx].clone();

            for (int i = 0; i < populationSize; i++) {
                double r1 = Math.random();
                double r2 = Math.random();
                double r3 = Math.random();

                int[] newSol = new int[numTasks];

                for (int t = 0; t < numTasks; t++) {
                    int nodeIdx = population[i][t];
                    int bestNode = best[t];

                    int updated;
                    if (r1 > moa) {
                        // ── Exploration : × or ÷  (Eq. 17) ───────────────
                        double step = mop * (controlParam * (2 * Math.random() - 1));
                        if (r2 <= 0.5) {
                            // Division operator
                            updated = (int) Math.round(bestNode / (mop + 1e-9)
                                    * controlParam + bestNode);
                        } else {
                            // Multiplication operator
                            updated = (int) Math.round(bestNode * mop
                                    * controlParam + bestNode);
                        }
                    } else {
                        // ── Exploitation : + or −  (Eq. 19) ──────────────
                        double step = mop * controlParam * (2 * Math.random() - 1);
                        if (r3 > 0.5) {
                            // Addition operator
                            updated = (int) Math.round(bestNode + step * numNodes);
                        } else {
                            // Subtraction operator
                            updated = (int) Math.round(bestNode - step * numNodes);
                        }
                    }

                    // Clamp to valid node range
                    updated = Math.max(0, Math.min(numNodes - 1, updated));
                    newSol[t] = updated;
                }

                // Greedy acceptance — replace if new solution is better
                double newFit = computeFitness(newSol, numTasks, numNodes,
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

        // ── Extract best solution ──────────────────────────────────────────
        int[] bestSolution = population[bestIdx];

        // ── Print per-task assignment ──────────────────────────────────────
        DebugLogger.separator();
        DebugLogger.section("STEP 8 — Per-Task Assignment Results");

        DebugLogger.log(String.format("  %-8s %-16s %-22s %-10s %-10s %-10s",
                "Task#", "Type", "Assigned Node", "Level", "Delay", "Energy"));
        DebugLogger.separator();

        double totalDelay  = 0;
        double totalEnergy = 0;
        int cloudCount = 0, fogCount = 0, edgeCount = 0;

        for (int t = 0; t < numTasks; t++) {
            Tuple tuple   = pendingTasks.get(t);
            int nodeIdx   = bestSolution[t];
            FogDevice dev = fogDevices.get(nodeIdx);

            double delay  = computeDelay(taskLength[t], taskDataSize[t],
                    nodeMips[nodeIdx], nodeLevels[nodeIdx]);
            double energy = computeEnergy(taskLength[t], nodeLevels[nodeIdx]);

            totalDelay  += delay;
            totalEnergy += energy;

            // Count by tier
            if (nodeLevels[nodeIdx] == 0)      cloudCount++;
            else if (nodeLevels[nodeIdx] == 1)  fogCount++;
            else                                edgeCount++;

            DebugLogger.log(String.format(
                    "  %-8d %-16s %-22s %-10d %-10.4f %-10.4f",
                    t + 1,
                    tuple.getTupleType(),
                    dev.getName(),
                    nodeLevels[nodeIdx],
                    delay,
                    energy));

            // Mark tuple as assigned and dispatch
            if (tuple.getDestModuleName() == null) {
                tuple.setDestModuleName(dev.getName());
                tuple.setUserId(-1);
                sendNow(dev.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
            }
        }

        // ── Per-device energy summary ──────────────────────────────────────
        DebugLogger.section("STEP 9 — Per-Device Energy & Cost Summary");
        DebugLogger.log(String.format("  %-25s %-10s %-14s %-12s",
                "Device", "Level", "Energy (J)", "Cost ($)"));
        DebugLogger.separator();
        for (FogDevice dev : fogDevices) {
            DebugLogger.log(String.format("  %-25s %-10d %-14.4f %-12.6f",
                    dev.getName(),
                    dev.getLevel(),
                    dev.getEnergyConsumption(),
                    dev.getTotalCost()));
        }

        // ── MOAOA Summary ─────────────────────────────────────────────────
        DebugLogger.section("STEP 10 — MOAOA Optimisation Summary");
        DebugLogger.result("Total Tasks Scheduled",    String.valueOf(numTasks));
        DebugLogger.result("Best Fitness (Combined)",  String.format("%.6f", bestFitness));
        DebugLogger.result("Total Delay  (ms)",        String.format("%.4f", totalDelay));
        DebugLogger.result("Total Energy (J)",         String.format("%.4f", totalEnergy));
        DebugLogger.separator();
        DebugLogger.result("Tasks → Cloud  (level 0)", String.valueOf(cloudCount));
        DebugLogger.result("Tasks → Fog    (level 1)", String.valueOf(fogCount));
        DebugLogger.result("Tasks → Edge   (level 3)", String.valueOf(edgeCount));
        DebugLogger.separator();
        DebugLogger.result("Network Usage (bits/s)",
                String.format("%.4f",
                        NetworkUsageMonitor.getNetworkUsage() / Config.MAX_SIMULATION_TIME));

        pendingTasks.clear();
    }

    // ─── Fitness / delay / energy models (from paper Eq. 3-15) ──────────────

    /**
     * Combined fitness (Eq. 3) :  W * Delay + (1-W) * Energy,  W = 0.5
     */
    private double computeFitness(int[] solution, int numTasks, int numNodes,
                                  double[] taskLength, double[] taskDataSize,
                                  double[] nodeMips, int[] nodeLevels) {
        double totalDelay  = 0;
        double totalEnergy = 0;

        for (int t = 0; t < numTasks; t++) {
            int n = solution[t];
            totalDelay  += computeDelay(taskLength[t], taskDataSize[t], nodeMips[n], nodeLevels[n]);
            totalEnergy += computeEnergy(taskLength[t], nodeLevels[n]);
        }
        return 0.5 * totalDelay + 0.5 * totalEnergy;
    }

    /**
     * Delay model combining computation + communication delay.
     * Approximates Eq. 5-9 from paper.
     *
     * Edge  (level 3) : fast compute, near-zero comm cost
     * Fog   (level 1) : medium compute, small comm
     * Cloud (level 0) : very fast compute, higher comm (data must travel far)
     */
    private double computeDelay(double length, double dataSize,
                                double mips, int level) {
        double compDelay = length / mips;

        double commDelay;
        switch (level) {
            case 0:  commDelay = dataSize / 100.0;  break;  // cloud — long hop
            case 1:  commDelay = dataSize / 1000.0; break;  // fog   — medium
            default: commDelay = dataSize / 10000.0; break; // edge  — very close
        }
        return compDelay + commDelay;
    }

    /**
     * Energy model.  Approximates Eq. 10-15 from paper.
     *
     * Cloud : high compute power → higher energy per MI
     * Fog   : moderate
     * Edge  : lowest
     */
    private double computeEnergy(double length, int level) {
        double energyPerMI;
        switch (level) {
            case 0:  energyPerMI = 1.5;  break; // cloud   (paper: Eq. 13)
            case 1:  energyPerMI = 0.6;  break; // fog     (paper: Eq. 12)
            default: energyPerMI = 0.4;  break; // edge    (paper: Eq. 10)
        }
        return length * energyPerMI;
    }

    // ─── Final results printer ────────────────────────────────────────────────

    private void printFinalResults() {

        long execTime = Calendar.getInstance().getTimeInMillis()
                - TimeKeeper.getInstance().getSimulationStartTime();

        DebugLogger.section("FINAL SIMULATION RESULTS — Evaluation 2");

        // Application loop latency
        DebugLogger.log("  Application Loop Latencies:");
        DebugLogger.separator();
        for (Integer loopId : TimeKeeper.getInstance().getLoopIdToTupleIds().keySet()) {
            Double avg = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loopId);
            String loopStr = getStringForLoopId(loopId);
            DebugLogger.result("  " + loopStr,
                    avg != null ? String.format("%.4f ms", avg) : "N/A");
        }

        // CPU execution time per tuple type
        DebugLogger.separator();
        DebugLogger.log("  Average CPU Execution Time per Tuple Type:");
        DebugLogger.separator();
        for (String tupleType : TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().keySet()) {
            DebugLogger.result("  " + tupleType,
                    String.format("%.6f s",
                            TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().get(tupleType)));
        }

        // Device energy and cost
        DebugLogger.separator();
        DebugLogger.log("  Per-Device Energy Consumption & Cost:");
        DebugLogger.separator();
        DebugLogger.log(String.format("  %-25s %-12s %-14s %-12s",
                "Device", "Level", "Energy (J)", "Cost ($)"));
        DebugLogger.separator();
        double totalEnergy = 0;
        double totalCost   = 0;
        for (FogDevice dev : fogDevices) {
            DebugLogger.log(String.format("  %-25s %-12d %-14.4f %-12.6f",
                    dev.getName(),
                    dev.getLevel(),
                    dev.getEnergyConsumption(),
                    dev.getTotalCost()));
            totalEnergy += dev.getEnergyConsumption();
            totalCost   += dev.getTotalCost();
        }
        DebugLogger.separator();
        DebugLogger.result("  TOTAL Energy Consumed (J)", String.format("%.4f", totalEnergy));
        DebugLogger.result("  TOTAL Cost ($)",            String.format("%.6f", totalCost));

        // Network usage
        DebugLogger.separator();
        DebugLogger.result("  Network Usage (bits/s)",
                String.format("%.4f",
                        NetworkUsageMonitor.getNetworkUsage() / Config.MAX_SIMULATION_TIME));

        // Wall clock time
        DebugLogger.separator();
        DebugLogger.result("  Wall Clock Execution Time", execTime + " ms");
        DebugLogger.section("END OF EVALUATION 2");
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void connectWithLatencies() {
        for (FogDevice dev : fogDevices) {
            FogDevice parent = getFogDeviceById(dev.getParentId());
            if (parent == null) continue;
            double latency = dev.getUplinkLatency();
            parent.getChildToLatencyMap().put(dev.getId(), latency);
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
        send(getId(), Config.RESOURCE_MANAGE_INTERVAL, FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
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

    public List<FogDevice> getFogDevices()                       { return fogDevices; }
    public void setFogDevices(List<FogDevice> fogDevices)        { this.fogDevices = fogDevices; }

    public Map<String, Integer> getAppLaunchDelays()             { return appLaunchDelays; }
    public void setAppLaunchDelays(Map<String, Integer> d)       { this.appLaunchDelays = d; }

    public Map<String, Application> getApplications()            { return applications; }
    public void setApplications(Map<String, Application> a)      { this.applications = a; }

    public List<Sensor> getSensors()                             { return sensors; }
    public void setSensors(List<Sensor> sensors) {
        for (Sensor s : sensors) s.setControllerId(getId());
        this.sensors = sensors;
    }

    public List<Actuator> getActuators()                         { return actuators; }
    public void setActuators(List<Actuator> actuators)           { this.actuators = actuators; }

    public Map<String, ModulePlacement> getAppModulePlacementPolicy() {
        return appModulePlacementPolicy;
    }
    public void setAppModulePlacementPolicy(Map<String, ModulePlacement> p) {
        this.appModulePlacementPolicy = p;
    }
}
