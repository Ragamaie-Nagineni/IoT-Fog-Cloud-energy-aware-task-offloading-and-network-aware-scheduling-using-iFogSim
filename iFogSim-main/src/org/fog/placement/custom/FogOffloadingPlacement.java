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

/**
 * FogOffloadingPlacement — custom module placement strategy.
 *
 * Placement rules (edge-first, matching paper's FC/FD model):
 *   data_preprocessor  → sensor-node (edge level 3)
 *   analytics          → cloud       (level 0)
 *   cloud_storage      → cloud       (level 0)
 *
 * This reflects the paper's approach where lightweight preprocessing
 * happens at the edge and compute-intensive analytics go to the cloud.
 */
public class FogOffloadingPlacement extends ModulePlacementEdgewards {

    public FogOffloadingPlacement(
            List<FogDevice> fogDevices,
            List<Sensor>    sensors,
            List<Actuator>  actuators,
            Application     application,
            ModuleMapping   moduleMapping) {

        super(fogDevices, sensors, actuators, application, moduleMapping);

        DebugLogger.info("PLACEMENT", "FogOffloadingPlacement initialised");
        DebugLogger.info("PLACEMENT", "Devices=" + fogDevices.size()
                + " | Sensors=" + sensors.size()
                + " | App=" + application.getAppId());
    }

    /**
     * Assigns each application module to the most appropriate device tier.
     */
    @Override
    public void mapModules() {

        DebugLogger.separator();
        DebugLogger.log("  Module Placement Decisions:");
        DebugLogger.separator();

        Application        app     = getApplication();
        List<FogDevice>    devices = getFogDevices();
        ModuleMapping      mapping = this.moduleMapping;

        for (AppModule module : app.getModules()) {
            String moduleName = module.getName();
            boolean placed    = false;

            if (moduleName.equals("data_preprocessor")) {
                // Place on ALL sensor-nodes (level 3) — one instance per node
                for (FogDevice device : devices) {
                    if (device.getLevel() == 3) {
                        mapping.addModuleToDevice(moduleName, device.getName());
                        DebugLogger.log(String.format(
                                "  %-22s  →  %-22s  [Level 3 — Edge]",
                                moduleName, device.getName()));
                        placed = true;
                    }
                }
            }

            if (!placed) {
                // analytics and cloud_storage go to cloud (level 0)
                for (FogDevice device : devices) {
                    if (device.getLevel() == 0) {
                        mapping.addModuleToDevice(moduleName, device.getName());
                        DebugLogger.log(String.format(
                                "  %-22s  →  %-22s  [Level 0 — Cloud]",
                                moduleName, device.getName()));
                    }
                }
            }
        }

        DebugLogger.separator();

        // Call super to complete iFogSim's internal device-module wiring
        super.mapModules();

        DebugLogger.info("PLACEMENT", "Module placement completed successfully");
    }
}
