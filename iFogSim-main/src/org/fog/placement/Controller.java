package org.fog.placement;

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
import org.fog.utils.Config;
import org.fog.utils.DebugLogger;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.NetworkUsageMonitor;
import org.fog.utils.TimeKeeper;

import java.util.ArrayList;
import java.util.List;
import org.fog.entities.Tuple;


public class Controller extends SimEntity{
	
	public static boolean ONLY_CLOUD = false;
		
	private List<FogDevice> fogDevices;
	private List<Sensor> sensors;
	private List<Actuator> actuators;
	
	private Map<String, Application> applications;
	private Map<String, Integer> appLaunchDelays;

	private Map<String, ModulePlacement> appModulePlacementPolicy;
	
	private List<Tuple> pendingTasks = new ArrayList<>();
	private int populationSize = 100;          // from paper (Table 8)
	private int maxIterations = 150;           // from paper
	private double Vmax = 0.9;                 // from paper
	private double Vmin = 0.2;                 // from paper
	private double escalationParameter = 5;     // from paper (α)
	private double controlParameter = 0.5;      // from paper (Cp)
	
	public Controller(String name, List<FogDevice> fogDevices, List<Sensor> sensors, List<Actuator> actuators) {
		super(name);
		this.applications = new HashMap<String, Application>();
		setAppLaunchDelays(new HashMap<String, Integer>());
		setAppModulePlacementPolicy(new HashMap<String, ModulePlacement>());
		for(FogDevice fogDevice : fogDevices){
			fogDevice.setControllerId(getId());
		}
		setFogDevices(fogDevices);
		setActuators(actuators);
		setSensors(sensors);
		connectWithLatencies();
	}

	private FogDevice getFogDeviceById(int id){
		for(FogDevice fogDevice : getFogDevices()){
			if(id==fogDevice.getId())
				return fogDevice;
		}
		return null;
	}
	
	private void connectWithLatencies(){
		for(FogDevice fogDevice : getFogDevices()){
			FogDevice parent = getFogDeviceById(fogDevice.getParentId());
			if(parent == null)
				continue;
			double latency = fogDevice.getUplinkLatency();
			parent.getChildToLatencyMap().put(fogDevice.getId(), latency);
			parent.getChildrenIds().add(fogDevice.getId());
		}
	}
	
	@Override
	public void startEntity() {
		for(String appId : applications.keySet()){
			if(getAppLaunchDelays().get(appId)==0)
				processAppSubmit(applications.get(appId));
			else
				send(getId(), getAppLaunchDelays().get(appId), FogEvents.APP_SUBMIT, applications.get(appId));
		}double newFitness = 0;

		send(getId(), Config.RESOURCE_MANAGE_INTERVAL, FogEvents.CONTROLLER_RESOURCE_MANAGE);
		
		send(getId(), Config.MAX_SIMULATION_TIME, FogEvents.STOP_SIMULATION);
		
		for(FogDevice dev : getFogDevices())
			sendNow(dev.getId(), FogEvents.RESOURCE_MGMT);
		//send(getId(), 1.0, FogEvents.MOAOA_OPTIMIZE);
		if (!pendingTasks.isEmpty()) {
		    //sendNow(getId(), FogEvents.MOAOA_OPTIMIZE);
			send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE);
		}
	}
	private void processTupleArrival(SimEvent ev) {
	    Tuple tuple = (Tuple) ev.getData();

	    String src = CloudSim.getEntityName(ev.getSource());

	    // Only accept tuples from sensors
	    if (src.startsWith("sensor") && tuple.getUserId() != -1) {
	        pendingTasks.add(tuple);
	    }
	}
	private void processTupleForDecision(SimEvent ev) {

	    Tuple tuple = (Tuple) ev.getData();
	    FogDevice srcDevice = (FogDevice) CloudSim.getEntity(tuple.getSourceDeviceId());

	    // ===== COMPUTE FITNESS =====
	    double local = computeFitnessLocal(tuple, srcDevice);
	    double fog = computeFitnessFog(tuple);
	    double cloud = computeFitnessCloud(tuple);

	    DebugLogger.log("[CONTROLLER DECISION] Source=" + srcDevice.getName()
	        + " | Local=" + local + " | Fog=" + fog + " | Cloud=" + cloud);

	    double min = Math.min(local, Math.min(fog, cloud));

	    if (min == local) {
	        DebugLogger.log("[CONTROLLER] Execute LOCAL at " + srcDevice.getName());
	        sendNow(srcDevice.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
	    }
	    else if (min == fog) {
	        DebugLogger.log("[CONTROLLER] Send to FOG");
	        sendNow(srcDevice.getParentId(), FogEvents.TUPLE_ARRIVAL, tuple);
	    }
	    else {
	        DebugLogger.log("[CONTROLLER] Send to CLOUD");

	        // find cloud
	        FogDevice cloud1 = null;
	        for (FogDevice dev : getFogDevices()) {
	            if (dev.getLevel() == 0) {
	                cloud1 = dev;
	                break;
	            }
	        }

	        sendNow(cloud1.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
	    }
	}
	private double computeFitnessLocal(Tuple t, FogDevice dev) {
	    double mips = dev.getHost().getTotalMips();
	    double delay = t.getCloudletLength() / mips;
	    double energy = t.getCloudletLength() * 0.6;
	    return delay + energy;
	}

	private double computeFitnessFog(Tuple t) {
	    double delay = t.getCloudletLength() / 1500.0;
	    double network = t.getCloudletFileSize() / 1000.0;
	    return delay + network;
	}

	private double computeFitnessCloud(Tuple t) {
	    double delay = t.getCloudletLength() / 4000.0;
	    double network = 2 * (t.getCloudletFileSize() / 1000.0);
	    return delay + network;
	}
	private void runMOAOAAndAssign() {
		
	    if (pendingTasks.isEmpty()) return;
	    
	    int numTasks = pendingTasks.size();
	    List<FogDevice> computeNodes = new ArrayList<>(fogDevices);
	    int numNodes = computeNodes.size();
	    
	    // Pre‑compute for each node: MIPS, bandwidth, power constants (from paper)
	    double[] nodeMips = new double[numNodes];
	    double[] nodeBandwidth = new double[numNodes];
	    double[] nodeEnergyParams = new double[numNodes]; // for energy model
	    for (int i = 0; i < numNodes; i++) {
	        FogDevice dev = computeNodes.get(i);
	        nodeMips[i] = dev.getHost().getTotalMips();          // MIPS
	        nodeBandwidth[i] = dev.getUplinkBandwidth();         // bandwidth
	        // Energy parameters: you may need to extract from device characteristics
	        // For now, use default constants from paper (Table 8)
	        nodeEnergyParams[i] = 0.5; // placeholder
	    }
	    
	    // Pre‑compute for each task: length, data size, type (for priority)
	    double[] taskLength = new double[numTasks];
	    double[] taskDataSize = new double[numTasks];
	    int[] taskPriority = new int[numTasks];
	    for (int i = 0; i < numTasks; i++) {
	        Tuple t = pendingTasks.get(i);
	        taskLength[i] = t.getCloudletLength();
	        taskDataSize[i] = t.getCloudletFileSize();
	        taskPriority[i] = 1; // default priority
	    }
	    
	    // MOAOA parameters
	    double moa, mop;
	    double r1, r2, r3;
	    int archiveSize = populationSize;
	    List<int[][]> population = new ArrayList<>();
	    List<Double> fitness = new ArrayList<>();
	    List<int[][]> archive = new ArrayList<>();
	    
	    // Initialize population randomly (binary matrices)
	    for (int i = 0; i < populationSize; i++) {
	        int[][] solution = new int[numTasks][numNodes];
	        for (int t = 0; t < numTasks; t++) {
	            int chosen = (int)(Math.random() * numNodes);
	            solution[t][chosen] = 1;
	        }
	        population.add(solution);
	    }
	    
	    // Main loop
	    for (int iter = 0; iter < maxIterations; iter++) {
	        // Compute MOA and MOP (Eq. 1 and 18)
	        moa = Vmin + iter * ((Vmax - Vmin) / maxIterations);
	        mop = 1 - Math.pow(iter / maxIterations, escalationParameter);
	        
	        // Evaluate fitness for each solution
	        fitness.clear();
	        for (int[][] sol : population) {
	            double totalDelay = 0, totalEnergy = 0;
	            for (int t = 0; t < numTasks; t++) {
	                int nodeIdx = -1;
	                for (int n = 0; n < numNodes; n++) {
	                    if (sol[t][n] == 1) { nodeIdx = n; break; }
	                }
	                // Compute delay and energy using paper's formulas (Eq. 4-15)
	                // Simplified: 
	                //   delay = taskLength[t] / nodeMips[nodeIdx] + taskDataSize[t] / nodeBandwidth[nodeIdx]
	                //   energy = taskLength[t] * nodeEnergyParams[nodeIdx]
	                double delay = taskLength[t] / nodeMips[nodeIdx] + taskDataSize[t] / nodeBandwidth[nodeIdx];
	                double energy = taskLength[t] * nodeEnergyParams[nodeIdx];
	                totalDelay += delay;
	                totalEnergy += energy;
	            }
	            // Combined fitness (Eq. 3, W=0.5)
	            double fit = 0.5 * totalDelay + 0.5 * totalEnergy;
	            fitness.add(fit);
	        }
	        
	        // Update archive with Pareto front (using crowding distance)
	        // (Implement crowding distance if you want multiple objectives)
	        // For simplicity, we store all non‑dominated solutions.
	        // (You can implement a proper Pareto archive here.)
	        
	        // Update positions for each solution
	        for (int i = 0; i < populationSize; i++) {
	            int[][] current = population.get(i);
	            int[][] newSol = new int[numTasks][numNodes];
	            r1 = Math.random(); r2 = Math.random(); r3 = Math.random();
	            if (r1 <= moa) { // exploitation
	                for (int t = 0; t < numTasks; t++) {
	                    int nodeIdx = -1;
	                    for (int n = 0; n < numNodes; n++) {
	                        if (current[t][n] == 1) { nodeIdx = n; break; }
	                    }
	                    double step = mop * (controlParameter * (Math.random() * 2 - 1));
	                    if (r3 > 0.5) { // addition
	                        nodeIdx = (nodeIdx + (int)step) % numNodes;
	                    } else { // subtraction
	                        nodeIdx = (nodeIdx - (int)step) % numNodes;
	                        if (nodeIdx < 0) nodeIdx += numNodes;
	                    }
	                    newSol[t][nodeIdx] = 1;
	                }
	            } else { // exploration
	                // Use multiplication/division operators (Eq. 17)
	                // For binary, we can choose a different node based on MOP
	                for (int t = 0; t < numTasks; t++) {
	                    int nodeIdx = -1;
	                    for (int n = 0; n < numNodes; n++) {
	                        if (current[t][n] == 1) { nodeIdx = n; break; }
	                    }
	                    double change = mop * (controlParameter * (Math.random() * 2 - 1));
	                    if (r2 <= 0.5) { // division
	                        nodeIdx = (nodeIdx + (int)change) % numNodes;
	                    } else { // multiplication
	                        nodeIdx = (nodeIdx * (int)(1 + change)) % numNodes;
	                    }
	                    newSol[t][nodeIdx] = 1;
	                }
	            }
	            // Replace current with new if better (greedy selection)
	            
	            double newFitness = 0;

	            /*for (int t = 0; t < numTasks; t++) {
	                int nodeIdx = -1;
	                for (int n = 0; n < numNodes; n++) {
	                    if (newSol[t][n] == 1) { nodeIdx = n; break; }
	                }

	                double delay = taskLength[t] / nodeMips[nodeIdx] 
	                             + taskDataSize[t] / nodeBandwidth[nodeIdx];

	                double energy = taskLength[t] * nodeEnergyParams[nodeIdx];

	                newFitness += 0.5 * delay + 0.5 * energy;
	            }*/
	        }
	    }
	    
	    // After iterations, select the best solution (e.g., minimal fitness)
	    int bestIdx = 0;
	    for (int i = 1; i < fitness.size(); i++) {
	        if (fitness.get(i) < fitness.get(bestIdx)) bestIdx = i;
	    }
	    int[][] bestSolution = population.get(bestIdx);

	 // ✅ ADD THIS BLOCK HERE
	    for (int t = 0; t < numTasks; t++) {
	        Tuple tuple = pendingTasks.get(t);

	        if (tuple.getDestModuleName() != null)
	            continue;

	        int nodeIdx = -1;
	        for (int n = 0; n < numNodes; n++) {
	            if (bestSolution[t][n] == 1) {
	                nodeIdx = n;
	                break;
	            }
	        }

	        if (nodeIdx >= 0) {
	            FogDevice targetDevice = computeNodes.get(nodeIdx);

	            tuple.setDestModuleName(targetDevice.getName());
	            
	            tuple.setUserId(-1);

	            sendNow(targetDevice.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
	        }
	    }
	 System.out.println("\n=== MOAOA DECISION ===");

	 for (int t = 0; t < numTasks; t++) {
	     int nodeIdx = -1;
	     for (int n = 0; n < numNodes; n++) {
	         if (bestSolution[t][n] == 1) {
	             nodeIdx = n;
	             break;
	         }
	     }

	     System.out.println(
	         "Task " + pendingTasks.get(t).getTupleType() +
	         " -> Assigned to " + computeNodes.get(nodeIdx).getName()
	     );
	 }
	    
	    // Assign tasks according to bestSolution
	    for (int t = 0; t < numTasks; t++) {
	        Tuple tuple = pendingTasks.get(t);
	        int nodeIdx = -1;
	        for (int n = 0; n < numNodes; n++) {
	            if (bestSolution[t][n] == 1) { nodeIdx = n; break; }
	        }
	        if (nodeIdx >= 0) {
	            FogDevice targetDevice = computeNodes.get(nodeIdx);
	            // Send tuple to the chosen device
	            sendNow(targetDevice.getId(), FogEvents.TUPLE_ARRIVAL, tuple);
	            return;
	        }
	    }
	    
	    pendingTasks.clear();
	}
	

	@Override
	public void processEvent(SimEvent ev) {
		switch(ev.getTag()){
		case FogEvents.TUPLE_ARRIVAL:
		    processTupleForDecision(ev);
		    break;
		case FogEvents.APP_SUBMIT:
			processAppSubmit(ev);
			break;
		case FogEvents.TUPLE_FINISHED:
			processTupleFinished(ev);
			break;
		case FogEvents.CONTROLLER_RESOURCE_MANAGE:
			manageResources();
			break;
		case FogEvents.STOP_SIMULATION:
			CloudSim.stopSimulation();
			printTimeDetails();
			printPowerDetails();
			printCostDetails();
			printNetworkUsageDetails();
			System.exit(0);
			break;
		case FogEvents.MOAOA_OPTIMIZE:
		    runMOAOAAndAssign();
		    send(getId(), 5.0, FogEvents.MOAOA_OPTIMIZE); // repeat every 5 seconds
		    break;

            default:
                throw new IllegalStateException("Unexpected value: " + ev.getTag());
        }
	}
	
	private void printNetworkUsageDetails() {
		System.out.println("Total network usage = "+NetworkUsageMonitor.getNetworkUsage()/Config.MAX_SIMULATION_TIME);		
	}

	private FogDevice getCloud(){
		for(FogDevice dev : getFogDevices())
			if(dev.getName().equals("cloud"))
				return dev;
		return null;
	}
	
	private void printCostDetails(){
		System.out.println("Cost of execution in cloud = "+getCloud().getTotalCost());
	}
	
	private void printPowerDetails() {
		for(FogDevice fogDevice : getFogDevices()){
			System.out.println(fogDevice.getName() + " : Energy Consumed = "+fogDevice.getEnergyConsumption());
		}
	}

	private String getStringForLoopId(int loopId){
		for(String appId : getApplications().keySet()){
			Application app = getApplications().get(appId);
			for(AppLoop loop : app.getLoops()){
				if(loop.getLoopId() == loopId)
					return loop.getModules().toString();
			}
		}
		return null;
	}
	private void printTimeDetails() {
		System.out.println("=========================================");
		System.out.println("============== RESULTS ==================");
		System.out.println("=========================================");
		System.out.println("EXECUTION TIME : "+ (Calendar.getInstance().getTimeInMillis() - TimeKeeper.getInstance().getSimulationStartTime()));
		System.out.println("=========================================");
		System.out.println("APPLICATION LOOP DELAYS");
		System.out.println("=========================================");
		for(Integer loopId : TimeKeeper.getInstance().getLoopIdToTupleIds().keySet()){
			/*double average = 0, count = 0;
			for(int tupleId : TimeKeeper.getInstance().getLoopIdToTupleIds().get(loopId)){
				Double startTime = 	TimeKeeper.getInstance().getEmitTimes().get(tupleId);
				Double endTime = 	TimeKeeper.getInstance().getEndTimes().get(tupleId);
				if(startTime == null || endTime == null)
					break;
				average += endTime-startTime;
				count += 1;
			}
			System.out.println(getStringForLoopId(loopId) + " ---> "+(average/count));*/
			System.out.println(getStringForLoopId(loopId) + " ---> "+TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loopId));
		}
		System.out.println("=========================================");
		System.out.println("TUPLE CPU EXECUTION DELAY");
		System.out.println("=========================================");
		
		for(String tupleType : TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().keySet()){
			System.out.println(tupleType + " ---> "+TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().get(tupleType));
		}
		
		System.out.println("=========================================");
	}

	protected void manageResources(){
		send(getId(), Config.RESOURCE_MANAGE_INTERVAL, FogEvents.CONTROLLER_RESOURCE_MANAGE);
	}
	
	private void processTupleFinished(SimEvent ev) {
	}
	
	@Override
	public void shutdownEntity() {	
	}
	
	public void submitApplication(Application application, int delay, ModulePlacement modulePlacement){
		FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
		getApplications().put(application.getAppId(), application);
		getAppLaunchDelays().put(application.getAppId(), delay);
		getAppModulePlacementPolicy().put(application.getAppId(), modulePlacement);
		
		for(Sensor sensor : sensors){
			sensor.setApp(getApplications().get(sensor.getAppId()));
		}
		for(Actuator ac : actuators){
			ac.setApp(getApplications().get(ac.getAppId()));
		}
		
		for(AppEdge edge : application.getEdges()){
			if(edge.getEdgeType() == AppEdge.ACTUATOR){
				String moduleName = edge.getSource();
				for(Actuator actuator : getActuators()){
					if(actuator.getActuatorType().equalsIgnoreCase(edge.getDestination()))
						application.getModuleByName(moduleName).subscribeActuator(actuator.getId(), edge.getTupleType());
				}
			}
		}	
	}
	
	public void submitApplication(Application application, ModulePlacement modulePlacement){
		submitApplication(application, 0, modulePlacement);
	}
	
	
	private void processAppSubmit(SimEvent ev){
		Application app = (Application) ev.getData();
		processAppSubmit(app);
	}
	
	private void processAppSubmit(Application application){
		System.out.println(CloudSim.clock()+" Submitted application "+ application.getAppId());
		FogUtils.appIdToGeoCoverageMap.put(application.getAppId(), application.getGeoCoverage());
		getApplications().put(application.getAppId(), application);
		
		ModulePlacement modulePlacement = getAppModulePlacementPolicy().get(application.getAppId());
		for(FogDevice fogDevice : fogDevices){
			sendNow(fogDevice.getId(), FogEvents.ACTIVE_APP_UPDATE, application);
		}
		
		Map<Integer, List<AppModule>> deviceToModuleMap = modulePlacement.getDeviceToModuleMap();
		for(Integer deviceId : deviceToModuleMap.keySet()){
			for(AppModule module : deviceToModuleMap.get(deviceId)){
				sendNow(deviceId, FogEvents.APP_SUBMIT, application);
				sendNow(deviceId, FogEvents.LAUNCH_MODULE, module);
			}
		}
	}

	public List<FogDevice> getFogDevices() {
		return fogDevices;
	}

	public void setFogDevices(List<FogDevice> fogDevices) {
		this.fogDevices = fogDevices;
	}

	public Map<String, Integer> getAppLaunchDelays() {
		return appLaunchDelays;
	}

	public void setAppLaunchDelays(Map<String, Integer> appLaunchDelays) {
		this.appLaunchDelays = appLaunchDelays;
	}

	public Map<String, Application> getApplications() {
		return applications;
	}

	public void setApplications(Map<String, Application> applications) {
		this.applications = applications;
	}

	public List<Sensor> getSensors() {
		return sensors;
	}

	public void setSensors(List<Sensor> sensors) {
		for(Sensor sensor : sensors)
			sensor.setControllerId(getId());
		this.sensors = sensors;
	}

	public List<Actuator> getActuators() {
		return actuators;
	}

	public void setActuators(List<Actuator> actuators) {
		this.actuators = actuators;
	}

	public Map<String, ModulePlacement> getAppModulePlacementPolicy() {
		return appModulePlacementPolicy;
	}

	public void setAppModulePlacementPolicy(Map<String, ModulePlacement> appModulePlacementPolicy) {
		this.appModulePlacementPolicy = appModulePlacementPolicy;
	}
}