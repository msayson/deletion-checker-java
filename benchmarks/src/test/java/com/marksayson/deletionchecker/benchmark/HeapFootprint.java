package com.marksayson.deletionchecker.benchmark;

import java.lang.management.ManagementFactory;

/**
 * Approximate retained-heap measurement by GC delta: settle the heap with repeated full GCs (the
 * {@code benchmark} task runs with {@code -XX:+UseParallelGC} so {@link System#gc()} is a
 * stop-the-world collection), then read live heap usage. Take {@code used()} before and after
 * building a structure — while holding a strong reference — to get its retained size, ±roughly 10%.
 */
final class HeapFootprint {

    private HeapFootprint() {
    }

    static long used() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
