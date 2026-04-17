package org.fog.test.perfeval;

import org.fog.utils.DebugLogger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.AppModule;
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
import org.fog.placement.ModulePlacement;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.NetworkUsageMonitor;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.fog.placement.custom.FogOffloadingPlacement;

/**
 * CloudOnlyBaseline — runs the SAME IoT-Fog-Cloud setup but routes
 * ALL tasks directly to the cloud with no MOAOA offloading.
 *
 * Purpose: Establish baseline metrics (energy, latency, cost) for
 * comparison against MOAOA results in IndustrialIoTFog.java.
 *
 * Run this FIRST, note the results, then run IndustrialIoTFog.java.
 */
public class CloudOnlyBaseline {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    static final int NUM_AREAS                = 1;
    static final int NUM_SENSOR_NODES_PER_AREA = 4;

    public static void main(String[] args) {

        Log.disable();

        DebugLogger.section("CLOUD-ONLY BASELINE — All Tasks Routed to Cloud");
        DebugLogger.log("  Purpose : Establish baseline metrics before MOAOA optimisation");
        DebugLogger.log("  Topology: Same as IndustrialIoTFog (1 area, 4 sensor nodes)");
        DebugLogger.log("  Policy  : ALL tasks go to cloud — no fog/edge offloading");

        try {
            DebugLogger.step(1, "Initialising CloudSim...");
            CloudSim.init(1, Calendar.getInstance(), false);
            DebugLogger.info("CloudSim", "Initialised");

            DebugLogger.step(2, "Creating broker and application...");
            String appId = "baseline_iot";
            FogBroker broker = new FogBroker("broker");
            Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());

            DebugLogger.step(3, "Creating topology...");
            createFogDevices(broker.getId(), appId);
            DebugLogger.info("Topology", fogDevices.size() + " devices, " + sensors.size() + " sensors");

            DebugLogger.step(4, "Module placement — ALL modules on CLOUD...");
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            // Put ALL modules on cloud — this is the cloud-only baseline
            for (AppModule module : application.getModules()) {
                for (FogDevice dev : fogDevices) {
                    if (dev.getLevel() == 0) { // cloud only
                        moduleMapping.addModuleToDevice(module.getName(), dev.getName());
                        DebugLogger.log(String.format("  %-22s → %-22s [CLOUD — Level 0]",
                                module.getName(), dev.getName()));
                    }
                }
            }

            DebugLogger.step(5, "Creating cloud-only controller...");
            CloudOnlyController controller = new CloudOnlyController(
                    "cloud-only-controller", fogDevices, sensors, actuators);

            for (FogDevice device : fogDevices)
                device.setControllerId(controller.getId());
            for (Sensor sensor : sensors)
                sensor.setApp(application);

            // Use standard placement with the all-cloud mapping
            ModulePlacementEdgewards placement =
                    new ModulePlacementEdgewards(fogDevices, sensors, actuators,
                            application, moduleMapping);

            controller.submitApplication(application, placement);

            DebugLogger.step(6, "Running simulation...");
            TimeKeeper.getInstance().setSimulationStartTime(
                    Calendar.getInstance().getTimeInMillis());
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

        } catch (Exception e) {
            DebugLogger.log("[ERROR] " + e.getMessage());
            e.printStackTrace();
        } finally {
            DebugLogger.close();
        }
    }

    // ─── Topology (identical to IndustrialIoTFog) ─────────────────────────────
    private static void createFogDevices(int userId, String appId) {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0,
                0.01, 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1,
                0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100);
        fogDevices.add(proxy);

        for (int i = 0; i < NUM_AREAS; i++)
            addArea(String.valueOf(i), userId, appId, proxy.getId());
    }

    private static void addArea(String id, int userId, String appId, int parentId) {
        FogDevice router = createFogDevice("router-" + id, 2800, 4000, 10000, 10000, 1,
                0.0, 107.339, 83.4333);
        router.setUplinkLatency(2);
        router.setParentId(parentId);
        fogDevices.add(router);

        for (int i = 0; i < NUM_SENSOR_NODES_PER_AREA; i++) {
            FogDevice node = createFogDevice("sensor-node-" + id + "-" + i,
                    500, 1000, 10000, 10000, 3, 0, 87.53, 82.44);
            node.setParentId(router.getId());
            node.setUplinkLatency(2);
            fogDevices.add(node);

            Sensor temp = new Sensor("temp-" + id + "-" + i, "TEMP", userId, appId,
                    new DeterministicDistribution(3));
            temp.setGatewayDeviceId(node.getId());
            temp.setLatency(1.0);
            temp.setAppId(appId);
            temp.setTransmitDistribution(new DeterministicDistribution(3));
            sensors.add(temp);

            Sensor vib = new Sensor("vib-" + id + "-" + i, "VIB", userId, appId,
                    new DeterministicDistribution(10));
            vib.setGatewayDeviceId(node.getId());
            vib.setLatency(1.0);
            vib.setAppId(appId);
            vib.setTransmitDistribution(new DeterministicDistribution(10));
            sensors.add(vib);
        }
    }

    private static FogDevice createFogDevice(String name, long mips, int ram,
            long upBw, long downBw, int level, double ratePerMips,
            double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));
        PowerHost host = new PowerHost(FogUtils.generateEntityId(),
                new RamProvisionerSimple(ram), new BwProvisionerOverbooking(10000),
                1000000, peList, new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower));
        List<Host> hostList = new ArrayList<>();
        hostList.add(host);
        FogDeviceCharacteristics chars = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
        FogDevice dev = null;
        try {
            dev = new FogDevice(name, chars, new AppModuleAllocationPolicy(hostList),
                    new LinkedList<Storage>(), 10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) { e.printStackTrace(); }
        dev.setLevel(level);
        return dev;
    }

    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);
        app.addAppModule("data_preprocessor", 50);
        app.addAppModule("analytics",         200);
        app.addAppModule("cloud_storage",     10);

        app.addAppEdge("TEMP", "data_preprocessor",
                500, 500, "TEMP_DATA", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("VIB",  "data_preprocessor",
                300, 300, "VIB_DATA",  Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("data_preprocessor", "analytics",
                2000, 4000, "PROCESSED_DATA", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("analytics", "cloud_storage",
                1000, 1000, "RESULTS", Tuple.UP, AppEdge.MODULE);

        app.addTupleMapping("data_preprocessor", "TEMP_DATA", "PROCESSED_DATA",
                new FractionalSelectivity(1.0));
        app.addTupleMapping("data_preprocessor", "VIB_DATA",  "PROCESSED_DATA",
                new FractionalSelectivity(1.0));

        app.setLoops(new ArrayList<AppLoop>() {{
            add(new AppLoop(new ArrayList<String>() {{
                add("data_preprocessor");
                add("analytics");
                add("cloud_storage");
            }}));
        }});
        return app;
    }
}

/**
 * CloudOnlyController — sends ALL tasks to cloud, no MOAOA.
 * Identical to Controller but overrides task handling.
 */
class CloudOnlyController extends SimEntity {

    private List<FogDevice>  fogDevices;
    private List<Sensor>     sensors;
    private List<Actuator>   actuators;
    private Map<String, Application>     applications     = new HashMap<>();
    private Map<String, Integer>         appLaunchDelays  = new HashMap<>();
    private Map<String, ModulePlacement> appModulePlacementPolicy = new HashMap<>();

    private int  totalTasksReceived = 0;
    private int  totalCloudTasks    = 0;
    private double totalDelay  = 0;
    private double totalEnergy = 0;

    public CloudOnlyController(String name,
                               List<FogDevice> fogDevices,
                               List<Sensor> sensors,
                               List<Actuator> actuators) {
        super(name);
        this.fogDevices = fogDevices;
        this.sensors    = sensors;
        this.actuators  = actuators;

        for (FogDevice d : fogDevices)
            d.setControllerId(getId());

        // Connect parent-child latencies
        for (FogDevice dev : fogDevices) {
            FogDevice parent = getById(dev.getParentId());
            if (parent == null) continue;
            parent.getChildToLatencyMap().put(dev.getId(), dev.getUplinkLatency());
            parent.getChildrenIds().add(dev.getId());
        }
    }

    @Override
    public void startEntity() {
        for (String appId : applications.keySet()) {
            if (appLaunchDelays.get(appId) == 0) submitApp(applications.get(appId));
            else send(getId(), appLaunchDelays.get(appId), FogEvents.APP_SUBMIT,
                    applications.get(appId));
        }
        send(getId(), Config.RESOURCE_MANAGE_INTERVAL,
                FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
        send(getId(), Config.MAX_SIMULATION_TIME, FogEvents.STOP_SIMULATION, null);
        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.RESOURCE_MGMT);
    }

    @Override
    public void processEvent(SimEvent ev) {
        FogEvents tag = (FogEvents) ev.getTag();
        switch (tag) {
            case TUPLE_ARRIVAL:
                handleTask(ev);
                break;
            case APP_SUBMIT:
                submitApp((Application) ev.getData());
                break;
            case CONTROLLER_RESOURCE_MANAGE:
                send(getId(), Config.RESOURCE_MANAGE_INTERVAL,
                        FogEvents.CONTROLLER_RESOURCE_MANAGE, null);
                break;
            case STOP_SIMULATION:
                CloudSim.stopSimulation();
                printBaselineResults();
                System.exit(0);
                break;
            default: break;
        }
    }

    @Override public void shutdownEntity() {}

    private void handleTask(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        if (tuple.getUserId() == -1) return; // already processed

        totalTasksReceived++;

        // ALL tasks → cloud
        FogDevice cloud = getCloudDevice();
        if (cloud == null) return;

        double delay  = tuple.getCloudletLength() / 44800.0
                      + tuple.getCloudletFileSize() / 100.0;
        double energy = tuple.getCloudletLength() * 1.5;

        totalDelay  += delay;
        totalEnergy += energy;
        totalCloudTasks++;

        DebugLogger.log(String.format(
                "  [TASK → CLOUD] #%-4d %-8s CPU=%-6.0f delay=%-8.4f energy=%-8.4f",
                totalTasksReceived,
                tuple.getTupleType(),
                (double) tuple.getCloudletLength(),
                delay, energy));

        tuple.setDestModuleName(cloud.getName());
        tuple.setUserId(-1);
        sendNow(cloud.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
    }

    private void printBaselineResults() {
        long execTime = Calendar.getInstance().getTimeInMillis()
                - TimeKeeper.getInstance().getSimulationStartTime();

        double totalDeviceEnergy = 0;
        double totalDeviceCost   = 0;
        for (FogDevice dev : fogDevices) {
            totalDeviceEnergy += dev.getEnergyConsumption();
            totalDeviceCost   += dev.getTotalCost();
        }

        DebugLogger.section("CLOUD-ONLY BASELINE RESULTS");
        DebugLogger.log(String.format("  %-30s : %d", "Total Tasks Received", totalTasksReceived));
        DebugLogger.log(String.format("  %-30s : %d (100%%)", "Tasks sent to CLOUD", totalCloudTasks));
        DebugLogger.log(String.format("  %-30s : 0 (0%%)",   "Tasks offloaded to FOG"));
        DebugLogger.log(String.format("  %-30s : 0 (0%%)",   "Tasks offloaded to EDGE"));
        DebugLogger.separator();
        DebugLogger.result("  Total Delay (ms)",         String.format("%.4f", totalDelay));
        DebugLogger.result("  Total Energy Computed (J)", String.format("%.4f", totalEnergy));
        DebugLogger.result("  Total Device Energy (J)",   String.format("%.4f", totalDeviceEnergy));
        DebugLogger.result("  Total Cost ($)",            String.format("%.6f", totalDeviceCost));
        DebugLogger.separator();
        DebugLogger.log("  ► Record these values to compare against MOAOA in IndustrialIoTFog.java");
        DebugLogger.result("  Wall Clock Time", execTime + " ms");
        DebugLogger.section("END OF CLOUD-ONLY BASELINE");
    }

    public void submitApplication(Application application, ModulePlacement placement) {
        FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
        applications.put(application.getAppId(), application);
        appLaunchDelays.put(application.getAppId(), 0);
        appModulePlacementPolicy.put(application.getAppId(), placement);

        for (Sensor s : sensors) s.setApp(application);
        for (Actuator a : actuators) a.setApp(application);
    }

    private void submitApp(Application application) {
        FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
        applications.put(application.getAppId(), application);
        ModulePlacement placement = appModulePlacementPolicy.get(application.getAppId());

        for (FogDevice dev : fogDevices)
            sendNow(dev.getId(), FogEvents.ACTIVE_APP_UPDATE, application);

        if (placement != null) {
            Map<Integer, List<AppModule>> map = placement.getDeviceToModuleMap();
            for (Integer deviceId : map.keySet()) {
                for (AppModule module : map.get(deviceId)) {
                    sendNow(deviceId, FogEvents.APP_SUBMIT,    application);
                    sendNow(deviceId, FogEvents.LAUNCH_MODULE, module);
                }
            }
        }
    }

    private FogDevice getCloudDevice() {
        for (FogDevice dev : fogDevices)
            if (dev.getLevel() == 0) return dev;
        return null;
    }

    private FogDevice getById(int id) {
        for (FogDevice d : fogDevices)
            if (d.getId() == id) return d;
        return null;
    }
}
