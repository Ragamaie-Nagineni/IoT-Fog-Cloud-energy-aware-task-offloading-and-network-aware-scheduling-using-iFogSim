package org.fog.placement.custom;

import java.util.List;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.utils.DebugLogger;

public class FogOffloadingPlacement extends ModulePlacementEdgewards {
    
    // Simple constructor that matches what you're trying to call
    public FogOffloadingPlacement(
            List<FogDevice> fogDevices,
            List<Sensor> sensors,
            List<Actuator> actuators,
            Application application,
            ModuleMapping moduleMapping) {
        
        // Call super with the same parameters
        super(fogDevices, sensors, actuators, application, moduleMapping);
        
        DebugLogger.log("*** FogOffloadingPlacement CONSTRUCTOR CALLED ***");
        DebugLogger.log("fogDevices size: " + fogDevices.size());
        DebugLogger.log("sensors size: " + sensors.size());
        DebugLogger.log("actuators size: " + actuators.size());
        DebugLogger.log("application: " + application.getAppId());
    }

    @Override
    public void mapModules() {

        DebugLogger.log("=== mapModules() STARTED ===");

        Application app = getApplication();
        List<FogDevice> devices = getFogDevices();
        ModuleMapping mapping = this.moduleMapping;

        for (AppModule module : app.getModules()) {

            String moduleName = module.getName();

            boolean placed = false;

            // Place data_preprocessor on sensor nodes (level 3)
            for (FogDevice device : devices) {
                if (device.getLevel() == 3 && moduleName.equals("data_preprocessor")) {

                    DebugLogger.log("[PLACEMENT] " + moduleName + " -> " + device.getName());

                    mapping.addModuleToDevice(moduleName, device.getName());
                    placed = true;
                }
            }
            // Place others in cloud
            if (!placed) {
                for (FogDevice device : devices) {
                    if (device.getLevel() == 0) {

                        DebugLogger.log("[PLACEMENT] " + moduleName + " -> CLOUD");

                        mapping.addModuleToDevice(moduleName, device.getName());
                    }
                }
            }
        }

        // VERY IMPORTANT
        super.mapModules();

        DebugLogger.log("=== mapModules() COMPLETED ===");
    }
}