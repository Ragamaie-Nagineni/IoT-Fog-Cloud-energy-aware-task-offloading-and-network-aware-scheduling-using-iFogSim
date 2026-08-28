package org.fog.test.perfeval;

import org.fog.utils.DebugLogger;
import java.io.BufferedReader;
import java.io.FileReader;
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
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * VehicularFogSimulation
 *
 * Evaluation 3 : MoAOA task offloading in 5G Vehicular Edge Computing
 *
 * Architecture :
 *   Cloud  (level 0) → 44800 MIPS, 40000 MB RAM
 *   RSU/MEC (level 2) → 5000 MIPS, 8000 MB RAM
 *   Vehicles (level 3) → 500 MIPS, 1000 MB RAM
 *
 * Dataset : vehicular_dataset.csv
 *   200 vehicles, 10000 tasks
 *   Task types : SAFETY, TRAFFIC, INFOTAINMENT
 */
public class VehicularFogSimulation {

    // ─── Lists ────────────────────────────────────────────────────────────────
    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

 // ─── Dataset path ─────────────────────────────────────────────────────────
    static final String DATASET_PATH = "dataset/vehicular_dataset.csv";

    // ─── RSU count ────────────────────────────────────────────────────────────
    static final int NUM_RSU = 5;

    // ─── Simulation time ──────────────────────────────────────────────────────
    static final double SIM_TIME = 100.0;  
    // ─── Main ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {

        Log.disable();

        DebugLogger.section("MoAOA Task Offloading in 5G Vehicular Edge Computing");

        try {
            // STEP 1: Initialise CloudSim
            DebugLogger.step(1, "Initialising CloudSim...");
            CloudSim.init(1, Calendar.getInstance(), false);
            DebugLogger.info("CloudSim", "Initialised successfully");

            // STEP 2: Create application
            DebugLogger.step(2, "Creating application...");
            String appId = "vehicular_iot";
            FogBroker broker = new FogBroker("broker");
            Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());
            DebugLogger.info("Application", "Created with 3 modules: " +
                "safety_processor, traffic_processor, info_processor");

            // STEP 3: Create topology
            DebugLogger.step(3, "Creating vehicular topology...");
            createTopology(broker.getId(), appId);
            DebugLogger.info("Topology", "Fog devices : " + fogDevices.size());
            DebugLogger.info("Topology", "Sensors     : " + sensors.size());
            printTopology();

            // STEP 4: Module placement
            DebugLogger.step(4, "Placing modules...");
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            FogOffloadingPlacement placement = new FogOffloadingPlacement(
                fogDevices, sensors, actuators, application, moduleMapping);

            // STEP 5: Create controller
            DebugLogger.step(5, "Creating controller...");
            Controller controller = new Controller(
                "vehicular-controller", fogDevices, sensors, actuators);
            for (FogDevice d : fogDevices) d.setControllerId(controller.getId());
            for (Sensor s : sensors) s.setApp(application);
            controller.submitApplication(application, placement);
            DebugLogger.info("Controller", "Vehicular controller created");

            // STEP 6: Run simulation
            DebugLogger.step(6, "Starting simulation...");
            TimeKeeper.getInstance().setSimulationStartTime(
                Calendar.getInstance().getTimeInMillis());
            CloudSim.startSimulation();
            CloudSim.stopSimulation();
            DebugLogger.info("Simulation", "Completed successfully");

        } catch (Exception e) {
            DebugLogger.log("[ERROR] " + e.getMessage());
            e.printStackTrace();
        } finally {
            DebugLogger.close();
        }
    }

    // ─── Create Topology ──────────────────────────────────────────────────────
    private static void createTopology(int userId, String appId) throws Exception {

        // Cloud (level 0)
        FogDevice cloud = createFogDevice(
            "cloud", 44800, 40000, 100, 10000, 0, 0.01,
            16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        // RSU/MEC servers (level 2) - NEW
        for (int i = 0; i < NUM_RSU; i++) {
            FogDevice rsu = createFogDevice(
                "RSU-" + i, 5000, 8000, 10000, 10000, 2, 0.0,
                107.339, 83.4333);
            rsu.setParentId(cloud.getId());
            rsu.setUplinkLatency(0.5); // 5G = 0.5ms
            fogDevices.add(rsu);
        }

        // Read vehicles from CSV dataset
        DebugLogger.info("DATASET", "Reading from " + DATASET_PATH);
        readVehiclesFromCSV(userId, appId);
    }

    // ─── Read CSV Dataset ─────────────────────────────────────────────────────
    private static void readVehiclesFromCSV(int userId, String appId)
            throws Exception {

        BufferedReader br = new BufferedReader(new FileReader(DATASET_PATH));
        String line;
        boolean firstLine = true;

        // Track unique vehicles
        List<String> addedVehicles = new ArrayList<>();

        // Track task counts
        int safetyCount = 0, trafficCount = 0, infoCount = 0;

        // Get RSU devices for parent assignment
        List<FogDevice> rsuDevices = new ArrayList<>();
        for (FogDevice d : fogDevices) {
            if (d.getLevel() == 2) rsuDevices.add(d);
        }

        int rsuIndex = 0;

        while ((line = br.readLine()) != null) {

            // Skip header
            if (firstLine) { firstLine = false; continue; }

            String[] col = line.split(",");

            // Parse CSV columns
            String vehicleId        = col[0];
            double speed            = Double.parseDouble(col[1]);
            double signalStrength   = Double.parseDouble(col[2]);
            double txPower          = Double.parseDouble(col[3]);
            double battery          = Double.parseDouble(col[4]);
            double distanceToRsu    = Double.parseDouble(col[5]);
            String taskType         = col[6];
            double taskSize         = Double.parseDouble(col[7]);
            double deadline         = Double.parseDouble(col[8]);

            // Create vehicle node if not already created
            if (!addedVehicles.contains(vehicleId)) {
                addedVehicles.add(vehicleId);

                // Assign RSU as parent (round robin)
                FogDevice parentRSU = rsuDevices.get(rsuIndex % rsuDevices.size());
                rsuIndex++;

                // Create vehicle fog device (level 3)
                FogDevice vehicle = createFogDevice(
                    "vehicle-" + vehicleId, 500, 1000,
                    10000, 10000, 3, 0, 87.53, 82.44);
                vehicle.setParentId(parentRSU.getId());
                vehicle.setUplinkLatency(2.0);
                fogDevices.add(vehicle);

                // Create sensor based on task type
                // Transmission interval based on deadline
                double interval;
                if (taskType.equals("SAFETY")) {
                    interval = 3.0;
                } else if (taskType.equals("TRAFFIC")) {
                    interval = 5.0;
                } else {
                    interval = 10.0;
                }

                if (taskType.equals("SAFETY")) {
                    Sensor s = new Sensor(
                        "sensor-" + vehicleId, "SAFETY",
                        userId, appId,
                        new DeterministicDistribution(interval));
                    s.setGatewayDeviceId(vehicle.getId());
                    s.setLatency(1.0);
                    s.setAppId(appId);
                    s.setTransmitDistribution(
                        new DeterministicDistribution(interval));
                    sensors.add(s);
                    safetyCount++;

                } else if (taskType.equals("TRAFFIC")) {
                    Sensor s = new Sensor(
                        "sensor-" + vehicleId, "TRAFFIC",
                        userId, appId,
                        new DeterministicDistribution(interval));
                    s.setGatewayDeviceId(vehicle.getId());
                    s.setLatency(1.0);
                    s.setAppId(appId);
                    s.setTransmitDistribution(
                        new DeterministicDistribution(interval));
                    sensors.add(s);
                    trafficCount++;

                } else {
                    Sensor s = new Sensor(
                        "sensor-" + vehicleId, "INFOTAINMENT",
                        userId, appId,
                        new DeterministicDistribution(interval));
                    s.setGatewayDeviceId(vehicle.getId());
                    s.setLatency(1.0);
                    s.setAppId(appId);
                    s.setTransmitDistribution(
                        new DeterministicDistribution(interval));
                    sensors.add(s);
                    infoCount++;
                }
            }
        }
        br.close();

        DebugLogger.info("DATASET", "Vehicles loaded  : " + addedVehicles.size());
        DebugLogger.info("DATASET", "SAFETY sensors   : " + safetyCount);
        DebugLogger.info("DATASET", "TRAFFIC sensors  : " + trafficCount);
        DebugLogger.info("DATASET", "INFO sensors     : " + infoCount);
    }

    // ─── Create Application ───────────────────────────────────────────────────
    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {

        Application app = Application.createApplication(appId, userId);

        // Modules
        app.addAppModule("safety_processor",  50);   // on vehicle (edge)
        app.addAppModule("traffic_processor", 200);  // on RSU
        app.addAppModule("info_processor",    500);  // on cloud

        // Edges — sensor to module
        app.addAppEdge("SAFETY", "safety_processor",
            200, 200, "SAFETY_DATA", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("TRAFFIC", "traffic_processor",
            500, 500, "TRAFFIC_DATA", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("INFOTAINMENT", "info_processor",
            2000, 2000, "INFO_DATA", Tuple.UP, AppEdge.SENSOR);

        // Tuple mappings
        app.addTupleMapping("safety_processor", "SAFETY_DATA",
            "SAFETY_RESULT", new FractionalSelectivity(1.0));
        app.addTupleMapping("traffic_processor", "TRAFFIC_DATA",
            "TRAFFIC_RESULT", new FractionalSelectivity(1.0));
        app.addTupleMapping("info_processor", "INFO_DATA",
            "INFO_RESULT", new FractionalSelectivity(1.0));

        // Loop
        app.setLoops(new ArrayList<AppLoop>() {{
            add(new AppLoop(new ArrayList<String>() {{
                add("safety_processor");
                add("traffic_processor");
                add("info_processor");
            }}));
        }});

        return app;
    }

    // ─── Create Fog Device ────────────────────────────────────────────────────
    private static FogDevice createFogDevice(
            String name, long mips, int ram,
            long upBw, long downBw, int level,
            double ratePerMips, double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        PowerHost host = new PowerHost(
            FogUtils.generateEntityId(),
            new RamProvisionerSimple(ram),
            new BwProvisionerOverbooking(10000),
            1000000, peList,
            new StreamOperatorScheduler(peList),
            new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics chars = new FogDeviceCharacteristics(
            "x86", "Linux", "Xen", host,
            10.0, 3.0, 0.05, 0.001, 0.0);

        FogDevice dev = null;
        try {
            dev = new FogDevice(name, chars,
                new AppModuleAllocationPolicy(hostList),
                new LinkedList<Storage>(),
                10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }
        dev.setLevel(level);
        return dev;
    }

    // ─── Print Topology ───────────────────────────────────────────────────────
    private static void printTopology() {
        DebugLogger.separator();
        DebugLogger.log("  Vehicular Topology:");
        for (FogDevice d : fogDevices) {
            String indent = "    " + "  ".repeat(d.getLevel());
            DebugLogger.log(String.format(
                "%s[Level %d] %-25s | MIPS=%5d | RAM=%5d MB",
                indent, d.getLevel(), d.getName(),
                (int) d.getHost().getTotalMips(),
                d.getHost().getRam()));
        }
        DebugLogger.separator();
    }
}