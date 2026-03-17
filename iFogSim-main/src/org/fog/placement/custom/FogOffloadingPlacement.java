package org.fog.placement.custom;

import java.util.List;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;

public class FogOffloadingPlacement extends ModulePlacementEdgewards {

    public FogOffloadingPlacement(
            List<FogDevice> fogDevices,
            List<Sensor> sensors,
            List<Actuator> actuators,
            Application application,
            ModuleMapping moduleMapping) {

        super(fogDevices, sensors, actuators, application, moduleMapping);
    }

    @Override
    public void mapModules() {

        Application app = getApplication();
        List<FogDevice> devices = getFogDevices();

        // IMPORTANT: use superclass's moduleMapping (already initialized)
        ModuleMapping mapping = this.moduleMapping;

        for (AppModule module : app.getModules()) {

            String moduleName = module.getName();

            // Keep fixed modules untouched
            if (moduleName.equals("user_interface") ||
                moduleName.equals("motion_detector"))
                continue;

            boolean placed = false;

            // Try fog first
            //commented by ragamaie
            
            /*for (FogDevice device : devices) {
                if (device.getLevel() > 0 && moduleName.contains("detector")) {
                	System.out.println(
                		    "[OFFLOADING] Placing module " + moduleName +
                		    " on device " + device.getName() +
                		    " (level=" + device.getLevel() + ")"
                		);

                    mapping.addModuleToDevice(moduleName, device.getName());
                    placed = true;
                    break;
                }
            }*/
            //added for checking
            for (FogDevice device : devices) {

                // Place preprocessing in fog nodes
                if (device.getLevel() == 3 && moduleName.equals("data_preprocessor")) {

                    System.out.println(
                        "[OFFLOADING] Placing module " + moduleName +
                        " on device " + device.getName()
                    );

                    mapping.addModuleToDevice(moduleName, device.getName());
                    placed = true;
                    //commented by ragamaie to fix 
                    //break;
                }
            }

            // Otherwise place in cloud
            if (!placed) {
                for (FogDevice device : devices) {
                    if (device.getLevel() == 0) {
                    	System.out.println(
                    		    "[OFFLOADING] Placing module " + moduleName +
                    		    " on device " + device.getName() +
                    		    " (level=" + device.getLevel() + ")"
                    		);

                        mapping.addModuleToDevice(moduleName, device.getName());
                        //commented by ragamaie to fix
                        //break;
                    }
                }
            }
        }
        super.mapModules();
    }
    
}
