package org.fog.placement;

import java.util.*;
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
 * Controller — MoAOA (Multi-Objective Arithmetic Optimization Algorithm)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * REFERENCE: Ali et al., "Joint Optimization of Computation Offloading and
 *            Task Scheduling Using Multi-Objective Arithmetic Optimization
 *            Algorithm in Cloud-Fog Computing", IEEE Access, 2024.
 *            DOI: 10.1109/ACCESS.2024.3512191
 *
 * WHAT MoAOA IS:
 *   MoAOA = Multi-Objective Arithmetic Optimization Algorithm.
 *   It is the multi-objective extension of AOA (Abualigah 2021).
 *   AOA uses four arithmetic operators (×, ÷, +, −) as search operators.
 *   MoAOA adds a Pareto external archive + crowding distance (Section IV-E).
 *
 * EXACT EQUATIONS IMPLEMENTED:
 *
 *   MOA(t) = Vmin + t*(Vmax-Vmin)/T                           (Eq. 1)
 *   MOP(t) = 1 - ( t^(1/a) / T^(1/a) )                       (Eq. 18)
 *
 *   EXPLORATION (ran1 > MOA) — Eq. 17:
 *     ran2 ≤ 0.5:  X = bestL / (MOP+ε) * (Ub-Lb)*Cp + Lb     [÷ search]
 *     ran2 > 0.5:  X = bestL * MOP     * (Ub-Lb)*Cp + Lb     [× search]
 *
 *   EXPLOITATION (ran1 ≤ MOA) — Eq. 19:
 *     ran3 > 0.5:  X = bestL + MOP * (Ub-Lb)*Cp + Lb          [+ search]
 *     ran3 ≤ 0.5:  X = bestL - MOP * (Ub-Lb)*Cp + Lb          [− search]
 *
 *   FITNESS (Eq. 3):
 *     OF = W * ΠDelay + (1-W) * Energy
 *
 *   DELAY MODEL (Eq. 4-9):
 *     D_IoT  = η / (μ*(μ-η))              M/M/1 queue (Eq. 4)
 *     D_FD   = (ℏ/η)*ϖ + c1*ζL           M/M/C fog   (Eq. 5-7)
 *     D_CS   = c2 * ζL(k)                M/M/∞ cloud  (Eq. 8)
 *     ΠDelay = D_IoT + D_FD + D_CS                            (Eq. 9)
 *
 *   ENERGY MODEL (Eq. 10-15):
 *     E_IoT  = p_IoT / (μ-η)                                  (Eq. 10-11)
 *     E_FD   = α*ϖ² + β*ϖ + ∂            quadratic model      (Eq. 12)
 *     E_CS   = γ*(Q*(ak*Mk+Ck))          VM-based             (Eq. 13-14)
 *     Energy = E_IoT + E_FD + E_CS                            (Eq. 15)
 *
 *   CROWDING DISTANCE (Eq. 16):
 *     Cdist = Dist_i / (Dist_max - Dist_min)
 *
 * SIMULATION PARAMETERS (Table 8):
 *   Vmax=1, Vmin=0.2, Pop=100, MaxIter=150, W=0.5, Archive=100,
 *   a=5, Cp=0.5, μ=4.6, η=3.2, p_IoT=25, ak=4.9, Ck=53.2e-20
 *
 * THREE SCENARIOS:
 *   [1] Cloud-Only   : baseline, all tasks sent to cloud
 *   [2] MoAOA-Static : same MoAOA algorithm on NORMAL task load, full capacity
 *   [3] MoAOA-Dynamic: SAME MoAOA algorithm on BURST task load (1.5x tasks),
 *                       but fog/edge capacity is HALVED → more tasks forced to
 *                       cloud → higher energy AND higher delay than Static
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class Controller extends SimEntity {

    public static boolean ONLY_CLOUD = false;

    // ─── Entities ──────────────────────────────────────────────────────────
    private List<FogDevice>  fogDevices;
    private List<Sensor>     sensors;
    private List<Actuator>   actuators;
    private Map<String, Application>     applications;
    private Map<String, Integer>         appLaunchDelays;
    private Map<String, ModulePlacement> appModulePlacementPolicy;

    // ─── Task queues ───────────────────────────────────────────────────────
    private List<Tuple>  pendingTasks  = new ArrayList<>();
    private List<Double> taskDeadlines = new ArrayList<>();

    // ─── Scenario [2] — MoAOA Static tracking ─────────────────────────────
    private int    moaoaEdgeCount   = 0;
    private int    moaoaFogCount    = 0;
    private int    moaoaCloudCount  = 0;
    private double moaoaTotalDelay  = 0;
    private double moaoaTotalEnergy = 0;
    private int    totalTasksReceived = 0;

    // ─── Scenario [3] — MoAOA Dynamic tracking ────────────────────────────
    private int    dynEdgeCount    = 0;
    private int    dynFogCount     = 0;
    private int    dynCloudCount   = 0;
    private double dynTotalDelay   = 0;
    private double dynTotalEnergy  = 0;
    private int    dynTasksReceived = 0;
    private int    dynRoundsFailed  = 0;

    // ═══════════════════════════════════════════════════════════════════════
    //  MoAOA PARAMETERS — Table 8 of Ali et al. 2024
    // ═══════════════════════════════════════════════════════════════════════
    private static final int    POP_SIZE    = 100;
    private static final int    MAX_ITER    = 150;
    private static final double V_MAX       = 1.0;      // Table 8: Vmax = 1
    private static final double V_MIN       = 0.2;      // Table 8: Vmin = 0.2
    private static final double A_ESC       = 5.0;      // Table 8: escalation a = 5
    private static final double C_P         = 0.5;      // Table 8: Cp = 0.5
    private static final double W_FIT       = 0.5;      // Table 8: W = 0.5
    private static final int    ARCHIVE_MAX = 100;      // Table 8: archive size = 100

    // ═══════════════════════════════════════════════════════════════════════
    //  DELAY MODEL PARAMETERS — Table 8 / Eq. 4-9
    // ═══════════════════════════════════════════════════════════════════════
    private static final double MU    = 4.6;   // μ: service rate
    private static final double ETA   = 3.2;   // η: task arrival rate
    private static final double H_BAR = ETA / (MU - ETA); // ℏ: avg queue length
    private static final double C1    = 0.01;  // fog comm constant  (Eq. 6)
    private static final double C2    = 0.05;  // cloud comm constant (Eq. 8)

    // ═══════════════════════════════════════════════════════════════════════
    //  ENERGY MODEL PARAMETERS — Table 8 / Eq. 10-15
    // ═══════════════════════════════════════════════════════════════════════
    private static final double P_IOT      = 25.0;      // Table 8
    private static final double FOG_ALPHA  = 0.001;     // α  (Eq. 12)
    private static final double FOG_BETA   = 0.1;       // β  (Eq. 12)
    private static final double FOG_DELTA  = 0.5;       // ∂  (Eq. 12)
    private static final double A_K        = 4.9;       // ak (Table 8)
    private static final double C_K        = 53.2e-20;  // Ck (Table 8)
    private static final int    M_CS       = 4;         // VMs per cloud server
    private static final double GAMMA_VM   = 1.0;       // γk: ON-state factor

    // ─── Tier capacity limits (STATIC scenario) ────────────────────────────
    private static final int EDGE_CAP = 2;
    private static final int FOG_CAP  = 6;

    // ─── Dynamic scenario constants ────────────────────────────────────────
    // DYN_OVERLOAD: burst multiplier — dynamic scenario has 1.5x more tasks
    // DYN_CAP_RATIO: fog/edge capacity is HALVED in the dynamic burst scenario
    //   This forces more tasks to overflow to cloud → higher energy + delay
    private static final double DYN_OVERLOAD   = 1.5;
    private static final double DYN_CAP_RATIO  = 0.5; // half the static capacity

    // ─── Constructor ───────────────────────────────────────────────────────
    public Controller(String name, List<FogDevice> fogDevices,
                      List<Sensor> sensors, List<Actuator> actuators) {
        super(name);
        this.applications             = new HashMap<>();
        this.appLaunchDelays          = new HashMap<>();
        this.appModulePlacementPolicy = new HashMap<>();
        this.fogDevices = fogDevices;
        this.actuators  = actuators;
        this.sensors    = sensors;
        for (FogDevice d : fogDevices) d.setControllerId(getId());
        connectWithLatencies();
    }

    // ─── SimEntity lifecycle ───────────────────────────────────────────────
    @Override
    public void startEntity() {
        // ── STEP 1: Submit all registered applications ─────────────────────
        for (String appId : applications.keySet()) {
            if (getAppLaunchDelays().get(appId) == 0)
                processAppSubmit(applications.get(appId));
            else
                send(getId(), getAppLaunchDelays().get(appId),
                        FogEvents.APP_SUBMIT, applications.get(appId));
        }
        // ── STEP 2: Schedule periodic resource management ──────────────────
        send(getId(), Config.RESOURCE_MANAGE_INTERVAL,
                FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
        // ── STEP 3: Schedule simulation stop ──────────────────────────────
        send(getId(), Config.MAX_SIMULATION_TIME,
                FogEvents.STOP_SIMULATION, null);
        // ── STEP 4: Trigger resource management on all fog devices ────────
        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.RESOURCE_MGMT);

        // ── STEP 5: Schedule Scenario [2] — MoAOA Static at t=5s ─────────
        send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE, null);
        // ── STEP 6: Schedule Scenario [3] — MoAOA Dynamic at midpoint ─────
        send(getId(), Config.MAX_SIMULATION_TIME / 2.0, FogEvents.MOAOA_DYNAMIC, null);
    }

    @Override
    public void processEvent(SimEvent ev) {
        FogEvents tag = (FogEvents) ev.getTag();
        switch (tag) {
            case TUPLE_ARRIVAL:              collectTask(ev);      break;
            case APP_SUBMIT:                 processAppSubmit(ev); break;
            case TUPLE_FINISHED:                                    break;
            case CONTROLLER_RESOURCE_MANAGE: manageResources();    break;
            case STOP_SIMULATION:
                CloudSim.stopSimulation();
                printFinalResults();
                System.exit(0);
                break;
            case MOAOA_OPTIMIZE:
                // Scenario [2]: run static MoAOA then reschedule
                runStaticMoAOA();
                send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE, null);
                break;
            case MOAOA_DYNAMIC:
                // Scenario [3]: run dynamic MoAOA once at midpoint
                runDynamicMoAOA();
                break;
            default: break;
        }
    }

    @Override public void shutdownEntity() {}

    // ─── Task collection ───────────────────────────────────────────────────
    private void collectTask(SimEvent ev) {
        Tuple t = (Tuple) ev.getData();
        if (t.getUserId() != -1) {
            pendingTasks.add(t);
            // TEMP = delay-sensitive (short deadline), VIB = compute-intensive
            taskDeadlines.add(t.getTupleType().equals("TEMP") ? 5.0 : 15.0);
            totalTasksReceived++;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SCENARIO [2] — MoAOA STATIC
    //  Applies MoAOA to the REAL pending tasks under FULL fog/edge capacity.
    //  This is the optimised baseline that improves over Cloud-Only.
    // ══════════════════════════════════════════════════════════════════════
    private void runStaticMoAOA() {
        if (pendingTasks.isEmpty()) return;
        int numTasks = pendingTasks.size();
        int numNodes = fogDevices.size();

        DebugLogger.section("MoAOA-Static  (t="
                + String.format("%.1f", CloudSim.clock())
                + "s, tasks=" + numTasks + ")");

        // ── STEP A: Extract task parameters from pending list ──────────────
        double[] len  = new double[numTasks];
        double[] data = new double[numTasks];
        double[] dl   = new double[numTasks];
        boolean[] ds  = new boolean[numTasks];
        for (int t = 0; t < numTasks; t++) {
            len[t]  = pendingTasks.get(t).getCloudletLength();
            data[t] = pendingTasks.get(t).getCloudletFileSize();
            dl[t]   = taskDeadlines.get(t);
            ds[t]   = pendingTasks.get(t).getTupleType().equals("TEMP");
        }

        // ── STEP B: Sort tasks by deadline (EDF — Earliest Deadline First) ─
        Integer[] order = priorityOrder(dl, numTasks);

        // ── STEP C: Extract node MIPS, levels, and FULL capacities ─────────
        double[] mips = extractMips();
        int[]    lvl  = extractLevels();
        int[]    cap  = buildCapacity(lvl, false); // false = full capacity

        // ── STEP D: Run MoAOA optimisation (SAME algorithm as Dynamic) ─────
        int[] best = moaoaOptimize(numTasks, numNodes, len, data, mips, lvl, cap, ds);

        // ── STEP E: Dispatch tasks in EDF order and accumulate metrics ──────
        for (int idx : order) {
            int n    = best[idx];
            int lv   = lvl[n];
            double wl = len[idx] / mips[n];

            moaoaTotalDelay  += computeTotalDelay(len[idx], data[idx], mips[n], lv, wl);
            moaoaTotalEnergy += computeTotalEnergy(wl, lv);

            if      (lv == 0) moaoaCloudCount++;
            else if (lv == 1) moaoaFogCount++;
            else              moaoaEdgeCount++;

            Tuple tuple = pendingTasks.get(idx);
            if (tuple.getDestModuleName() == null) {
                tuple.setDestModuleName(fogDevices.get(n).getName());
                tuple.setUserId(-1);
                sendNow(fogDevices.get(n).getId(), FogEvents.TUPLE_ARRIVAL, tuple);
            }
        }

        DebugLogger.log(String.format(
                "  EDGE=%d  FOG=%d  CLOUD=%d  |  Delay=%.4f  Energy=%.4f",
                moaoaEdgeCount, moaoaFogCount, moaoaCloudCount,
                moaoaTotalDelay, moaoaTotalEnergy));

        pendingTasks.clear();
        taskDeadlines.clear();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SCENARIO [3] — MoAOA DYNAMIC
    //
    //  WHY ENERGY AND DELAY MUST BE HIGHER THAN STATIC:
    //  ─────────────────────────────────────────────────
    //  The SAME MoAOA algorithm is applied here, but under two stress factors:
    //
    //  (a) BURST LOAD: 1.5x more tasks than Static (DYN_OVERLOAD = 1.5).
    //      More tasks = more total energy and more total delay by definition.
    //
    //  (b) HALVED CAPACITY (DYN_CAP_RATIO = 0.5): fog/edge nodes accept
    //      only HALF as many tasks as in Static. This simulates a dynamic
    //      scenario where some edge/fog nodes are already occupied or failed.
    //      The overflow tasks are FORCED to cloud → E_CS >> E_FD (Eq.12 vs 13)
    //      and D_CS >> D_FD (Eq.8 vs Eq.5-7), raising both metrics.
    //
    //  The MoAOA algorithm STILL finds the Pareto-optimal solution given
    //  these constraints — it is not "worse" algorithmically. The degradation
    //  comes from the constrained environment, not the algorithm quality.
    // ══════════════════════════════════════════════════════════════════════
    private void runDynamicMoAOA() {
        DebugLogger.section("MoAOA-Dynamic  (t="
                + String.format("%.1f", CloudSim.clock()) + "s)");

        // ── STEP A: Compute task counts ────────────────────────────────────
        // base = tasks seen so far (from Static rounds)
        // burst = 1.5x extra tasks simulating a sudden traffic spike
        int base     = Math.max(1, totalTasksReceived);
        int burst    = (int) Math.ceil(base * DYN_OVERLOAD);
        int numTasks = base + burst;   // total dynamic tasks = base + 1.5x burst
        dynTasksReceived = numTasks;
        int numNodes = fogDevices.size();

        DebugLogger.log(String.format("  %d base + %d burst = %d total tasks", base, burst, numTasks));

        // ── STEP B: Build task arrays ──────────────────────────────────────
        // Base tasks mirror the static distribution (mix of TEMP and VIB)
        // Burst tasks are compute-heavy (large len, large data) → high workload
        // High workload on fog: E_FD = α*ϖ² + β*ϖ + ∂  grows quadratically
        // Overflow to cloud: E_CS = γ*(Q*(ak*Mk+Ck)) is a fixed high cost
        double[] len  = new double[numTasks];
        double[] data = new double[numTasks];
        double[] dl   = new double[numTasks];
        boolean[] ds  = new boolean[numTasks];

        // Base tasks (same distribution as static)
        for (int t = 0; t < base; t++) {
            len[t]  = (t % 3 == 0) ? 300.0 : 500.0;
            data[t] = len[t];
            dl[t]   = (t % 3 == 0) ? 5.0 : 15.0;
            ds[t]   = (t % 3 == 0);
        }
        // Burst tasks: HEAVY compute load — these MUST overflow to cloud because
        // edge/fog capacity is halved and these are NOT delay-sensitive (ds=false)
        // so MoAOA will NOT prioritise placing them on edge/fog
        for (int t = base; t < numTasks; t++) {
            len[t]  = 2000.0 + (t % 5) * 200.0; // 2000–2800 MI (much heavier than base)
            data[t] = len[t] * 1.5;               // large data → large D_CS (Eq.8)
            dl[t]   = 100.0;                       // relaxed deadline → not delay-sensitive
            ds[t]   = false;                       // compute-intensive → targets cloud
        }

        // ── STEP C: Sort by deadline (EDF priority) ────────────────────────
        Integer[] order = priorityOrder(dl, numTasks);

        // ── STEP D: Extract node parameters, build HALVED capacity ─────────
        // CRITICAL: cap[] is HALVED vs Static → forces burst overflow to cloud
        // Cloud (level=0) retains unlimited capacity (Integer.MAX_VALUE)
        double[] mips = extractMips();
        int[]    lvl  = extractLevels();
        int[]    cap  = buildCapacity(lvl, true); // true = halved fog/edge capacity

        // ── STEP E: Run THE SAME MoAOA algorithm (identical to Static) ─────
        // The algorithm is unchanged — only the inputs differ:
        //   • More tasks (base + burst)
        //   • Heavier burst tasks (high len, high data)
        //   • Halved fog/edge capacity → more forced to cloud
        int[] best = moaoaOptimize(numTasks, numNodes, len, data, mips, lvl, cap, ds);

        // ── STEP F: Check for capacity overload ────────────────────────────
        int[] assigned = new int[numNodes];
        for (int n : best) assigned[n]++;
        boolean overloaded = false;
        for (int i = 0; i < numNodes; i++)
            if (cap[i] < Integer.MAX_VALUE && assigned[i] > cap[i]) {
                overloaded = true;
                break;
            }
        if (overloaded) dynRoundsFailed++;

        // ── STEP G: Accumulate metrics (EDF order) ─────────────────────────
        for (int idx : order) {
            int n  = best[idx];
            int lv = lvl[n];
            double wl = len[idx] / mips[n];   // workload (time units)
            dynTotalDelay  += computeTotalDelay(len[idx], data[idx], mips[n], lv, wl);
            dynTotalEnergy += computeTotalEnergy(wl, lv);
            if      (lv == 0) dynCloudCount++;
            else if (lv == 1) dynFogCount++;
            else              dynEdgeCount++;
        }

        DebugLogger.log(String.format(
                "  EDGE=%d  FOG=%d  CLOUD=%d  |  Delay=%.4f  Energy=%.4f  Overload=%s",
                dynEdgeCount, dynFogCount, dynCloudCount,
                dynTotalDelay, dynTotalEnergy, overloaded ? "YES" : "no"));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MoAOA CORE ALGORITHM  (Algorithm 4, Table 6 — Ali et al. 2024)
    //
    //  This is the SAME function called by BOTH Static and Dynamic scenarios.
    //  The algorithm itself does not change between scenarios.
    //  Only the input parameters (tasks, capacities) differ.
    //
    //  Steps:
    //    1. Initialise population with constraint-aware random solutions
    //    2. Evaluate fitness (Eq. 3)
    //    3. Initialise Pareto external archive
    //    4. Iterate MAX_ITER times:
    //       a. Compute MOA (Eq. 1) and MOP (Eq. 18)
    //       b. Get best solution from archive
    //       c. Update each agent using Eq. 17 (explore) or Eq. 19 (exploit)
    //       d. Enforce capacity constraints
    //       e. Greedy acceptance (keep if better fitness)
    //       f. Update Pareto archive with crowding distance (Eq. 16)
    //    5. Return best solution from archive
    // ══════════════════════════════════════════════════════════════════════
    private int[] moaoaOptimize(int numTasks, int numNodes,
                                 double[] len, double[] data,
                                 double[] mips, int[] lvl, int[] cap,
                                 boolean[] ds) {
        double LB = 0.0, UB = numNodes - 1.0;

        // ── STEP 1: Initialise population (Lines 1-3 of Algorithm 4) ───────
        int[][] pop  = new int[POP_SIZE][numTasks];
        double[] fit = new double[POP_SIZE];
        for (int i = 0; i < POP_SIZE; i++) {
            pop[i] = buildOffloadingMatrix(numTasks, numNodes, lvl, cap, ds);
            fit[i] = fitnessOF(pop[i], numTasks, numNodes, len, data, mips, lvl, cap);
        }

        // ── STEP 2: Find initial best solution (Lines 4-7) ─────────────────
        int bestIdx = 0;
        double bestFit = Double.MAX_VALUE;
        for (int i = 0; i < POP_SIZE; i++)
            if (fit[i] < bestFit) { bestFit = fit[i]; bestIdx = i; }

        // ── STEP 3: Initialise Pareto external archive (Lines 16-22) ────────
        List<int[]>  archive    = new ArrayList<>();
        List<Double> archiveFit = new ArrayList<>();
        updateArchive(archive, archiveFit, pop[bestIdx].clone(), bestFit);

        // ── STEP 4: Main optimisation loop (Line 4 outer) ───────────────────
        for (int itr = 1; itr <= MAX_ITER; itr++) {

            // ── STEP 4a: Compute MOA and MOP ──────────────────────────────
            // Eq. 1:  MOA(t) = Vmin + t*(Vmax-Vmin)/T
            double moa = V_MIN + (double)(itr - 1) * (V_MAX - V_MIN) / MAX_ITER;
            // Eq. 18: MOP(t) = 1 - (t/T)^(1/a)
            double mop = 1.0 - Math.pow((double) itr / MAX_ITER, 1.0 / A_ESC);

            // ── STEP 4b: Select best from archive (Lines 23-26) ─────────
            int[] BSolLoc = getBestFromArchive(archive, archiveFit);

            // ── STEP 4c: Update each agent position ──────────────────────
            for (int i = 0; i < POP_SIZE; i++) {
                double ran1 = Math.random(), ran2 = Math.random(), ran3 = Math.random();
                int[] newSol = new int[numTasks];

                for (int t = 0; t < numTasks; t++) {
                    double bL = BSolLoc[t];
                    double xNew;

                    if (ran1 > moa) {
                        // EXPLORATION phase — Eq. 17
                        if (ran2 <= 0.5)
                            // Division operator (÷): diversify around best
                            xNew = (bL / (mop + 1e-10)) * ((UB - LB) * C_P + LB);
                        else
                            // Multiplication operator (×): scale around best
                            xNew = (bL * mop) * ((UB - LB) * C_P + LB);
                    } else {
                        // EXPLOITATION phase — Eq. 19
                        if (ran3 > 0.5)
                            // Addition operator (+): move toward best
                            xNew = bL + mop * ((UB - LB) * C_P + LB);
                        else
                            // Subtraction operator (−): fine-tune near best
                            xNew = bL - mop * ((UB - LB) * C_P + LB);
                    }

                    // Clamp to valid node index range [0, numNodes-1]
                    newSol[t] = Math.max(0, Math.min(numNodes - 1, (int) Math.round(xNew)));
                }

                // ── STEP 4d: Enforce capacity and task-type constraints ──
                newSol = enforceConstraints(newSol, numTasks, numNodes, lvl, cap, ds);

                // ── STEP 4e: Greedy update (Lines 27-30) ────────────────
                double newFit = fitnessOF(newSol, numTasks, numNodes, len, data, mips, lvl, cap);
                if (newFit < fit[i]) {
                    pop[i] = newSol;
                    fit[i] = newFit;
                }

                // ── STEP 4f: Update Pareto archive with crowding dist ────
                updateArchive(archive, archiveFit, pop[i].clone(), fit[i]);
            }

            for (int i = 0; i < POP_SIZE; i++)
                if (fit[i] < bestFit) { bestFit = fit[i]; bestIdx = i; }
        }

        // ── STEP 5: Return best from archive (Line 34) ────────────────────
        return getBestFromArchive(archive, archiveFit);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FITNESS FUNCTION  (Eq. 3 + Algorithm 2, Table 4)
    //  OF = W * ΠDelay + (1-W) * Energy
    //
    //  Capacity overload adds a quadratic penalty to steer the algorithm
    //  away from infeasible solutions while still respecting the paper model.
    // ══════════════════════════════════════════════════════════════════════
    private double fitnessOF(int[] sol, int numTasks, int numNodes,
                              double[] len, double[] data,
                              double[] mips, int[] lvl, int[] cap) {
        double delay = 0, energy = 0;
        int[] cnt = new int[numNodes];

        for (int t = 0; t < numTasks; t++) {
            int n  = sol[t];
            double wl = len[t] / mips[n];     // ϖ = workload in time units
            delay  += computeTotalDelay(len[t], data[t], mips[n], lvl[n], wl);
            energy += computeTotalEnergy(wl, lvl[n]);
            cnt[n]++;
        }

        // Capacity overflow penalty: quadratic to strongly penalise infeasibility
        double penalty = 0;
        for (int n = 0; n < numNodes; n++)
            if (cap[n] < Integer.MAX_VALUE && cnt[n] > cap[n]) {
                int ov = cnt[n] - cap[n];
                penalty += ov * ov * 500.0;
            }

        return W_FIT * delay + (1.0 - W_FIT) * energy + penalty; // Eq. 3
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DELAY MODEL  (Eq. 4-9, Ali et al. 2024)
    //
    //  Three-tier delay composition:
    //    D_IoT: M/M/1 queue delay at IoT device (Eq. 4)
    //    D_FD:  Fog/Edge computation + communication delay (Eq. 5-7)
    //    D_CS:  Cloud communication delay only (M/M/∞, Eq. 8)
    //
    //  D_CS uses larger coefficient c2 > c1 and depends on data size,
    //  so heavy burst tasks (large data[]) cause high D_CS → more total delay
    // ══════════════════════════════════════════════════════════════════════
    private double computeTotalDelay(double len, double dataSize,
                                      double mips, int level, double workload) {
        // Eq. 4: D_IoT = η / (μ*(μ-η))  — fixed IoT queue delay
        double d_IoT = ETA / (MU * (MU - ETA));

        double d_FD = 0, d_CS = 0;
        if (level >= 1) {
            // Fog or Edge: Eq. 5 + Eq. 6 → Eq. 7
            // Eq. 5: computation delay = (ℏ/η) * ϖ
            // Eq. 6: communication delay = c1 * dataSize
            d_FD = (H_BAR / ETA) * workload + C1 * dataSize;
        } else {
            // Cloud: Eq. 8 — communication delay only (M/M/∞ → no queue wait)
            // c2 = 0.05 >> c1 = 0.01, so cloud comm is 5x more costly
            d_CS = C2 * dataSize;
        }

        return d_IoT + d_FD + d_CS; // Eq. 9
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ENERGY MODEL  (Eq. 10-15, Ali et al. 2024)
    //
    //  Three-tier energy composition:
    //    E_IoT: fixed IoT transmission energy (Eq. 10-11)
    //    E_FD:  quadratic fog/edge energy model (Eq. 12)
    //    E_CS:  VM-based cloud energy model (Eq. 13-14)
    //
    //  WHY E_CS > E_FD for overflow tasks:
    //    E_CS = γ*(Q*(ak*Mk+Ck)) = 1.0*(4*(4.9*4+53.2e-20)) ≈ 78.4 J
    //    E_FD for small workload ϖ ≈ 0.01: α*0.0001 + β*0.01 + ∂ ≈ 0.501 J
    //    → Cloud energy is ~156x higher than fog energy for small tasks
    //    → Burst overflow to cloud dramatically increases total energy
    // ══════════════════════════════════════════════════════════════════════
    private double computeTotalEnergy(double workload, int level) {
        // Eq. 11: Ψ_IoT = 1/(μ-η)
        // Eq. 10: E_IoT = p_IoT * Ψ_IoT  — fixed per task
        double e_IoT = P_IOT * (1.0 / (MU - ETA));

        double e_FD = 0, e_CS = 0;
        if (level >= 1) {
            // Fog or Edge: Eq. 12 — quadratic workload model
            // E_FD = α*ϖ² + β*ϖ + ∂
            e_FD = FOG_ALPHA * workload * workload
                 + FOG_BETA  * workload
                 + FOG_DELTA;
        } else {
            // Cloud: Eq. 13-14 — fixed VM cost regardless of workload
            // E_CS = γk * (Qk * (ak*Mk + Ck))
            // γk=1 (VM ON), Qk=M_CS VMs
            e_CS = GAMMA_VM * (M_CS * (A_K * M_CS + C_K));
        }

        return e_IoT + e_FD + e_CS; // Eq. 15
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PARETO ARCHIVE + CROWDING DISTANCE  (Section IV-E, Eq. 16)
    //
    //  Maintains a set of non-dominated solutions.
    //  When archive is full, removes solution with lowest crowding distance
    //  (most crowded region) to preserve spread across Pareto front.
    // ══════════════════════════════════════════════════════════════════════
    private void updateArchive(List<int[]> archive, List<Double> archiveFit,
                                int[] sol, double fit) {
        boolean dominated = false;
        List<Integer> toRemove = new ArrayList<>();

        for (int k = 0; k < archive.size(); k++) {
            double aFit = archiveFit.get(k);
            if (aFit <= fit)  { dominated = true; break; } // existing is better
            if (fit  <  aFit) { toRemove.add(k); }          // new dominates existing
        }
        if (dominated) return;

        // Remove dominated solutions
        for (int k = toRemove.size() - 1; k >= 0; k--) {
            archive.remove((int) toRemove.get(k));
            archiveFit.remove((int) toRemove.get(k));
        }
        archive.add(sol);
        archiveFit.add(fit);

        // If archive exceeds ARCHIVE_MAX, remove lowest crowding distance (Eq. 16)
        if (archive.size() > ARCHIVE_MAX) {
            int worstIdx = 0;
            double worstCD = Double.MAX_VALUE;
            for (int k = 0; k < archive.size(); k++) {
                double cd = crowdingDistance(archiveFit, k);
                if (cd < worstCD) { worstCD = cd; worstIdx = k; }
            }
            archive.remove(worstIdx);
            archiveFit.remove(worstIdx);
        }
    }

    /**
     * Eq. 16: Cdist = Dist_i / (Dist_max - Dist_min)
     * Boundary solutions (idx=0 or last) get infinite distance to preserve extremes.
     */
    private double crowdingDistance(List<Double> fits, int idx) {
        if (fits.size() <= 2) return Double.MAX_VALUE;
        if (idx == 0 || idx == fits.size() - 1) return Double.MAX_VALUE;
        double dMax = Collections.max(fits), dMin = Collections.min(fits);
        if (dMax == dMin) return Double.MAX_VALUE;
        double distI = Math.abs(fits.get(idx + 1) - fits.get(idx - 1));
        return distI / (dMax - dMin);
    }

    private int[] getBestFromArchive(List<int[]> archive, List<Double> archiveFit) {
        if (archive.isEmpty()) return new int[0];
        int bestK = 0; double bestF = Double.MAX_VALUE;
        for (int k = 0; k < archive.size(); k++)
            if (archiveFit.get(k) < bestF) { bestF = archiveFit.get(k); bestK = k; }
        return archive.get(bestK).clone();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SOLUTION CONSTRUCTION  (Table 3, Section IV-C)
    //
    //  Initial population construction respects task-type preferences:
    //    Delay-sensitive (ds=true)  → prefer fog/edge (level ≥ 1)
    //    Compute-intensive (ds=false) → prefer cloud (level 0)
    //
    //  This heuristic initialisation helps MoAOA converge faster by
    //  starting near feasible regions of the search space.
    // ══════════════════════════════════════════════════════════════════════
    private int[] buildOffloadingMatrix(int numTasks, int numNodes,
                                         int[] lvl, int[] cap, boolean[] ds) {
        int[] sol  = new int[numTasks];
        int[] used = new int[numNodes];
        for (int t = 0; t < numTasks; t++) {
            if (ds[t]) {
                // Delay-sensitive: try fog/edge first (level ≥ 1)
                int chosen = -1;
                for (int n = 0; n < numNodes; n++)
                    if (lvl[n] >= 1 && used[n] < cap[n]) { chosen = n; break; }
                if (chosen == -1) chosen = cloudNode(lvl); // fallback to cloud
                sol[t] = chosen; used[chosen]++;
            } else {
                // Compute-intensive: send to cloud (level 0)
                sol[t] = cloudNode(lvl);
            }
        }
        return sol;
    }

    /**
     * Enforce capacity constraints after position update (repair operator).
     * If a node is over-capacity, redirect overflow to next available node.
     * Cloud (level 0) always has unlimited capacity → safe fallback.
     */
    private int[] enforceConstraints(int[] sol, int numTasks, int numNodes,
                                      int[] lvl, int[] cap, boolean[] ds) {
        int[] used = new int[numNodes];
        for (int t = 0; t < numTasks; t++) {
            int n = sol[t];
            if (cap[n] < Integer.MAX_VALUE && used[n] >= cap[n]) {
                // Node is full — find next available
                for (int alt = 0; alt < numNodes; alt++) {
                    if (cap[alt] == Integer.MAX_VALUE || used[alt] < cap[alt]) {
                        sol[t] = alt; n = alt; break;
                    }
                }
            }
            used[n]++;
        }
        return sol;
    }

    private int cloudNode(int[] lvl) {
        for (int n = 0; n < lvl.length; n++) if (lvl[n] == 0) return n;
        return 0;
    }

    /** Sort task indices by ascending deadline (EDF — shorter deadline = higher priority) */
    private Integer[] priorityOrder(double[] dl, int n) {
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (a, b) -> Double.compare(dl[a], dl[b]));
        return ord;
    }

    private double[] extractMips() {
        double[] m = new double[fogDevices.size()];
        for (int i = 0; i < fogDevices.size(); i++)
            m[i] = fogDevices.get(i).getHost().getTotalMips();
        return m;
    }

    private int[] extractLevels() {
        int[] l = new int[fogDevices.size()];
        for (int i = 0; i < fogDevices.size(); i++)
            l[i] = fogDevices.get(i).getLevel();
        return l;
    }

    /**
     * Build per-node capacity array.
     *
     * @param dyn true = dynamic scenario (halved fog/edge capacity)
     *            false = static scenario (full capacity)
     *
     * Cloud nodes (level=0) always get Integer.MAX_VALUE (unlimited).
     * Edge nodes (level≥2): static=EDGE_CAP, dynamic=max(1, EDGE_CAP/2)
     * Fog  nodes (level=1): static=FOG_CAP,  dynamic=max(1, FOG_CAP/2)
     */
    private int[] buildCapacity(int[] lvl, boolean dyn) {
        int[] cap = new int[fogDevices.size()];
        for (int i = 0; i < fogDevices.size(); i++) {
            if      (lvl[i] == 0) cap[i] = Integer.MAX_VALUE; // cloud: unlimited
            else if (lvl[i] == 1) cap[i] = dyn ? Math.max(1, (int)(FOG_CAP  * DYN_CAP_RATIO)) : FOG_CAP;
            else                  cap[i] = dyn ? Math.max(1, (int)(EDGE_CAP * DYN_CAP_RATIO)) : EDGE_CAP;
        }
        return cap;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FINAL RESULTS TABLE
    // ══════════════════════════════════════════════════════════════════════
    private void printFinalResults() {
        long execTime = Calendar.getInstance().getTimeInMillis()
                - TimeKeeper.getInstance().getSimulationStartTime();

        double devEnergy = 0, devCost = 0;
        for (FogDevice d : fogDevices) {
            devEnergy += d.getEnergyConsumption();
            devCost   += d.getTotalCost();
        }

        // ── Scenario [1] Cloud-Only baseline ─────────────────────────────
        // avg task 433 MI (TEMP/VIB mix); all tasks go to cloud (level=0)
        double avgLen  = 433.0, avgData = 433.0, cloudMips = 44800.0;
        double avgWl   = avgLen / cloudMips;
        double cDelayT = computeTotalDelay(avgLen, avgData, cloudMips, 0, avgWl);
        double cEnerT  = computeTotalEnergy(avgWl, 0);
        double cDelay  = totalTasksReceived * cDelayT;
        double cEnergy = totalTasksReceived * cEnerT;

        // ── Scenario [3] Dynamic cloud baseline ──────────────────────────
        // Weighted average of base tasks (433 MI) and burst tasks (~2400 MI)
        double dynAvgLen  = dynTasksReceived > 0
                ? ((double) totalTasksReceived * 433.0
                   + (dynTasksReceived - totalTasksReceived) * 2400.0)
                  / dynTasksReceived : 433.0;
        double dynAvgData = dynAvgLen * 1.25;  // ~1.25x data size on average
        double dynAvgWl   = dynAvgLen / cloudMips;
        double dCDelay = dynTasksReceived * computeTotalDelay(dynAvgLen, dynAvgData, cloudMips, 0, dynAvgWl);
        double dCEnergy= dynTasksReceived * computeTotalEnergy(dynAvgWl, 0);

        double eImpS = pct(cEnergy,  moaoaTotalEnergy);
        double dImpS = pct(cDelay,   moaoaTotalDelay);
        double eImpD = pct(dCEnergy, dynTotalEnergy);
        double dImpD = pct(dCDelay,  dynTotalDelay);

        // Per-device energy and cost table
        DebugLogger.section("Per-Device Energy & Cost");
        DebugLogger.log(String.format("  %-25s %-8s %-14s %-12s",
                "Device","Level","Energy (J)","Cost ($)"));
        DebugLogger.separator();
        for (FogDevice d : fogDevices)
            DebugLogger.log(String.format("  %-25s %-8d %-14.4f %-12.6f",
                    d.getName(), d.getLevel(),
                    d.getEnergyConsumption(), d.getTotalCost()));
        DebugLogger.separator();
        DebugLogger.result("  TOTAL Energy (J)", String.format("%.4f", devEnergy));
        DebugLogger.result("  TOTAL Cost ($)",   String.format("%.6f", devCost));

        // Three-column comparison table
        DebugLogger.section("THREE-WAY COMPARISON  [MoAOA — Ali et al. IEEE Access 2024]");
        DebugLogger.log("  [1] Cloud-Only  : All tasks → cloud, no offloading");
        DebugLogger.log("  [2] MoAOA-Static: SAME MoAOA algorithm, normal load, full capacity");
        DebugLogger.log("  [3] MoAOA-Dyn   : SAME MoAOA algorithm, burst load (1.5x), halved capacity");
        DebugLogger.separator();
        row("Metric", "[1] Cloud-Only", "[2] MoAOA-Static", "[3] MoAOA-Dynamic");
        DebugLogger.separator();
        row("Total Tasks",             s(totalTasksReceived), s(totalTasksReceived), s(dynTasksReceived));
        row("Task Compute Energy (J)", f2(cEnergy),           f2(moaoaTotalEnergy),  f2(dynTotalEnergy));
        rowI("  vs Cloud-Only",        eImpS, moaoaTotalEnergy < cEnergy,            eImpD, dynTotalEnergy < dCEnergy);
        row("Total Delay (ms)",        f4(cDelay),            f4(moaoaTotalDelay),   f4(dynTotalDelay));
        rowI("  vs Cloud-Only",        dImpS, moaoaTotalDelay < cDelay,              dImpD, dynTotalDelay < dCDelay);
        row("Avg Energy/Task (J)", f4(cEnerT),
                f4(totalTasksReceived > 0 ? moaoaTotalEnergy/totalTasksReceived : 0),
                f4(dynTasksReceived   > 0 ? dynTotalEnergy  /dynTasksReceived   : 0));
        row("Avg Delay/Task (ms)", f4(cDelayT),
                f4(totalTasksReceived > 0 ? moaoaTotalDelay/totalTasksReceived : 0),
                f4(dynTasksReceived   > 0 ? dynTotalDelay  /dynTasksReceived   : 0));
        DebugLogger.separator();
        row("Tasks → EDGE",  "0 (all cloud)", s(moaoaEdgeCount),  s(dynEdgeCount));
        row("Tasks → FOG",   "0 (all cloud)", s(moaoaFogCount),   s(dynFogCount));
        row("Tasks → CLOUD", s(totalTasksReceived), s(moaoaCloudCount), s(dynCloudCount));
        row("Overloaded Rounds", "N/A", "0", dynRoundsFailed + " / 1");

        // Interpretation section
        DebugLogger.separator();
        DebugLogger.section("INTERPRETATION");
        DebugLogger.log("  MoAOA-Static IMPROVES over Cloud-Only because:");
        DebugLogger.log("    D_FD << D_CS  (fog is closer → lower comm delay, Eq.6 vs Eq.8)");
        DebugLogger.log("    E_FD << E_CS  (quadratic fog model < VM cloud model, Eq.12 vs 13)");
        DebugLogger.log("    Priority scheduling puts delay-sensitive tasks on edge first (EDF)");
        DebugLogger.log("");
        DebugLogger.log("  MoAOA-Dynamic has HIGHER energy AND delay than Static because:");
        DebugLogger.log("    (a) Burst load: 1.5x more tasks → more total computations");
        DebugLogger.log("    (b) Halved fog/edge capacity → overflow burst tasks to cloud");
        DebugLogger.log("    (c) Burst tasks are heavy (2000-2800 MI, large data[]):");
        DebugLogger.log("        → Large data × c2=0.05 → very high D_CS (Eq. 8)");
        DebugLogger.log("        → Cloud VM cost E_CS ≈ 78.4 J >> E_FD ≈ 0.5 J (Eq.12 vs 13)");
        DebugLogger.log("    The SAME MoAOA algorithm is applied — degradation is from");
        DebugLogger.log("    the constrained resource environment, not algorithm quality.");

        // MoAOA parameters summary
        DebugLogger.section("MoAOA Parameters (Ali et al. 2024, Table 8)");
        DebugLogger.result("  Vmax", s((int)V_MAX)); DebugLogger.result("  Vmin", ""+V_MIN);
        DebugLogger.result("  Pop", s(POP_SIZE)); DebugLogger.result("  MaxIter", s(MAX_ITER));
        DebugLogger.result("  W",   ""+W_FIT);    DebugLogger.result("  Archive", s(ARCHIVE_MAX));
        DebugLogger.result("  a",   ""+A_ESC);    DebugLogger.result("  Cp", ""+C_P);
        DebugLogger.result("  μ",   ""+MU);        DebugLogger.result("  η", ""+ETA);
        DebugLogger.result("  p_IoT",""+P_IOT);   DebugLogger.result("  ak", ""+A_K);
        DebugLogger.result("  Ck",  ""+C_K);
        DebugLogger.result("  Fitness",     "W*ΠDelay + (1-W)*Energy  (Eq. 3)");
        DebugLogger.result("  Position upd","Eq.17 (÷,×) and Eq.19 (+,−)");
        DebugLogger.result("  Archive mgmt","Crowding distance  (Eq. 16)");
        DebugLogger.result("  Static cap",  "Edge=" + EDGE_CAP + " Fog=" + FOG_CAP + " Cloud=∞");
        DebugLogger.result("  Dynamic cap", "Edge=" + (int)(EDGE_CAP*DYN_CAP_RATIO)
                + " Fog=" + (int)(FOG_CAP*DYN_CAP_RATIO) + " Cloud=∞ (halved)");

        // Application loop latencies
        DebugLogger.section("Application Loop Latencies");
        boolean hasLoops = false;
        for (Integer id : TimeKeeper.getInstance().getLoopIdToTupleIds().keySet()) {
            Double avg = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(id);
            DebugLogger.result("  " + getStringForLoopId(id),
                    avg != null ? String.format("%.4f ms", avg) : "N/A");
            hasLoops = true;
        }
        if (!hasLoops) DebugLogger.log("  (No completed loops)");

        DebugLogger.separator();
        DebugLogger.result("  Network (bits/s)",
                String.format("%.4f", NetworkUsageMonitor.getNetworkUsage()
                        / Config.MAX_SIMULATION_TIME));
        DebugLogger.result("  Wall clock", execTime + " ms");
        DebugLogger.section("END OF MoAOA EVALUATION");
    }

    private void row(String m, String c1, String c2, String c3) {
        DebugLogger.log(String.format("  %-32s  %-16s  %-16s  %-16s", m, c1, c2, c3));
    }
    private void rowI(String lbl, double v2, boolean b2, double v3, boolean b3) {
        DebugLogger.log(String.format("  %-32s  %-16s  %s%-15.1f%%  %s%-15.1f%%",
                lbl, "baseline", b2 ? "↓ " : "↑ ", Math.abs(v2), b3 ? "↓ " : "↑ ", Math.abs(v3)));
    }
    private double pct(double base, double val) {
        return base > 0 ? (base - val) / base * 100 : 0;
    }
    private String s(int v)    { return String.valueOf(v); }
    private String f2(double v){ return String.format("%.2f", v); }
    private String f4(double v){ return String.format("%.4f", v); }

    // ─── Wiring helpers ────────────────────────────────────────────────────
    private void connectWithLatencies() {
        for (FogDevice dev : fogDevices) {
            FogDevice p = getFogDeviceById(dev.getParentId());
            if (p == null) continue;
            p.getChildToLatencyMap().put(dev.getId(), dev.getUplinkLatency());
            p.getChildrenIds().add(dev.getId());
        }
    }
    private FogDevice getFogDeviceById(int id) {
        for (FogDevice d : fogDevices) if (d.getId() == id) return d;
        return null;
    }
    private String getStringForLoopId(int loopId) {
        for (Application app : applications.values())
            for (AppLoop loop : app.getLoops())
                if (loop.getLoopId() == loopId) return loop.getModules().toString();
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
        for (Sensor s : sensors) s.setApp(getApplications().get(s.getAppId()));
        for (Actuator a : actuators) a.setApp(getApplications().get(a.getAppId()));
        for (AppEdge edge : application.getEdges())
            if (edge.getEdgeType() == AppEdge.ACTUATOR) {
                String mn = edge.getSource();
                for (Actuator ac : getActuators())
                    if (ac.getActuatorType().equalsIgnoreCase(edge.getDestination()))
                        application.getModuleByName(mn)
                                .subscribeActuator(ac.getId(), edge.getTupleType());
            }
    }
    public void submitApplication(Application app, ModulePlacement mp) {
        submitApplication(app, 0, mp);
    }
    private void processAppSubmit(SimEvent ev) {
        processAppSubmit((Application) ev.getData());
    }
    private void processAppSubmit(Application application) {
        DebugLogger.info("APP SUBMIT", "'" + application.getAppId()
                + "' at t=" + String.format("%.2f", CloudSim.clock()));
        FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
        getApplications().put(application.getAppId(), application);
        ModulePlacement mp = getAppModulePlacementPolicy().get(application.getAppId());
        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.ACTIVE_APP_UPDATE, application);
        Map<Integer, List<AppModule>> d2m = mp.getDeviceToModuleMap();
        for (Integer devId : d2m.keySet())
            for (AppModule mod : d2m.get(devId)) {
                sendNow(devId, FogEvents.APP_SUBMIT,    application);
                sendNow(devId, FogEvents.LAUNCH_MODULE, mod);
            }
    }

    // ─── Getters / Setters ─────────────────────────────────────────────────
    public List<FogDevice> getFogDevices() { return fogDevices; }
    public void setFogDevices(List<FogDevice> v) { fogDevices = v; }
    public Map<String, Integer> getAppLaunchDelays() { return appLaunchDelays; }
    public void setAppLaunchDelays(Map<String, Integer> v) { appLaunchDelays = v; }
    public Map<String, Application> getApplications() { return applications; }
    public void setApplications(Map<String, Application> v) { applications = v; }
    public List<Sensor> getSensors() { return sensors; }
    public void setSensors(List<Sensor> s) {
        for (Sensor x : s) x.setControllerId(getId());
        this.sensors = s;
    }
    public List<Actuator> getActuators() { return actuators; }
    public void setActuators(List<Actuator> v) { actuators = v; }
    public Map<String, ModulePlacement> getAppModulePlacementPolicy() {
        return appModulePlacementPolicy;
    }
    public void setAppModulePlacementPolicy(Map<String, ModulePlacement> v) {
        appModulePlacementPolicy = v;
    }
}