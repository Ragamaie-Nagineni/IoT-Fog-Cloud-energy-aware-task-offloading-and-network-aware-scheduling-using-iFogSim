package org.fog.utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * DebugLogger — Structured console + file logger for iFogSim simulation.
 *
 * FIX (Eval 3):
 *   Previous version used Unicode box-drawing characters (═, ─, ┌) which
 *   crash on Windows with the default cp1252 console encoding.
 *
 *   This version:
 *     1. Opens the log file with explicit UTF-8 encoding.
 *     2. Redirects System.out to UTF-8 so the console also accepts the chars.
 *     3. Provides a USE_ASCII flag — set to true if your terminal still shows
 *        garbled characters, and plain = / - lines will be used instead.
 */
public class DebugLogger {

    // ─── Configuration ─────────────────────────────────────────────────────────
    private static final String LOG_FILE  = "simulation_output.txt";

    /**
     * Set to true to use plain ASCII separators (= and -)
     * instead of Unicode box-drawing characters.
     * Useful if your Windows terminal doesn't support UTF-8.
     */
    private static final boolean USE_ASCII = false;

    // ─── Separator strings ─────────────────────────────────────────────────────
    private static final String SEP_THICK = USE_ASCII
            ? "======================================================="
            : "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550";

    private static final String SEP_THIN  = USE_ASCII
            ? "  -------------------------------------------------------"
            : "  \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

    // ─── Internals ─────────────────────────────────────────────────────────────
    private static PrintWriter writer;

    static {
        // Redirect System.out to UTF-8 so console handles box-drawing chars
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
        } catch (Exception e) {
            // If this fails, console output may have garbled chars — non-fatal
        }

        // Open log file with explicit UTF-8 encoding (fixes Windows cp1252 crash)
        try {
            writer = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(LOG_FILE, false),
                            StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[DebugLogger] Cannot open log file: " + e.getMessage());
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
        log("");
        log(SEP_THICK);
        log("  " + title);
        log(SEP_THICK);
    }

    public static void subSection(String title) {
        if (USE_ASCII) {
            log("  +-- " + title + " --+");
        } else {
            log("  \u250C\u2500 " + title + " \u2500\u2510");
        }
    }

    public static void separator() {
        log(SEP_THIN);
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
        // Only print every 10 iterations to keep output concise
        if (iter % 10 == 0 || iter == 1) {
            log(String.format("  Iter %3d | MOA=%.3f | MOP=%.3f | BestFit=%.4f",
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