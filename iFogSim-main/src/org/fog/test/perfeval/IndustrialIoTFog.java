package org.fog.test.perfeval;

import org.fog.utils.DebugLogger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.custom.FogOffloadingPlacement;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogEvents;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * IndustrialIoTFog - Main simulation entry point.
 *
 * Evaluation 2 : Baseline MOAOA task offloading in IoT-Fog-Cloud environment.
 *
 * Architecture :
 *   Cloud  (level 0)  →  44800 MIPS, 40000 MB RAM
 *   Proxy  (level 1)  →  2800  MIPS,  4000 MB RAM
 *   Router (level 1)  →  2800  MIPS,  4000 MB RAM
 *   Sensor Nodes (level 3) → 500 MIPS, 1000 MB RAM  x4
 *
 * Sensors : TEMP (every 3s), VIB (every 10s) per sensor node
 *
 * Modules :
 *   data_preprocessor  → placed on sensor nodes (edge)
 *   analytics          → placed on cloud
 *   cloud_storage      → placed on cloud
 */
public class IndustrialIoTFog {

    // ─── Topology config ──────────────────────────────────────────────────────
    static List<FogDevice> fogDevices  = new ArrayList<>();
    static List<Sensor>    sensors     = new ArrayList<>();
    static List<Actuator>  actuators   = new ArrayList<>();

    static final int NUM_AREAS               = 1;
    static final int NUM_SENSOR_NODES_PER_AREA = 4;

    // ─── Main ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {

        // Suppress default CloudSim console noise
        Log.disable();

        // ── STEP 1 : Initialise simulation ────────────────────────────────────
        DebugLogger.section("EVALUATION 2 — MOAOA Task Offloading in IoT-Fog-Cloud");
        DebugLogger.step(1, "Initialising CloudSim simulation environment...");

        try {
            int numUsers       = 1;
            Calendar calendar  = Calendar.getInstance();
            boolean traceFlag  = false;
            CloudSim.init(numUsers, calendar, traceFlag);
            DebugLogger.info("CloudSim", "Initialised successfully");

            String appId = "industrial_iot";

            // ── STEP 2 : Create broker and application ────────────────────────
            DebugLogger.step(2, "Creating FogBroker and Application...");
            FogBroker broker = new FogBroker("broker");

            Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());
            DebugLogger.info("Application", "'" + appId + "' created with 3 modules: "
                    + "data_preprocessor, analytics, cloud_storage");

            // ── STEP 3 : Create physical topology ────────────────────────────
            DebugLogger.step(3, "Creating fog device topology...");
            createFogDevices(broker.getId(), appId);
            DebugLogger.info("Topology", "Total fog devices created : " + fogDevices.size());
            DebugLogger.info("Topology", "Total sensors created     : " + sensors.size());
            printTopologySummary();

            // ── STEP 4 : Module placement (edge-first) ────────────────────────
            DebugLogger.step(4, "Performing module placement via FogOffloadingPlacement...");
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();

            FogOffloadingPlacement placement = new FogOffloadingPlacement(
                    fogDevices, sensors, actuators, application, moduleMapping);

            // ── STEP 5 : Create controller and wire everything together ────────
            DebugLogger.step(5, "Creating Controller and connecting sensors...");
            Controller controller = new Controller(
                    "master-controller", fogDevices, sensors, actuators);

            for (FogDevice device : fogDevices) {
                device.setControllerId(controller.getId());
            }
            for (Sensor sensor : sensors) {
                sensor.setApp(application);
            }

            controller.submitApplication(application, placement);

            // Sensors start transmitting via their own startEntity() -> EMIT_TUPLE loop.

            DebugLogger.info("Controller", "master-controller created and wired");

            // ── STEP 6 : Run simulation ────────────────────────────────────────
            DebugLogger.step(6, "Starting CloudSim simulation...");
            TimeKeeper.getInstance().setSimulationStartTime(
                    Calendar.getInstance().getTimeInMillis());

            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            DebugLogger.info("Simulation", "Completed successfully");

        } catch (Exception e) {
            DebugLogger.log("[ERROR] Simulation failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            DebugLogger.close();
        }
    }

    // ─── Topology helpers ─────────────────────────────────────────────────────

    /**
     * Creates the full fog device hierarchy:
     * Cloud → Proxy → Router → SensorNodes
     */
    private static void createFogDevices(int userId, String appId) {

        // Cloud (level 0)
        FogDevice cloud = createFogDevice(
                "cloud", 44800, 40000, 100, 10000, 0, 0.01,
                16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        // Proxy server (level 1)
        FogDevice proxy = createFogDevice(
                "proxy-server", 2800, 4000, 10000, 10000, 1, 0.0,
                107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100); // 100 ms cloud link
        fogDevices.add(proxy);

        // Areas
        for (int i = 0; i < NUM_AREAS; i++) {
            addArea(String.valueOf(i), userId, appId, proxy.getId());
        }
    }

    private static void addArea(String id, int userId, String appId, int parentId) {
        FogDevice router = createFogDevice(
                "router-" + id, 2800, 4000, 10000, 10000, 1, 0.0,
                107.339, 83.4333);
        router.setUplinkLatency(2); // 2 ms proxy link
        router.setParentId(parentId);
        fogDevices.add(router);

        for (int i = 0; i < NUM_SENSOR_NODES_PER_AREA; i++) {
            FogDevice sensorNode = addSensorNode(id + "-" + i, userId, appId, router.getId());
            sensorNode.setUplinkLatency(2); // 2 ms router link
            fogDevices.add(sensorNode);
        }
    }

    private static FogDevice addSensorNode(String id, int userId, String appId, int parentId) {

        FogDevice node = createFogDevice(
                "sensor-node-" + id, 500, 1000,
                10000, 10000, 3, 0, 87.53, 82.44);
        node.setParentId(parentId);

        // Temperature sensor (every 3 s)
        Sensor tempSensor = new Sensor(
                "temp-" + id, "TEMP", userId, appId,
                new DeterministicDistribution(3));
        tempSensor.setGatewayDeviceId(node.getId());
        tempSensor.setLatency(1.0);
        tempSensor.setAppId(appId);
        tempSensor.setTransmitDistribution(new DeterministicDistribution(3));
        sensors.add(tempSensor);

        // Vibration sensor (every 10 s)
        Sensor vibSensor = new Sensor(
                "vib-" + id, "VIB", userId, appId,
                new DeterministicDistribution(10));
        vibSensor.setGatewayDeviceId(node.getId());
        vibSensor.setLatency(1.0);
        vibSensor.setAppId(appId);
        vibSensor.setTransmitDistribution(new DeterministicDistribution(10));
        sensors.add(vibSensor);

        return node;
    }

    private static FogDevice createFogDevice(
            String nodeName, long mips, int ram,
            long upBw, long downBw, int level,
            double ratePerMips, double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int    hostId  = FogUtils.generateEntityId();
        long   storage = 1_000_000;
        int    bw      = 10_000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage, peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host,
                10.0, 3.0, 0.05, 0.001, 0.0);

        FogDevice fogDevice = null;
        try {
            fogDevice = new FogDevice(nodeName, characteristics,
                    new AppModuleAllocationPolicy(hostList),
                    new LinkedList<Storage>(),
                    10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }

        assert fogDevice != null;
        fogDevice.setLevel(level);
        return fogDevice;
    }

    // ─── Application definition ───────────────────────────────────────────────

    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {

        Application app = Application.createApplication(appId, userId);

        // Modules
        app.addAppModule("data_preprocessor", 50);   // lightweight, edge
        app.addAppModule("analytics",         200);  // heavy, cloud
        app.addAppModule("cloud_storage",     10);   // storage, cloud

        // Edges (sensor → preprocessor)
        app.addAppEdge("TEMP", "data_preprocessor",
                500, 500, "TEMP_DATA", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("VIB", "data_preprocessor",
                300, 300, "VIB_DATA",  Tuple.UP, AppEdge.SENSOR);

        // Edges (module → module)
        app.addAppEdge("data_preprocessor", "analytics",
                2000, 4000, "PROCESSED_DATA", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("analytics", "cloud_storage",
                1000, 1000, "RESULTS",         Tuple.UP, AppEdge.MODULE);

        // Tuple mappings
        app.addTupleMapping("data_preprocessor", "TEMP_DATA",
                "PROCESSED_DATA", new FractionalSelectivity(1.0));
        app.addTupleMapping("data_preprocessor", "VIB_DATA",
                "PROCESSED_DATA", new FractionalSelectivity(1.0));

        // Loop for latency measurement
        app.setLoops(new ArrayList<AppLoop>() {{
            add(new AppLoop(new ArrayList<String>() {{
                add("data_preprocessor");
                add("analytics");
                add("cloud_storage");
            }}));
        }});

        return app;
    }

    // ─── Print helpers ────────────────────────────────────────────────────────

    private static void printTopologySummary() {
        DebugLogger.separator();
        DebugLogger.log("  Fog Device Hierarchy:");
        for (FogDevice dev : fogDevices) {
            String indent = "    " + "  ".repeat(dev.getLevel());
            DebugLogger.log(String.format("%s[Level %d] %-22s | MIPS=%5d | RAM=%5d MB",
                    indent, dev.getLevel(), dev.getName(),
                    (int) dev.getHost().getTotalMips(),
                    dev.getHost().getRam()));
        }
        DebugLogger.separator();
        DebugLogger.log("  Sensors:");
        for (Sensor s : sensors) {
            DebugLogger.log(String.format("    %-20s | Type=%-6s | Gateway=%s",
                    s.getName(), s.getTupleType(),
                    org.cloudbus.cloudsim.core.CloudSim.getEntityName(s.getGatewayDeviceId())));
        }
        DebugLogger.separator();
    }
}
