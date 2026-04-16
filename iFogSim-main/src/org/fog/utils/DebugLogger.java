package org.fog.utils;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

/**
 * DebugLogger - Structured console + file logger for iFogSim simulation.
 * Produces clean, readable step-by-step output for evaluation.
 */
public class DebugLogger {

    private static PrintWriter writer;
    private static final String LOG_FILE = "simulation_output.txt";

    static {
        try {
            writer = new PrintWriter(new FileWriter(LOG_FILE, false)); // overwrite each run
        } catch (IOException e) {
            System.err.println("[DebugLogger] Could not open log file: " + e.getMessage());
        }
    }

    // ─── Raw log ───────────────────────────────────────────────────────────────

    public static void log(String message) {
        System.out.println(message);
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }

    // ─── Section headers ───────────────────────────────────────────────────────

    public static void section(String title) {
        String line = "═══════════════════════════════════════════════════════";
        log("");
        log(line);
        log("  " + title);
        log(line);
    }

    public static void subSection(String title) {
        log("  ┌─ " + title + " ─┐");
    }

    public static void separator() {
        log("  ───────────────────────────────────────────────────");
    }

    // ─── Typed log helpers ─────────────────────────────────────────────────────

    public static void step(int stepNum, String message) {
        log(String.format("%n[STEP %d] %s", stepNum, message));
    }

    public static void info(String tag, String message) {
        log(String.format("  [%-14s] %s", tag, message));
    }

    public static void result(String label, String value) {
        log(String.format("  %-35s : %s", label, value));
    }

    public static void taskLog(int taskIdx, String tupleType, String device,
                               double delay, double energy) {
        log(String.format("  Task #%-3d | %-16s -> %-20s | Delay=%8.4f | Energy=%8.4f",
                taskIdx, tupleType, device, delay, energy));
    }

    public static void iterLog(int iter, double moa, double mop, double bestFit) {
        // Only print every 10 iterations to keep output clean
        if (iter % 10 == 0 || iter == 1) {
            log(String.format("  Iter %3d | MOA=%.3f | MOP=%.3f | BestFitness=%.4f",
                    iter, moa, mop, bestFit));
        }
    }

    // ─── Cleanup ───────────────────────────────────────────────────────────────

    public static void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
        }
    }
}
