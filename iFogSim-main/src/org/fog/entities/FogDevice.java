package org.fog.entities;

import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.mobilitydata.Clustering;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.*;
import org.json.simple.JSONObject;

import java.util.*;

/**
 * FogDevice — represents one computational node in the IoT-Fog-Cloud hierarchy.
 *
 * Levels:
 *   0 = Cloud server
 *   1 = Fog/proxy node
 *   3 = Edge sensor node
 *
 * Each device receives tuples (tasks) and forwards them to the Controller
 * for MOAOA scheduling decisions.
 */
public class FogDevice extends PowerDatacenter {

    // ─── Queues ───────────────────────────────────────────────────────────────
    protected Queue<Tuple>                northTupleQueue;
    protected Queue<Pair<Tuple, Integer>> southTupleQueue;
    protected List<Tuple>                 schedulingQueue = new ArrayList<>();

    // ─── Application state ────────────────────────────────────────────────────
    protected List<String>                     activeApplications;
    protected Map<String, Application>          applicationMap;
    protected Map<String, List<String>>         appToModulesMap;
    protected Map<Integer, Double>              childToLatencyMap;
    protected Map<Integer, Integer>             cloudTrafficMap;
    protected Map<Integer, List<String>>        childToOperatorsMap;
    protected Map<String, Map<String, Integer>> moduleInstanceCount;

    // ─── Network topology ─────────────────────────────────────────────────────
    protected int          parentId;
    protected int          controllerId;
    protected List<Integer> childrenIds;
    protected List<Pair<Integer, Double>> associatedActuatorIds;

    protected boolean isSouthLinkBusy;
    protected boolean isNorthLinkBusy;
    protected double  uplinkBandwidth;
    protected double  downlinkBandwidth;
    protected double  uplinkLatency;

    // ─── Energy / cost tracking ───────────────────────────────────────────────
    protected double energyConsumption;
    protected double lastUtilizationUpdateTime;
    protected double lastUtilization;
    protected double totalCost;
    protected double ratePerMips;
    protected double lockTime;
    private   int    level;

    // ─── Clustering (optional, kept for compatibility) ────────────────────────
    protected List<Integer>       clusterMembers             = new ArrayList<>();
    protected boolean             isInCluster                = false;
    protected boolean             selfCluster                = false;
    protected Map<Integer, Double> clusterMembersToLatencyMap;
    protected Queue<Pair<Tuple, Integer>> clusterTupleQueue;
    protected boolean             isClusterLinkBusy;
    protected double              clusterLinkBandwidth;

    // ─── Constructor (full) ───────────────────────────────────────────────────

    public FogDevice(
            String name,
            FogDeviceCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy,
            List<Storage> storageList,
            double schedulingInterval,
            double uplinkBandwidth,
            double downlinkBandwidth,
            double uplinkLatency,
            double ratePerMips) throws Exception {

        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);

        setCharacteristics(characteristics);
        setVmAllocationPolicy(vmAllocationPolicy);
        setLastProcessTime(0.0);
        setStorageList(storageList);
        setVmList(new ArrayList<Vm>());
        setSchedulingInterval(schedulingInterval);
        setUplinkBandwidth(uplinkBandwidth);
        setDownlinkBandwidth(downlinkBandwidth);
        setUplinkLatency(uplinkLatency);
        setRatePerMips(ratePerMips);
        setAssociatedActuatorIds(new ArrayList<>());

        for (HostEntity host : getCharacteristics().getHostList())
            host.setDatacenter(this);

        setActiveApplications(new ArrayList<>());

        if (getCharacteristics().getNumberOfPes() == 0)
            throw new Exception(getName() + ": No PEs — cannot process cloudlets.");

        getCharacteristics().setId(super.getId());

        applicationMap   = new HashMap<>();
        appToModulesMap  = new HashMap<>();
        northTupleQueue  = new LinkedList<>();
        southTupleQueue  = new LinkedList<>();
        setNorthLinkBusy(false);
        setSouthLinkBusy(false);

        setChildrenIds(new ArrayList<>());
        setChildToOperatorsMap(new HashMap<>());

        this.cloudTrafficMap = new HashMap<>();
        this.lockTime        = 0;
        this.energyConsumption = 0;
        this.lastUtilization   = 0;
        setTotalCost(0);
        setModuleInstanceCount(new HashMap<>());
        setChildToLatencyMap(new HashMap<>());

        clusterTupleQueue = new LinkedList<>();
        setClusterLinkBusy(false);
    }

    // ─── Constructor (simplified, for tests) ─────────────────────────────────

    public FogDevice(
            String name, long mips, int ram,
            double uplinkBandwidth, double downlinkBandwidth,
            double ratePerMips, PowerModel powerModel) throws Exception {

        super(name, null, null, new LinkedList<Storage>(), 0);

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
                powerModel);

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);
        setVmAllocationPolicy(new AppModuleAllocationPolicy(hostList));

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                Config.FOG_DEVICE_ARCH, Config.FOG_DEVICE_OS, Config.FOG_DEVICE_VMM,
                host, Config.FOG_DEVICE_TIMEZONE,
                Config.FOG_DEVICE_COST, Config.FOG_DEVICE_COST_PER_MEMORY,
                Config.FOG_DEVICE_COST_PER_STORAGE, Config.FOG_DEVICE_COST_PER_BW);

        setCharacteristics(characteristics);
        setLastProcessTime(0.0);
        setVmList(new ArrayList<>());
        setUplinkBandwidth(uplinkBandwidth);
        setDownlinkBandwidth(downlinkBandwidth);
        setUplinkLatency(uplinkLatency);
        setAssociatedActuatorIds(new ArrayList<>());

        for (HostEntity h : getCharacteristics().getHostList())
            h.setDatacenter(this);

        setActiveApplications(new ArrayList<>());

        if (getCharacteristics().getNumberOfPes() == 0)
            throw new Exception(getName() + ": No PEs.");

        getCharacteristics().setId(super.getId());

        applicationMap   = new HashMap<>();
        appToModulesMap  = new HashMap<>();
        northTupleQueue  = new LinkedList<>();
        southTupleQueue  = new LinkedList<>();
        setNorthLinkBusy(false);
        setSouthLinkBusy(false);
        setChildrenIds(new ArrayList<>());
        setChildToOperatorsMap(new HashMap<>());

        this.cloudTrafficMap   = new HashMap<>();
        this.lockTime          = 0;
        this.energyConsumption = 0;
        this.lastUtilization   = 0;
        setTotalCost(0);
        setChildToLatencyMap(new HashMap<>());
        setModuleInstanceCount(new HashMap<>());

        clusterTupleQueue = new LinkedList<>();
        setClusterLinkBusy(false);
    }

    // ─── Event handling ───────────────────────────────────────────────────────

    @Override
    protected void registerOtherEntity() {}

    @Override
    protected void processOtherEvent(SimEvent ev) {
        FogEvents tag = (FogEvents) ev.getTag();
        switch (tag) {
            case TUPLE_ARRIVAL:
                processTupleArrival(ev);
                break;
            case LAUNCH_MODULE:
                processModuleArrival(ev);
                break;
            case RELEASE_OPERATOR:
                processOperatorRelease(ev);
                break;
            case SENSOR_JOINED:
                processSensorJoining(ev);
                break;
            case SEND_PERIODIC_TUPLE:
                sendPeriodicTuple(ev);
                break;
            case APP_SUBMIT:
                processAppSubmit(ev);
                break;
            case UPDATE_NORTH_TUPLE_QUEUE:
                updateNorthTupleQueue();
                break;
            case UPDATE_SOUTH_TUPLE_QUEUE:
                updateSouthTupleQueue();
                break;
            case ACTIVE_APP_UPDATE:
                updateActiveApplications(ev);
                break;
            case ACTUATOR_JOINED:
                processActuatorJoined(ev);
                break;
            case LAUNCH_MODULE_INSTANCE:
                updateModuleInstanceCount(ev);
                break;
            case MODULE_SEND:
                moduleSend(ev);
                break;
            case MODULE_RECEIVE:
                moduleReceive(ev);
                break;
            case RELEASE_MODULE:
                processModuleTermination(ev);
                break;
            case RESOURCE_MGMT:
                manageResources(ev);
                break;
            case UPDATE_CLUSTER_TUPLE_QUEUE:
                updateClusterTupleQueue();
                break;
            case START_DYNAMIC_CLUSTERING:
                processClustering(this.getParentId(), this.getId(), ev);
                break;
            default:
                break;
        }
    }

    // ─── Tuple arrival — forward to Controller for MOAOA decision ────────────

    /**
     * All tuples from sensors are forwarded to the Controller.
     * The Controller runs MOAOA and sends them back with a target device assigned.
     * When a tuple returns from the Controller (source == controllerId), execute it.
     */
    protected void processTupleArrival(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();

        // Tuple returning from Controller → execute locally
        if (ev.getSource() == getControllerId()) {
            DebugLogger.info("EXECUTE",
                    getName() + " running " + tuple.getTupleType()
                            + " [CPU=" + tuple.getCloudletLength() + " MI]");
            return;
        }

        // First arrival from sensor/upstream → tag source device and forward
        tuple.setSourceDeviceId(getId());
        sendNow(getControllerId(), FogEvents.TUPLE_ARRIVAL, tuple);
    }

    // ─── Module management ────────────────────────────────────────────────────

    protected void moduleSend(SimEvent ev) {
        JSONObject object  = (JSONObject) ev.getData();
        AppModule  module  = (AppModule)  object.get("module");
        NetworkUsageMonitor.sendingModule((double) object.get("delay"), module.getSize());
        MigrationDelayMonitor.setMigrationDelay((double) object.get("delay"));
        sendNow(getId(), FogEvents.RELEASE_MODULE, module);
    }

    protected void moduleReceive(SimEvent ev) {
        JSONObject object  = (JSONObject) ev.getData();
        AppModule  module  = (AppModule)  object.get("module");
        Application app    = (Application) object.get("application");
        NetworkUsageMonitor.sendingModule((double) object.get("delay"), module.getSize());
        MigrationDelayMonitor.setMigrationDelay((double) object.get("delay"));
        sendNow(getId(), FogEvents.APP_SUBMIT,    app);
        sendNow(getId(), FogEvents.LAUNCH_MODULE, module);
    }

    protected void manageResources(SimEvent ev) {
        updateEnergyConsumption();
        send(getId(), Config.RESOURCE_MGMT_INTERVAL, FogEvents.RESOURCE_MGMT, null);
    }

    protected void updateModuleInstanceCount(SimEvent ev) {
        ModuleLaunchConfig config = (ModuleLaunchConfig) ev.getData();
        String appId = config.getModule().getAppId();
        if (!moduleInstanceCount.containsKey(appId))
            moduleInstanceCount.put(appId, new HashMap<>());
        moduleInstanceCount.get(appId).put(
                config.getModule().getName(), config.getInstanceCount());
    }

    private AppModule getModuleByName(String moduleName) {
        for (Vm vm : getHost().getVmList())
            if (((AppModule) vm).getName().equals(moduleName))
                return (AppModule) vm;
        return null;
    }

    protected void sendPeriodicTuple(SimEvent ev) {
        AppEdge edge = (AppEdge) ev.getData();
        AppModule module = getModuleByName(edge.getSource());
        if (module == null) return;

        int instanceCount = module.getNumInstances();
        for (int i = 0; i < ((edge.getDirection() == Tuple.UP) ? instanceCount : 1); i++) {
            Tuple tuple = applicationMap.get(module.getAppId())
                    .createTuple(edge, getId(), module.getId());
            updateTimingsOnSending(tuple);
            sendToSelf(tuple);
        }
        send(getId(), edge.getPeriodicity(), FogEvents.SEND_PERIODIC_TUPLE, edge);
    }

    protected void processActuatorJoined(SimEvent ev) {
        int actuatorId = ev.getSource();
        double delay   = (double) ev.getData();
        getAssociatedActuatorIds().add(new Pair<>(actuatorId, delay));
    }

    protected void updateActiveApplications(SimEvent ev) {
        Application app = (Application) ev.getData();
        getActiveApplications().add(app.getAppId());
    }

    public String getOperatorName(int vmId) {
        for (Vm vm : getHost().getVmList())
            if (vm.getId() == vmId) return ((AppModule) vm).getName();
        return null;
    }

    // ─── Cloudlet processing ──────────────────────────────────────────────────

    protected double updateCloudetProcessingWithoutSchedulingFutureEventsForce() {
        double currentTime = CloudSim.clock();
        double minTime     = Double.MAX_VALUE;
        double timeDiff    = currentTime - getLastProcessTime();
        double timeFrameDatacenterEnergy = 0.0;

        for (PowerHost host : this.<PowerHost>getHostList()) {
            double time = host.updateCloudletsProcessing(currentTime);
            if (time < minTime) minTime = time;
        }

        if (timeDiff > 0) {
            for (PowerHost host : this.<PowerHost>getHostList()) {
                double prevUtil = host.getPreviousUtilizationOfCpu();
                double util     = host.getUtilizationOfCpu();
                timeFrameDatacenterEnergy +=
                        host.getEnergyLinearInterpolation(prevUtil, util, timeDiff);
            }
        }

        setPower(getPower() + timeFrameDatacenterEnergy);
        checkCloudletCompletion();
        Log.printLine();
        setLastProcessTime(currentTime);
        return minTime;
    }

    protected void checkCloudletCompletion() {
        boolean cloudletCompleted = false;
        for (Host host : getVmAllocationPolicy().getHostList()) {
            for (Vm vm : host.getVmList()) {
                while (vm.getCloudletScheduler().isFinishedCloudlets()) {
                    Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
                    if (cl != null) {
                        cloudletCompleted = true;
                        Tuple tuple = (Tuple) cl;
                        TimeKeeper.getInstance().tupleEndedExecution(tuple);
                        Application application =
                                getApplicationMap().get(tuple.getAppId());
                        List<Tuple> resultantTuples = application.getResultantTuples(
                                tuple.getDestModuleName(), tuple, getId(), vm.getId());
                        for (Tuple resTuple : resultantTuples) {
                            resTuple.setModuleCopyMap(
                                    new HashMap<>(tuple.getModuleCopyMap()));
                            resTuple.getModuleCopyMap().put(
                                    ((AppModule) vm).getName(), vm.getId());
                            updateTimingsOnSending(resTuple);
                            sendToSelf(resTuple);
                        }
                        sendNow(cl.getUserId(), CloudActionTags.CLOUDLET_RETURN, cl);
                    }
                }
            }
        }
        if (cloudletCompleted) updateAllocatedMips(null);
    }

    protected void updateTimingsOnSending(Tuple resTuple) {
        String srcModule  = resTuple.getSrcModuleName();
        String destModule = resTuple.getDestModuleName();
        for (AppLoop loop : getApplicationMap().get(resTuple.getAppId()).getLoops()) {
            if (loop.hasEdge(srcModule, destModule) && loop.isStartModule(srcModule)) {
                int tupleId = TimeKeeper.getInstance().getUniqueId();
                resTuple.setActualTupleId(tupleId);
                if (!TimeKeeper.getInstance().getLoopIdToTupleIds()
                        .containsKey(loop.getLoopId()))
                    TimeKeeper.getInstance().getLoopIdToTupleIds()
                            .put(loop.getLoopId(), new ArrayList<>());
                TimeKeeper.getInstance().getLoopIdToTupleIds()
                        .get(loop.getLoopId()).add(tupleId);
                TimeKeeper.getInstance().getEmitTimes()
                        .put(tupleId, CloudSim.clock());
            }
        }
    }

    protected int getChildIdWithRouteTo(int targetDeviceId) {
        for (Integer childId : getChildrenIds()) {
            if (targetDeviceId == childId) return childId;
            if (((FogDevice) CloudSim.getEntity(childId))
                    .getChildIdWithRouteTo(targetDeviceId) != -1)
                return childId;
        }
        return -1;
    }

    protected int getChildIdForTuple(Tuple tuple) {
        if (tuple.getDirection() == Tuple.ACTUATOR) {
            int gatewayId =
                    ((Actuator) CloudSim.getEntity(tuple.getActuatorId()))
                            .getGatewayDeviceId();
            return getChildIdWithRouteTo(gatewayId);
        }
        return -1;
    }

    protected void updateAllocatedMips(String incomingOperator) {
        getHost().getVmScheduler().deallocatePesForAllVms();
        for (final Vm vm : getHost().getVmList()) {
            if (vm.getCloudletScheduler().runningCloudlets() > 0
                    || ((AppModule) vm).getName().equals(incomingOperator)) {
                getHost().getVmScheduler().allocatePesForVm(vm,
                        new ArrayList<Double>() {{
                            add((double) getHost().getTotalMips());
                        }});
            } else {
                getHost().getVmScheduler().allocatePesForVm(vm,
                        new ArrayList<Double>() {{
                            add(0.0);
                        }});
            }
        }
        updateEnergyConsumption();
    }

    private void updateEnergyConsumption() {
        double totalMipsAllocated = 0;
        for (final Vm vm : getHost().getVmList()) {
            AppModule operator = (AppModule) vm;
            operator.updateVmProcessing(CloudSim.clock(),
                    getVmAllocationPolicy().getHost(operator)
                            .getVmScheduler().getAllocatedMipsForVm(operator));
            totalMipsAllocated +=
                    getHost().getTotalAllocatedMipsForVm(vm);
        }

        double timeNow = CloudSim.clock();
        double newEnergy = getEnergyConsumption()
                + (timeNow - lastUtilizationUpdateTime)
                * getHost().getPowerModel().getPower(lastUtilization);
        setEnergyConsumption(newEnergy);

        double newCost = getTotalCost()
                + (timeNow - lastUtilizationUpdateTime)
                * getRatePerMips() * lastUtilization * getHost().getTotalMips();
        setTotalCost(newCost);

        lastUtilization = Math.min(1,
                totalMipsAllocated / getHost().getTotalMips());
        lastUtilizationUpdateTime = timeNow;
    }

    protected void processAppSubmit(SimEvent ev) {
        Application app = (Application) ev.getData();
        applicationMap.put(app.getAppId(), app);
    }

    public void addChild(int childId) {
        if (CloudSim.getEntityName(childId).toLowerCase().contains("sensor")) return;
        if (!getChildrenIds().contains(childId) && childId != getId())
            getChildrenIds().add(childId);
        if (!getChildToOperatorsMap().containsKey(childId))
            getChildToOperatorsMap().put(childId, new ArrayList<>());
    }

    protected void updateCloudTraffic() {
        int time = (int) CloudSim.clock() / 1000;
        cloudTrafficMap.merge(time, 1, Integer::sum);
    }

    protected void sendTupleToActuator(Tuple tuple) {
        for (Pair<Integer, Double> pair : getAssociatedActuatorIds()) {
            int actuatorId = pair.getFirst();
            double delay   = pair.getSecond();
            if (actuatorId == tuple.getActuatorId()) {
                send(actuatorId, delay, FogEvents.TUPLE_ARRIVAL, tuple);
                return;
            }
        }
        int childId = getChildIdForTuple(tuple);
        if (childId != -1) sendDown(tuple, childId);

        for (Pair<Integer, Double> pair : getAssociatedActuatorIds()) {
            String actuatorType =
                    ((Actuator) CloudSim.getEntity(pair.getFirst())).getActuatorType();
            if (tuple.getDestModuleName().equals(actuatorType)) {
                send(pair.getFirst(), pair.getSecond(), FogEvents.TUPLE_ARRIVAL, tuple);
                return;
            }
        }
        for (int child : getChildrenIds()) sendDown(tuple, child);
    }

    // ─── Link management ──────────────────────────────────────────────────────

    protected void updateNorthTupleQueue() {
        if (!getNorthTupleQueue().isEmpty()) {
            sendUpFreeLink(getNorthTupleQueue().poll());
        } else {
            setNorthLinkBusy(false);
        }
    }

    protected void sendUpFreeLink(Tuple tuple) {
        double networkDelay = tuple.getCloudletFileSize() / getUplinkBandwidth();
        setNorthLinkBusy(true);
        send(getId(), networkDelay, FogEvents.UPDATE_NORTH_TUPLE_QUEUE, null);
        send(parentId, networkDelay + getUplinkLatency(), FogEvents.TUPLE_ARRIVAL, tuple);
        NetworkUsageMonitor.sendingTuple(getUplinkLatency(), tuple.getCloudletFileSize());
    }

    protected void sendUp(Tuple tuple) {
        if (parentId > 0) {
            if (!isNorthLinkBusy()) sendUpFreeLink(tuple);
            else northTupleQueue.add(tuple);
        }
    }

    protected void updateSouthTupleQueue() {
        if (!getSouthTupleQueue().isEmpty()) {
            Pair<Tuple, Integer> pair = getSouthTupleQueue().poll();
            sendDownFreeLink(pair.getFirst(), pair.getSecond());
        } else {
            setSouthLinkBusy(false);
        }
    }

    protected void sendDownFreeLink(Tuple tuple, int childId) {
        double networkDelay = tuple.getCloudletFileSize() / getDownlinkBandwidth();
        setSouthLinkBusy(true);
        double latency = getChildToLatencyMap().get(childId);
        send(getId(), networkDelay, FogEvents.UPDATE_SOUTH_TUPLE_QUEUE, null);
        send(childId, networkDelay + latency, FogEvents.TUPLE_ARRIVAL, tuple);
        NetworkUsageMonitor.sendingTuple(latency, tuple.getCloudletFileSize());
    }

    protected void sendDown(Tuple tuple, int childId) {
        if (getChildrenIds().contains(childId)) {
            if (!isSouthLinkBusy()) sendDownFreeLink(tuple, childId);
            else southTupleQueue.add(new Pair<>(tuple, childId));
        }
    }

    protected void sendToSelf(Tuple tuple) {
        send(getId(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ARRIVAL, tuple);
    }

    // ─── Module lifecycle ─────────────────────────────────────────────────────

    protected void executeTuple(SimEvent ev, String moduleName) {
        Tuple tuple     = (Tuple) ev.getData();
        AppModule module = getModuleByName(moduleName);

        if (tuple.getDirection() == Tuple.UP) {
            String srcModule = tuple.getSrcModuleName();
            if (!module.getDownInstanceIdsMaps().containsKey(srcModule))
                module.getDownInstanceIdsMaps().put(srcModule, new ArrayList<>());
            if (!module.getDownInstanceIdsMaps().get(srcModule)
                    .contains(tuple.getSourceModuleId()))
                module.getDownInstanceIdsMaps().get(srcModule)
                        .add(tuple.getSourceModuleId());

            int instances = -1;
            for (String m : module.getDownInstanceIdsMaps().keySet())
                instances = Math.max(
                        module.getDownInstanceIdsMaps().get(m).size(), instances);
            module.setNumInstances(instances);
        }

        TimeKeeper.getInstance().tupleStartedExecution(tuple);
        updateAllocatedMips(moduleName);
        processCloudletSubmit(ev, false);
        updateAllocatedMips(moduleName);
    }

    protected void processModuleArrival(SimEvent ev) {
        AppModule module = (AppModule) ev.getData();
        String appId     = module.getAppId();

        if (!appToModulesMap.containsKey(appId))
            appToModulesMap.put(appId, new ArrayList<>());
        appToModulesMap.get(appId).add(module.getName());

        processVmCreate(ev, false);

        if (module.isBeingInstantiated())
            module.setBeingInstantiated(false);

        initializePeriodicTuples(module);
        module.updateVmProcessing(CloudSim.clock(),
                getVmAllocationPolicy().getHost(module)
                        .getVmScheduler().getAllocatedMipsForVm(module));
    }

    protected void processModuleTermination(SimEvent ev) {
        processVmDestroy(ev, false);
    }

    protected void initializePeriodicTuples(AppModule module) {
        String appId = module.getAppId();
        Application app = getApplicationMap().get(appId);
        for (AppEdge edge : app.getPeriodicEdges(module.getName()))
            send(getId(), edge.getPeriodicity(), FogEvents.SEND_PERIODIC_TUPLE, edge);
    }

    protected void processOperatorRelease(SimEvent ev) {
        processVmMigrate(ev, false);
    }

    protected void processSensorJoining(SimEvent ev) {
        send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);
    }

    protected void updateTimingsOnReceipt(Tuple tuple) {
        Application app   = getApplicationMap().get(tuple.getAppId());
        String srcModule  = tuple.getSrcModuleName();
        String destModule = tuple.getDestModuleName();
        for (AppLoop loop : app.getLoops()) {
            if (loop.hasEdge(srcModule, destModule) && loop.isEndModule(destModule)) {
                Double startTime = TimeKeeper.getInstance().getEmitTimes()
                        .get(tuple.getActualTupleId());
                if (startTime == null) break;
                if (!TimeKeeper.getInstance().getLoopIdToCurrentAverage()
                        .containsKey(loop.getLoopId())) {
                    TimeKeeper.getInstance().getLoopIdToCurrentAverage()
                            .put(loop.getLoopId(), 0.0);
                    TimeKeeper.getInstance().getLoopIdToCurrentNum()
                            .put(loop.getLoopId(), 0);
                }
                double currentAvg  =
                        TimeKeeper.getInstance().getLoopIdToCurrentAverage()
                                .get(loop.getLoopId());
                int currentCount   =
                        TimeKeeper.getInstance().getLoopIdToCurrentNum()
                                .get(loop.getLoopId());
                double delay       = CloudSim.clock()
                        - TimeKeeper.getInstance().getEmitTimes()
                        .get(tuple.getActualTupleId());
                TimeKeeper.getInstance().getEmitTimes()
                        .remove(tuple.getActualTupleId());
                double newAvg = (currentAvg * currentCount + delay) / (currentCount + 1);
                TimeKeeper.getInstance().getLoopIdToCurrentAverage()
                        .put(loop.getLoopId(), newAvg);
                TimeKeeper.getInstance().getLoopIdToCurrentNum()
                        .put(loop.getLoopId(), currentCount + 1);
                break;
            }
        }
    }

    // ─── Cluster support (kept for iFogSim compatibility) ────────────────────

    protected void processClustering(int parentId, int nodeId, SimEvent ev) {
        JSONObject objectLocator = (JSONObject) ev.getData();
        Clustering cms = new Clustering();
        cms.createClusterMembers(this.getParentId(), this.getId(), objectLocator);
    }

    private void updateClusterTupleQueue() {
        if (!getClusterTupleQueue().isEmpty()) {
            Pair<Tuple, Integer> pair = getClusterTupleQueue().poll();
            sendThroughFreeClusterLink(pair.getFirst(), pair.getSecond());
        } else {
            setClusterLinkBusy(false);
        }
    }

    protected void sendToCluster(Tuple tuple, int clusterNodeID) {
        if (getClusterMembers().contains(clusterNodeID)) {
            if (!isClusterLinkBusy) sendThroughFreeClusterLink(tuple, clusterNodeID);
            else clusterTupleQueue.add(new Pair<>(tuple, clusterNodeID));
        }
    }

    private void sendThroughFreeClusterLink(Tuple tuple, Integer clusterNodeID) {
        double networkDelay = tuple.getCloudletFileSize() / getClusterLinkBandwidth();
        setClusterLinkBusy(true);
        double latency = getClusterMembersToLatencyMap().get(clusterNodeID);
        send(getId(), networkDelay, FogEvents.UPDATE_CLUSTER_TUPLE_QUEUE, null);
        send(clusterNodeID, networkDelay + latency, FogEvents.TUPLE_ARRIVAL, tuple);
        NetworkUsageMonitor.sendingTuple(latency, tuple.getCloudletFileSize());
    }

    // ─── Getters / setters ────────────────────────────────────────────────────

    public PowerHost getHost()                              { return (PowerHost) getHostList().get(0); }
    public int  getParentId()                               { return parentId; }
    public void setParentId(int parentId)                   { this.parentId = parentId; }
    public List<Integer> getChildrenIds()                   { return childrenIds; }
    public void setChildrenIds(List<Integer> childrenIds)   { this.childrenIds = childrenIds; }
    public double getUplinkBandwidth()                      { return uplinkBandwidth; }
    public void setUplinkBandwidth(double v)                { this.uplinkBandwidth = v; }
    public double getUplinkLatency()                        { return uplinkLatency; }
    public void setUplinkLatency(double v)                  { this.uplinkLatency = v; }
    public boolean isSouthLinkBusy()                        { return isSouthLinkBusy; }
    public boolean isNorthLinkBusy()                        { return isNorthLinkBusy; }
    public void setSouthLinkBusy(boolean v)                 { this.isSouthLinkBusy = v; }
    public void setNorthLinkBusy(boolean v)                 { this.isNorthLinkBusy = v; }
    public int  getControllerId()                           { return controllerId; }
    public void setControllerId(int v)                      { this.controllerId = v; }
    public List<String> getActiveApplications()             { return activeApplications; }
    public void setActiveApplications(List<String> v)       { this.activeApplications = v; }
    public Map<Integer, List<String>> getChildToOperatorsMap() { return childToOperatorsMap; }
    public void setChildToOperatorsMap(Map<Integer, List<String>> v) { this.childToOperatorsMap = v; }
    public Map<String, Application> getApplicationMap()     { return applicationMap; }
    public void setApplicationMap(Map<String, Application> v) { this.applicationMap = v; }
    public Queue<Tuple> getNorthTupleQueue()                { return northTupleQueue; }
    public void setNorthTupleQueue(Queue<Tuple> v)          { this.northTupleQueue = v; }
    public Queue<Pair<Tuple, Integer>> getSouthTupleQueue() { return southTupleQueue; }
    public void setSouthTupleQueue(Queue<Pair<Tuple, Integer>> v) { this.southTupleQueue = v; }
    public double getDownlinkBandwidth()                    { return downlinkBandwidth; }
    public void setDownlinkBandwidth(double v)              { this.downlinkBandwidth = v; }
    public List<Pair<Integer, Double>> getAssociatedActuatorIds() { return associatedActuatorIds; }
    public void setAssociatedActuatorIds(List<Pair<Integer, Double>> v) { this.associatedActuatorIds = v; }
    public double getEnergyConsumption()                    { return energyConsumption; }
    public void setEnergyConsumption(double v)              { this.energyConsumption = v; }
    public Map<Integer, Double> getChildToLatencyMap()      { return childToLatencyMap; }
    public void setChildToLatencyMap(Map<Integer, Double> v){ this.childToLatencyMap = v; }
    public int  getLevel()                                  { return level; }
    public void setLevel(int v)                             { this.level = v; }
    public double getRatePerMips()                          { return ratePerMips; }
    public void setRatePerMips(double v)                    { this.ratePerMips = v; }
    public double getTotalCost()                            { return totalCost; }
    public void setTotalCost(double v)                      { this.totalCost = v; }
    public Map<String, Map<String, Integer>> getModuleInstanceCount() { return moduleInstanceCount; }
    public void setModuleInstanceCount(Map<String, Map<String, Integer>> v) { this.moduleInstanceCount = v; }
    public List<String> getPlacedAppModulesPerApplication(String appId) { return appToModulesMap.get(appId); }

    public void removeChild(int childId) {
        getChildrenIds().remove(Integer.valueOf(childId));
        getChildToOperatorsMap().remove(childId);
    }

    public void setClusterMembers(List<Integer> list)       { this.clusterMembers = list; }
    public void addClusterMember(int id)                    { this.clusterMembers.add(id); }
    public List<Integer> getClusterMembers()                { return clusterMembers; }
    public void setIsInCluster(Boolean v)                   { this.isInCluster = v; }
    public void setSelfCluster(Boolean v)                   { this.selfCluster = v; }
    public Boolean getIsInCluster()                         { return isInCluster; }
    public Boolean getSelfCluster()                         { return selfCluster; }
    public void setClusterMembersToLatencyMap(Map<Integer, Double> v) { this.clusterMembersToLatencyMap = v; }
    public Map<Integer, Double> getClusterMembersToLatencyMap()       { return clusterMembersToLatencyMap; }
    public double getClusterLinkBandwidth()                 { return clusterLinkBandwidth; }
    protected void setClusterLinkBandwidth(double v)        { this.clusterLinkBandwidth = v; }
    protected void setClusterLinkBusy(boolean v)            { this.isClusterLinkBusy = v; }
    public Queue<Pair<Tuple, Integer>> getClusterTupleQueue() { return clusterTupleQueue; }
}
