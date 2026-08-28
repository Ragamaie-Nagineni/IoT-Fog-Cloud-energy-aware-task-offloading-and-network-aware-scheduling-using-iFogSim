package org.fog.utils;

import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;

/**
 * FogEvents — iFogSim event tags.
 *
 * Enum implementing CloudSimTags so instances can be passed to
 * SimEntity.send() / sendNow() which accept a CloudSimTags argument.
 *
 * HOW TAG RESOLUTION WORKS:
 *   CloudSim stores the tag as-is when you pass a CloudSimTags object.
 *   SimEvent.getTag() returns it back.
 *   In processEvent(), use FogEvents.fromTag(ev) instead of a raw cast
 *   so the code is safe regardless of CloudSim version.
 */
public enum FogEvents implements CloudSimTags {

    TUPLE_ARRIVAL,
    LAUNCH_MODULE,
    RELEASE_OPERATOR,
    SENSOR_JOINED,
    TUPLE_ACK,
    APP_SUBMIT,
    CALCULATE_INPUT_RATE,
    CALCULATE_UTIL,
    UPDATE_RESOURCE_USAGE,
    TUPLE_FINISHED,
    ACTIVE_APP_UPDATE,
    CONTROLLER_RESOURCE_MANAGE,
    ADAPTIVE_OPERATOR_REPLACEMENT,
    GET_RESOURCE_USAGE,
    RESOURCE_USAGE,
    CONTROL_MSG_ARRIVAL,
    UPDATE_NORTH_TUPLE_QUEUE,
    UPDATE_SOUTH_TUPLE_QUEUE,
    ACTUATOR_JOINED,
    STOP_SIMULATION,
    SEND_PERIODIC_TUPLE,
    LAUNCH_MODULE_INSTANCE,
    RESOURCE_MGMT,
    INITIALIZE_SENSOR,
    EMIT_TUPLE,
    MOBILITY_SUBMIT,
    MOBILITY_MANAGEMENT,
    MODULE_SEND,
    MODULE_RECEIVE,
    RELEASE_MODULE,
    UPDATE_CLUSTER_TUPLE_QUEUE,
    PROCESS_PRS,
    RECEIVE_PR,
    UPDATE_SERVICE_DISCOVERY,
    TRANSMIT_PR,
    MANAGEMENT_TUPLE_ARRIVAL,
    UPDATE_RESOURCE_INFO,
    START_DYNAMIC_CLUSTERING,
    MOAOA_OPTIMIZE,
    MOAOA_DYNAMIC;

    // ─── Safe tag resolution ───────────────────────────────────────────────────

    /**
     * Safely resolves a CloudSim SimEvent to a FogEvents constant.
     *
     * Handles two CloudSim variants:
     *   (a) getTag() returns the CloudSimTags Object directly
     *       → direct cast works.
     *   (b) getTag() returns int (ordinal)
     *       → look up via values()[ordinal].
     *
     * Returns null for unknown / out-of-range tags — callers should check
     * for null and fall through to a default handler.
     *
     * USAGE:
     *   FogEvents tag = FogEvents.fromTag(ev);
     *   if (tag == null) return;
     *   switch (tag) { ... }
     */
    public static FogEvents fromTag(SimEvent ev) {
        try {
            // Attempt direct cast first (works when CloudSim stores Object tag)
            Object raw = ev.getTag();
            if (raw instanceof FogEvents) return (FogEvents) raw;
        } catch (Exception ignored) {
            // getTag() may return primitive int in some CloudSim versions;
            // fall through to ordinal lookup below.
        }

        // Ordinal-based fallback (standard CloudSim 3.x / 4.x returns int)
        try {
            int ordinal = ev.getTag();
            FogEvents[] vals = values();
            if (ordinal >= 0 && ordinal < vals.length) return vals[ordinal];
        } catch (Exception ignored) { /* nothing */ }

        return null; // unrecognised tag
    }

    /**
     * Convert an int ordinal to a FogEvents constant.
     * Useful when you already have the tag as a plain int.
     *
     * @param ordinal  the int returned by SimEvent.getTag()
     * @return the FogEvents constant, or null if out of range
     */
    public static FogEvents fromOrdinal(int ordinal) {
        FogEvents[] vals = values();
        if (ordinal >= 0 && ordinal < vals.length) return vals[ordinal];
        return null;
    }
}