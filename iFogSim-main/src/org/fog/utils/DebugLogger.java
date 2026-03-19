package org.fog.utils;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

public class DebugLogger {
    private static PrintWriter writer;
    
    static {
        try {
            writer = new PrintWriter(new FileWriter("placement_debug.txt", true));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void log(String message) {
        System.out.println(message);
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }
    
    public static void close() {
        if (writer != null) {
            writer.close();
        }
    }
}