/*
 * Copyright (c) 2018-2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.sun.max.vm.profilers.allocation;

import com.sun.max.annotate.NEVER_INLINE;
import com.sun.max.annotate.NO_SAFEPOINT_POLLS;
import com.sun.max.lang.ISA;
import com.sun.max.memory.VirtualMemory;
import com.sun.max.platform.Platform;
import com.sun.max.unsafe.Pointer;
import com.sun.max.unsafe.Size;
import com.sun.max.vm.Intrinsics;
import com.sun.max.vm.Log;
import com.sun.max.vm.runtime.FatalError;

/**
 * This class implements any buffer used by the Allocation Profiler to keep track of the objects.
 *
 * The following 5 variables compose the stored information for each object:
 * -Index: unique for each object to make it distinguishable. [1-inf] index = 0 for empty cells. The Unique id
 * serves the purpose of following and tracing an object over the profiling cycles.
 * -Type: the object's type/class.
 * -Size: the object's size. Same type-different size Objects might exist (eg. same type arrays with different length).
 * -Address: the object's address in the Heap.
 * -Node: the physical NUMA node where the object is placed.
 */
class RecordBuffer {

    private Pointer ids;
    private Pointer types;
    private Pointer sizes;
    private Pointer addresses;
    private Pointer nodes;
    private Pointer threadIds;
    private Pointer timestamps;
    private Pointer coreIDs;

    String buffersName;
    public int bufferSize;
    int currentIndex;

    /**
     * The maximum Type string length.
     */
    static final int MAX_CHARS = 200;

    /**
     * A char[] buffer to store a string which is being read from native.
     */
    char[] readStringBuffer;

    /**
     * A primitive representation of null string.
     */
    private static final char[] nullValue = {'n', 'u', 'l', 'l', '\0'};

    private long StringBufferSizeInBytes;

    RecordBuffer(int bufSize, String name) {
        buffersName = name;
        bufferSize = bufSize;

        readStringBuffer = new char[MAX_CHARS];

        ids = allocateIntArrayOffHeap(bufSize);
        types = allocateStringArrayOffHeap(bufSize);
        sizes = allocateIntArrayOffHeap(bufSize);
        addresses = allocateLongArrayOffHeap(bufSize);
        nodes = allocateIntArrayOffHeap(bufSize);
        threadIds = allocateIntArrayOffHeap(bufSize);
        timestamps = allocateLongArrayOffHeap(bufSize);
        coreIDs = allocateIntArrayOffHeap(bufSize);

        /**
         * Off-heap String array useful values.
         * Since the end address is not available, we need to calculate it.
         * The VirtualMemory.allocate() method calls the mmap sys call under the hood,
         * so the space requests need to be in bytes.
         * The mmap sys call allocates space in memory page batches.
         * Memory page size in linux is 4kB.
         */
        final long allocSize = (long) bufSize * MAX_CHARS * Character.BYTES;
        final long pageSize = 4096;
        final long numOfAllocPages = allocSize / pageSize + 1;
        StringBufferSizeInBytes = numOfAllocPages * pageSize;

        currentIndex = 0;
    }

    private Pointer allocateIntArrayOffHeap(int size) {
        return VirtualMemory.allocate(Size.fromInt(size).times(Integer.BYTES), VirtualMemory.Type.DATA);
    }

    private Pointer allocateLongArrayOffHeap(int size) {
        return VirtualMemory.allocate(Size.fromInt(size).times(Long.BYTES), VirtualMemory.Type.DATA);
    }

    private Pointer allocateStringArrayOffHeap(int size) {
        Pointer space = VirtualMemory.allocate(Size.fromInt(size).times(MAX_CHARS).times(Character.BYTES),
                VirtualMemory.Type.DATA);

        if (space.isZero()) {
            Log.print(this.buffersName);
            Log.print("'s Type Array Allocation Failed.");
            System.exit(0);
        }
        return space;
    }

    void deallocateAll() {
        final Size intSize = Size.fromInt(bufferSize).times(Integer.BYTES);
        final Size longSize = Size.fromInt(bufferSize).times(Long.BYTES);
        VirtualMemory.deallocate(ids.asAddress(), intSize, VirtualMemory.Type.DATA);
        VirtualMemory.deallocate(types.asAddress(), Size.fromLong(bufferSize).times(MAX_CHARS).times(Character.BYTES), VirtualMemory.Type.DATA);
        VirtualMemory.deallocate(sizes.asAddress(), intSize, VirtualMemory.Type.DATA);
        VirtualMemory.deallocate(addresses.asAddress(), longSize, VirtualMemory.Type.DATA);
        VirtualMemory.deallocate(nodes.asAddress(), intSize, VirtualMemory.Type.DATA);
        VirtualMemory.deallocate(timestamps.asAddress(), longSize, VirtualMemory.Type.DATA);
        VirtualMemory.deallocate(coreIDs.asAddress(), intSize, VirtualMemory.Type.DATA);
    }

    private void writeType(int index, char[] value) {
        long stringIndex = index * MAX_CHARS;
        int charIndex = 0;
        long writeIndex = stringIndex + charIndex;
        char c;

        while (charIndex < value.length) {
            c = value[charIndex];
            if (writeIndex * Character.BYTES >= StringBufferSizeInBytes) {
                Log.print("Off-heap String array overflow detected at index: ");
                Log.println(writeIndex * Character.BYTES);
                Log.println("Suggestion: Increase the AllocationProfilerBufferSize.");
                break;
            }
            types.plus(writeIndex * Character.BYTES).setChar(c);
            if (c == '\0') {
                break;
            }
            charIndex++;
            writeIndex = stringIndex + charIndex;
        }
    }

    void readType(int index) {
        long stringIndex = index * MAX_CHARS;
        int charIndex = 0;
        long readIndex = stringIndex + charIndex;
        char c;

        do {
            c = types.plus(readIndex * Character.BYTES).getChar();
            readStringBuffer[charIndex] = c;
            charIndex++;
            readIndex = stringIndex + charIndex;
        } while (c != '\0');
    }

    public int readId(int index) {
        return readInt(ids, index);
    }

    int readSize(int index) {
        return readInt(sizes, index);
    }

    long readAddr(int index) {
        return readLong(addresses, index);
    }

    void writeNode(int index, int value) {
        writeInt(nodes, index, value);
    }

    private void writeInt(Pointer pointer, int index, int value) {
        pointer.setInt(index, value);
    }

    private void writeLong(Pointer pointer, int index, long value) {
        pointer.setLong(index, value);
    }

    private int readInt(Pointer pointer, int index) {
        return pointer.getInt(index);
    }

    private long readLong(Pointer pointer, int index) {
        return pointer.getLong(index);
    }

    int readThreadId(int index) {
        return readInt(threadIds, index);
    }

    @NO_SAFEPOINT_POLLS("allocation profiler call chain must be atomic")
    @NEVER_INLINE
    public void record(int id, int threadId, char[] type, int size, long address) {
        if (Platform.platform().isa != ISA.AMD64) {
            throw FatalError.unimplemented("RecordBuffer.record");
        }
        final long timestamp = Intrinsics.getTicks();
        final int  coreID    = Intrinsics.getCpuID();
        writeLong(timestamps, currentIndex, timestamp);
        writeInt(coreIDs, currentIndex, coreID);
        writeInt(ids, currentIndex, id);
        writeInt(threadIds, currentIndex, threadId);
        writeType(currentIndex, type);
        writeInt(sizes, currentIndex, size);
        writeLong(addresses, currentIndex, address);
        currentIndex++;
        if (currentIndex >= bufferSize) {
            Log.print("Off-heap Record Buffer overflow detected at index: ");
            Log.println(currentIndex);
            Log.println("Suggestion: Increase the AllocationProfilerBufferSize.");
            System.exit(1);
        }
    }

    @NO_SAFEPOINT_POLLS("allocation profiler call chain must be atomic")
    @NEVER_INLINE
    public void record(int id, int threadId, char[] type, int size, long address, int node) {
        writeNode(currentIndex, node);
        record(id, threadId, type, size, address);
    }

    /**
     * Allocation Profiler Output format.
     * Cycle; isAllocation; UniqueId; ThreadId; Type/Class; Size; NumaNode
     * @param cycle
     * @param allocation
     */
    public void print(int cycle, int allocation) {
        for (int i = 0; i < currentIndex; i++) {
            Log.print(cycle);
            Log.print(";");

            Log.print(allocation);
            Log.print(";");

            Log.print(readInt(ids, i));
            Log.print(";");

            Log.print(readInt(threadIds, i));
            Log.print(";");

            // read and store the string in the readStringBuffer.
            readType(i);
            // print the string char by char.
            int j = 0;
            while (readStringBuffer[j] != '\0') {
                Log.print(readStringBuffer[j]);
                j++;
            }
            // print a semicolon only for primitive types because the rest are already followed by one.
            if (readStringBuffer[j - 1] != ';') {
                Log.print(";");
            }
            Log.print(readInt(sizes, i));
            Log.print(";");
            Log.print(readInt(nodes, i));
            Log.print(";");
            Log.print(readLong(timestamps, i));
            Log.print(";");
            Log.println(readInt(coreIDs, i));
        }
    }

    void printUsage() {
        Log.print("(Allocation Profiler): ");
        Log.print(buffersName);
        Log.print(" usage = ");
        Log.print(currentIndex);
        Log.print(" / ");
        Log.print(bufferSize);
        Log.println(". (This number helps in tuning Buffer's size).");
    }

    public void cleanBufferCell(int i) {
        writeInt(ids, i, 0);
        writeType(i, nullValue);
        writeInt(sizes, i, 0);
        writeLong(addresses, i, 0L);
        writeInt(nodes, i, -1);
        writeLong(timestamps, i, 0L);
        writeInt(coreIDs, i, Integer.MIN_VALUE);
    }

    void resetBuffer() {
        currentIndex = 0;
    }
}
