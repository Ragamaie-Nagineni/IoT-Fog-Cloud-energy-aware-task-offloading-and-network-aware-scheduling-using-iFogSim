package org.fog.test.perfeval;

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
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.fog.placement.custom.FogOffloadingPlacement;


public class IndustrialIoTFog {
	static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
	static List<Sensor> sensors = new ArrayList<Sensor>();
	static List<Actuator> actuators = new ArrayList<Actuator>();
	static int numOfAreas = 1;
	static int numOfSensorNodesPerArea = 4;

	
	private static boolean CLOUD = false;
	
	public static void main(String[] args) {

		Log.printLine("Starting Industrial IoT Simulation...");

		try {
			Log.disable();
			int num_user = 1; // number of cloud users
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false; // mean trace events

			CloudSim.init(num_user, calendar, trace_flag);

			String appId = "dcns"; // identifier of the application
			
			FogBroker broker = new FogBroker("broker");
			
			Application application = createApplication(appId, broker.getId());
			application.setUserId(broker.getId());
			
			createFogDevices(broker.getId(), appId);
			
			Controller controller = null;
			
			ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();

			moduleMapping.addModuleToDevice("data_preprocessor", "sensor-node-0-0");
			moduleMapping.addModuleToDevice("analytics", "cloud");
			moduleMapping.addModuleToDevice("cloud_storage", "cloud");

			controller = new Controller("master-controller", fogDevices, sensors, 
					actuators);
			
			/*controller.submitApplication(application, 
					(CLOUD)?(new ModulePlacementMapping(fogDevices, application, moduleMapping))
							:(new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));*/
			controller.submitApplication(application,
				    new FogOffloadingPlacement(fogDevices, sensors, actuators, application, moduleMapping)
				);

			
			TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
			
			CloudSim.startSimulation();

			CloudSim.stopSimulation();

			Log.printLine("VRGame finished!");
		} catch (Exception e) {
			e.printStackTrace();
			Log.printLine("Unwanted errors happen");
		}
	}
	
	/**
	 * Creates the fog devices in the physical topology of the simulation.
	 * @param userId
	 * @param appId
	 */
	private static void createFogDevices(int userId, String appId) {
		FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16*103, 16*83.25);
		cloud.setParentId(-1);
		fogDevices.add(cloud);
		FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
		proxy.setParentId(cloud.getId());
		proxy.setUplinkLatency(100); // latency of connection between proxy server and cloud is 100 ms
		fogDevices.add(proxy);
		for(int i=0;i<numOfAreas;i++){
			addArea(i+"", userId, appId, proxy.getId());
		}
	}

	private static FogDevice addArea(String id, int userId, String appId, int parentId){
		FogDevice router = createFogDevice("d-"+id, 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
		fogDevices.add(router);
		router.setUplinkLatency(2); // latency of connection between router and proxy server is 2 ms
		for(int i=0;i<numOfSensorNodesPerArea;i++){
			String mobileId = id+"-"+i;
			FogDevice sensorNode = addSensorNode(mobileId, userId, appId, router.getId()); // adding a smart camera to the physical topology. Smart cameras have been modeled as fog devices as well.
			sensorNode.setUplinkLatency(2); // latency of connection between camera and router is 2 ms
			fogDevices.add(sensorNode);
		}
		router.setParentId(parentId);
		return router;
	}
	
	private static FogDevice addSensorNode(String id, int userId, String appId, int parentId) {

	    FogDevice node = createFogDevice(
	        "sensor-node-" + id, 500, 1000,
	        10000, 10000, 3, 0, 87.53, 82.44
	    );

	    node.setParentId(parentId);

	    Sensor tempSensor = new Sensor(
	        "temp-" + id, "TEMP", userId, appId,
	        new DeterministicDistribution(3)
	    );

	    Sensor vibSensor = new Sensor(
	        "vib-" + id, "VIB", userId, appId,
	        new DeterministicDistribution(10)
	    );

	    tempSensor.setGatewayDeviceId(node.getId());
	    vibSensor.setGatewayDeviceId(node.getId());

	    tempSensor.setLatency(1.0);
	    vibSensor.setLatency(1.0);

	    sensors.add(tempSensor);
	    sensors.add(vibSensor);

	    return node;
	}

	

	private static FogDevice createFogDevice(String nodeName, long mips,
			int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
		
		List<Pe> peList = new ArrayList<Pe>();

		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating

		int hostId = FogUtils.generateEntityId();
		long storage = 1000000; // host storage
		int bw = 10000;

		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
			);

		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
													// devices by now

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);

		FogDevice fogdevice = null;
		try {
			fogdevice = new FogDevice(nodeName, characteristics, 
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		fogdevice.setLevel(level);
		return fogdevice;
	}

	
	@SuppressWarnings({"serial" })
	private static Application createApplication(String appId, int userId){
		
		Application application = Application.createApplication(appId, userId);
		
		application.addAppModule("data_preprocessor", 50);
		application.addAppModule("analytics", 200);
		application.addAppModule("cloud_storage", 10);

		
		application.addAppEdge("TEMP", "data_preprocessor",
		        500, 500, "TEMP_DATA", Tuple.UP, AppEdge.SENSOR);

		application.addAppEdge("VIB", "data_preprocessor",
		        3000, 3000, "VIB_DATA", Tuple.UP, AppEdge.SENSOR);

		application.addAppEdge("data_preprocessor", "analytics",
		        2000, 4000, "PROCESSED_DATA", Tuple.UP, AppEdge.MODULE);

		application.addAppEdge("analytics", "cloud_storage",
		        1000, 1000, "RESULTS", Tuple.UP, AppEdge.MODULE);


		
		application.addTupleMapping(
			    "data_preprocessor", "TEMP_DATA", "PROCESSED_DATA",
			    new FractionalSelectivity(1.0)
			);

			application.addTupleMapping(
			    "data_preprocessor", "VIB_DATA", "PROCESSED_DATA",
			    new FractionalSelectivity(1.0)
			);


			final AppLoop loop1 = new AppLoop(
				    new ArrayList<String>() {{
				        add("data_preprocessor");
				        add("analytics");
				        add("cloud_storage");
				    }}
				);

				application.setLoops(
				    new ArrayList<AppLoop>() {{ add(loop1); }}
				);

		return application;
	}
}