package org.yurlib.server.library.application;

import java.util.concurrent.Semaphore;

public final class IngestionResourceGovernor {

    private static final int OPEN_FILES_PER_TASK = 4;
    private static final int MAXIMUM_MEMORY_MIB = 512;
    private static final int MAXIMUM_OPEN_FILES = 32;
    private static final int MAXIMUM_CPU_PERMITS = 4;
    private static final int MAXIMUM_DATABASE_PERMITS = 8;

    private final Semaphore memoryMib;
    private final Semaphore openFiles;
    private final Semaphore cpu;
    private final Semaphore database;

    public IngestionResourceGovernor(int memoryMib, int openFiles, int cpuPermits, int databasePermits) {
        requireRange(memoryMib, 128, MAXIMUM_MEMORY_MIB, "memoryMib");
        requireRange(openFiles, OPEN_FILES_PER_TASK, MAXIMUM_OPEN_FILES, "openFiles");
        requireRange(cpuPermits, 1, MAXIMUM_CPU_PERMITS, "cpuPermits");
        requireRange(databasePermits, 1, MAXIMUM_DATABASE_PERMITS, "databasePermits");
        this.memoryMib = positiveSemaphore(memoryMib, "memoryMib");
        this.openFiles = positiveSemaphore(openFiles, "openFiles");
        cpu = positiveSemaphore(cpuPermits, "cpuPermits");
        database = positiveSemaphore(databasePermits, "databasePermits");
    }

    public Lease acquire(int memoryReservationMib) throws InterruptedException {
        if (memoryReservationMib != 64 && memoryReservationMib != 128) {
            throw new IllegalArgumentException("memoryReservationMib must be 64 or 128");
        }
        memoryMib.acquire(memoryReservationMib);
        try {
            openFiles.acquire(OPEN_FILES_PER_TASK);
            try {
                cpu.acquire();
                try {
                    database.acquire();
                    return new Lease(memoryReservationMib);
                } catch (InterruptedException failure) {
                    cpu.release();
                    throw failure;
                }
            } catch (InterruptedException failure) {
                openFiles.release(OPEN_FILES_PER_TASK);
                throw failure;
            }
        } catch (InterruptedException failure) {
            memoryMib.release(memoryReservationMib);
            throw failure;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                memoryMib.availablePermits(),
                openFiles.availablePermits(),
                cpu.availablePermits(),
                database.availablePermits());
    }

    private static Semaphore positiveSemaphore(int permits, String name) {
        if (permits <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return new Semaphore(permits, true);
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    public record Snapshot(int availableMemoryMib, int availableOpenFiles, int availableCpu, int availableDatabase) {}

    public final class Lease implements AutoCloseable {

        private final int memoryReservationMib;
        private boolean open = true;

        private Lease(int memoryReservationMib) {
            this.memoryReservationMib = memoryReservationMib;
        }

        @Override
        public void close() {
            if (open) {
                database.release();
                cpu.release();
                openFiles.release(OPEN_FILES_PER_TASK);
                memoryMib.release(memoryReservationMib);
                open = false;
            }
        }
    }
}
