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
 * FogOffloadingPlacement — initial module placement for iFogSim wiring.
 *
 * NOTE: This class only handles iFogSim's module-to-device wiring so that
 * the simulation can start. The ACTUAL MoAOA task offloading optimization
 * is performed dynamically inside Controller.java via MOAOA_OPTIMIZE and
 * MOAOA_DYNAMIC events as tasks arrive from sensors.
 *
 * Placement rules (matching paper's FC/FD model, Section III-A):
 *   data_preprocessor → sensor-nodes (edge, level 3)  — delay-sensitive
 *   analytics         → cloud        (level 0)         — compute-intensive
 *   cloud_storage     → cloud        (level 0)         — storage
 */
public class FogOffloadingPlacement extends ModulePlacementEdgewards {

    public FogOffloadingPlacement(List<FogDevice> fogDevices,
                                   List<Sensor>    sensors,
                                   List<Actuator>  actuators,
                                   Application     application,
                                   ModuleMapping   moduleMapping) {
        super(fogDevices, sensors, actuators, application, moduleMapping);
        DebugLogger.info("PLACEMENT",
                "FogOffloadingPlacement initialised — MoAOA optimization "
              + "runs in Controller via MOAOA_OPTIMIZE / MOAOA_DYNAMIC events");
    }

    @Override
    public void mapModules() {
        DebugLogger.separator();
        DebugLogger.log("  Initial Module Placement (iFogSim wiring):");
        DebugLogger.separator();

        Application     app     = getApplication();
        List<FogDevice> devices = getFogDevices();

        for (AppModule module : app.getModules()) {
            String name = module.getName();

            if (name.equals("data_preprocessor")) {
                // Place on all edge sensor-nodes (level 3) — one per node
                // Matches paper: delay-sensitive tasks → fog/edge (Table 3, line 9-13)
                for (FogDevice dev : devices) {
                    if (dev.getLevel() == 3) {
                        moduleMapping.addModuleToDevice(name, dev.getName());
                        DebugLogger.log(String.format(
                                "  %-22s  →  %-22s  [Level 3 — Edge]",
                                name, dev.getName()));
                    }
                }
            } else {
                // analytics, cloud_storage → cloud (level 0)
                // Matches paper: compute-intensive tasks → cloud (Table 3, line 17-24)
                for (FogDevice dev : devices) {
                    if (dev.getLevel() == 0) {
                        moduleMapping.addModuleToDevice(name, dev.getName());
                        DebugLogger.log(String.format(
                                "  %-22s  →  %-22s  [Level 0 — Cloud]",
                                name, dev.getName()));
                    }
                }
            }
        }

        DebugLogger.separator();
        super.mapModules(); // complete iFogSim internal wiring
        DebugLogger.info("PLACEMENT", "Initial module placement complete");
    }
}
