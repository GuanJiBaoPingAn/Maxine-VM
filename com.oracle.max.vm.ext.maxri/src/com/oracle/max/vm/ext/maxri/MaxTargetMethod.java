/*
 * Copyright (c) 2017-2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2014, 2015, Andrey Rodchenko. All rights reserved.
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.vm.ext.maxri;

import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.compiler.CallEntryPoint.*;
import static com.sun.max.vm.compiler.deopt.Deoptimization.*;
import static com.sun.max.vm.compiler.target.Stub.Type.*;
import static com.sun.max.vm.stack.StackReferenceMapPreparer.*;

import java.util.*;

import com.oracle.max.asm.target.riscv64.RISCV64;
import com.sun.cri.ci.*;
import com.sun.cri.ci.CiCallingConvention.*;
import com.sun.cri.ci.CiRegister.*;
import com.sun.cri.ci.CiTargetMethod.*;
import com.sun.cri.ri.*;
import com.sun.max.annotate.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.code.CodeManager.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.deopt.Deoptimization;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.compiler.target.aarch64.Aarch64TargetMethodUtil;
import com.sun.max.vm.compiler.target.amd64.*;
import com.sun.max.vm.compiler.target.arm.*;
import com.sun.max.vm.compiler.target.riscv64.RISCV64TargetMethodUtil;
import com.sun.max.vm.object.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;

/**
 * This class implements a {@link TargetMethod target method} for
 * the Maxine VM compiled by a compiler written against the
 * CRI project.
 */
public class MaxTargetMethod extends TargetMethod implements Cloneable {

    /**
     * An array of pairs denoting the code positions protected by an exception handler.
     * A pair {@code {p,h}} at index {@code i} in this array specifies that code position
     * {@code h} is the handler for an exception of type {@code t} occurring at position
     * {@code p} where {@code t} is the element at index {@code i / 2} in {@link #exceptionClassActors}.
     */
    private int[] exceptionPositionsToCatchPositions;

    /**
     * @see #exceptionPositionsToCatchPositions
     */
    private ClassActor[] exceptionClassActors;

    /**
     * The handler BCI values corresponding to {@link #exceptionPositionsToCatchPositions}.
     * Needed by JVMTI.
     */
    private int[] exceptionHandlerBCIs;

    /**
     * Method actor of MaxMiscLowerings.throwException method.
     */
    private static StaticMethodActor MaxMiscLoweringsThrowExceptionMethodActor;

    /**
     * Method actor of MaxRuntimeCalls.runtimeUnwindException method.
     */
    private static StaticMethodActor MaxRuntimeCallsRuntimeUnwindExceptionMethodActor;

    /**
     * Method actor of MaxXirGenerator$RuntimeCalls.monitorEnter method.
     */
    private static StaticMethodActor MaxXirGeneratorRuntimeCallsMonitorEnterMethodActor;

    /**
     * Debug info.
     */
    private DebugInfo debugInfo;

    private final CodeAnnotation[] annotations;

    @HOSTED_ONLY
    private CiTargetMethod bootstrappingCiTargetMethod;

    private CiTargetMethod debugCiTargetMethod;

    public MaxTargetMethod(ClassMethodActor classMethodActor, CiTargetMethod ciTargetMethod, boolean install) {
        super(classMethodActor, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        assert classMethodActor != null;
        List<CodeAnnotation> annotations = ciTargetMethod.annotations();
        this.annotations = annotations == null ? null : annotations.toArray(new CodeAnnotation[annotations.size()]);
        init(ciTargetMethod, install);
    }

    private void init(CiTargetMethod ciTargetMethod, boolean install) {

        if (isHosted()) {
            // Save the target method for later gathering of calls and duplication
            this.bootstrappingCiTargetMethod = ciTargetMethod;
        } else {
            debugCiTargetMethod = ciTargetMethod;
        }

        for (Mark mark : ciTargetMethod.marks) {
            FatalError.unexpected("Unknown mark in code generated for " + this + ": " + mark);
        }

        if (classMethodActor != null) {
            int customStackAreaOffset = ciTargetMethod.customStackAreaOffset();
            if (customStackAreaOffset != DEOPT_RETURN_ADDRESS_OFFSET) {
                throw new InternalError("custom stack area offset should be " + DEOPT_RETURN_ADDRESS_OFFSET + ", not " + customStackAreaOffset);
            }
        }

        initCodeBuffer(ciTargetMethod, install);
        initFrameLayout(ciTargetMethod);
        CiDebugInfo[] debugInfos = initSafepoints(ciTargetMethod);
        initExceptionTable(ciTargetMethod);

        debugInfo = new DebugInfo(debugInfos, this);

        if (!isHosted()) {
            if (install) {
                linkDirectCalls();
                if (Platform.target().arch.isARM() || Platform.target().arch.isAarch64() || Platform.target().arch.isRISCV64()) {
                    MaxineVM.maxine_cache_flush(codeStart().toPointer(), code().length);
                }
            } else {
                // the displacement between a call site in the heap and a code cache location may not fit in the offset
                // operand of a call
            }
        }
    }

    @Override
    public Lifespan lifespan() {
        return Lifespan.LONG;
    }

    @Override
    public CodeAnnotation[] annotations() {
        return annotations;
    }

    /**
     * Gets the size (in bytes) of a bit map covering all the registers that may store references.
     * The bit position of a register in the bit map is the register's {@linkplain CiRegister#encoding encoding}.
     */
    @FOLD
    public static int regRefMapSize() {
        return ByteArrayBitMap.computeBitMapSize(target().arch.registerReferenceMapBitCount);
    }

    /**
     * @return the size of an activation frame for this target method in words.
     */
    private int frameWords() {
        return frameSize() / Word.size();
    }

    @Override
    public VMFrameLayout frameLayout() {
        if (platform().isa == ISA.AMD64) {
            return AMD64TargetMethodUtil.frameLayout(this);
        } else if (platform().isa == ISA.ARM) {
            return ARMTargetMethodUtil.frameLayout(this);
        } else {
            throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxTargetMethod.frameLayout");
        }
    }

    /**
     * @return the size (in bytes) of a reference map covering an activation frame for this target method.
     */
    public int frameRefMapSize() {
        return ByteArrayBitMap.computeBitMapSize(frameWords());
    }

    /**
     * @return the size (in bytes) of the frame reference map plus the register reference map.
     */
    public int totalRefMapSize() {
        return regRefMapSize() + frameRefMapSize();
    }

    public DebugInfo debugInfo() {
        return debugInfo;
    }

    private static int totalHandlersSize;

    private void initExceptionTable(CiTargetMethod ciTargetMethod) {
        if (ciTargetMethod.exceptionHandlers.size() > 0) {
            totalHandlersSize += ciTargetMethod.exceptionHandlers.size();
            exceptionPositionsToCatchPositions = new int[ciTargetMethod.exceptionHandlers.size() * 2];
            exceptionClassActors = new ClassActor[ciTargetMethod.exceptionHandlers.size()];
            exceptionHandlerBCIs = new int[ciTargetMethod.exceptionHandlers.size()];

            int z = 0;
            for (ExceptionHandler handler : ciTargetMethod.exceptionHandlers) {
                exceptionPositionsToCatchPositions[z * 2] = handler.pcOffset;
                exceptionPositionsToCatchPositions[z * 2 + 1] = handler.handlerPos;
                exceptionClassActors[z] = (handler.exceptionType == null) ? null : (ClassActor) handler.exceptionType;
                exceptionHandlerBCIs[z] = handler.handlerBci;
                z++;
            }
        }
    }

    @Override
    public boolean isPatchableCallSite(CodePointer callSite) {
        if (platform().isa == ISA.AMD64) {
            return AMD64TargetMethodUtil.isPatchableCallSite(callSite);
        } else if (platform().isa == ISA.ARM) {
            return ARMTargetMethodUtil.isPatchableCallSite(callSite);
        } else if (platform().target.arch.isAarch64()) {
            return Aarch64TargetMethodUtil.isPatchableCallSite(callSite);
        } else if (platform().target.arch.isRISCV64()) {
            return RISCV64TargetMethodUtil.isPatchableCallSite(callSite);
        } else {
            throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxTargetMethod.isPatchableCallSite");
        }
    }

    @Override
    public CodePointer fixupCallSite(int callOffset, CodePointer callEntryPoint) {
        if (platform().isa == ISA.AMD64) {
            return AMD64TargetMethodUtil.fixupCall32Site(this, callOffset, callEntryPoint);
        } else if (platform().isa == ISA.ARM) {
            return ARMTargetMethodUtil.fixupCall32Site(this, callOffset, callEntryPoint);
        } else if (platform().target.arch.isAarch64()) {
            return Aarch64TargetMethodUtil.fixupCall32Site(this, callOffset, callEntryPoint);
        } else if (platform().target.arch.isRISCV64()) {
            return RISCV64TargetMethodUtil.fixupCall32Site(this, callOffset, callEntryPoint);
        } else {
            throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxTargetMethod.fixupCallSite");
        }
    }

    @Override
    public CodePointer patchCallSite(int callOffset, CodePointer callEntryPoint) {
        if (platform().isa == ISA.AMD64) {
            return AMD64TargetMethodUtil.mtSafePatchCallDisplacement(this, codeAt(callOffset), callEntryPoint);
        } else if (platform().isa == ISA.ARM) {
            return ARMTargetMethodUtil.mtSafePatchCallDisplacement(this, codeAt(callOffset), callEntryPoint);
        } else {
            throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxTargetMethod.patchCallSite");
        }
    }

    @Override
    public void redirectTo(TargetMethod tm) {
        if (platform().isa == ISA.AMD64) {
            AMD64TargetMethodUtil.patchWithJump(this, OPTIMIZED_ENTRY_POINT.offset(), OPTIMIZED_ENTRY_POINT.in(tm));
            if (vm().compilationBroker.needsAdapters()) {
                AMD64TargetMethodUtil.patchWithJump(this, BASELINE_ENTRY_POINT.offset(), BASELINE_ENTRY_POINT.in(tm));
            }
        } else if (platform().isa == ISA.ARM) {
            ARMTargetMethodUtil.patchWithJump(this, OPTIMIZED_ENTRY_POINT.offset(), OPTIMIZED_ENTRY_POINT.in(tm));
            if (vm().compilationBroker.needsAdapters()) {
                ARMTargetMethodUtil.patchWithJump(this, BASELINE_ENTRY_POINT.offset(), BASELINE_ENTRY_POINT.in(tm));
            }
        } else if (platform().isa == ISA.Aarch64) {
            // Aarch64 entry points are patched in one pass to avoid overlapping long range call-site patches.
            Aarch64TargetMethodUtil.patchWithJump(this, OPTIMIZED_ENTRY_POINT.in(tm));
        } else if (platform().isa == ISA.RISCV64) {
            RISCV64TargetMethodUtil.patchWithJump(this, OPTIMIZED_ENTRY_POINT.offset(), OPTIMIZED_ENTRY_POINT.in(tm));
            if (vm().compilationBroker.needsAdapters()) {
                RISCV64TargetMethodUtil.patchWithJump(this, BASELINE_ENTRY_POINT.offset(), BASELINE_ENTRY_POINT.in(tm));
            }
        } else {
            throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxTargetMethod.redirectTo");
        }
        FatalError.check(Stubs.isJumpToStaticTrampoline(this), "sanity check");
    }

    @Override
    public CodePointer throwAddressToCatchAddress(CodePointer throwAddress, Throwable exception) {
        return throwAddressToCatchAddress(throwAddress, exception, null);
    }

    @Override
    public boolean catchExceptionInfo(StackFrameCursor current, Throwable throwable, CatchExceptionInfo info) {
        CodePointer codePointer = throwAddressToCatchAddress(current.vmIP(), throwable, info);
        if (!codePointer.isZero()) {
            info.codePointer = codePointer;
            return true;
        } else {
            return false;
        }
    }

    private CodePointer throwAddressToCatchAddress(CodePointer throwAddress, Throwable exception, CatchExceptionInfo info) {
        final int exceptionPos = throwAddress.minus(codeStart()).toInt();
        int count = getExceptionHandlerCount();
        for (int i = 0; i < count; i++) {
            int codePos = getExceptionPosAt(i);
            int catchPos = getCatchPosAt(i);
            ClassActor catchType = getCatchTypeAt(i);

            if ((codePos == exceptionPos) && checkType(exception, catchType)) {
                if (info != null) {
                    info.bci = getHandlerBCIAt(i);
                }
                return codeAt(catchPos);
            }
        }
        return CodePointer.zero();
    }

    private boolean checkType(Throwable exception, ClassActor catchType) {
        return catchType == null || catchType.isAssignableFrom(ObjectAccess.readClassActor(exception));
    }

    /**
     * Gets the exception code position of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception code position of element {@code i} in the exception handler table
     */
    private int getExceptionPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2];
    }

    /**
     * Gets the exception handler code position of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception handler position of element {@code i} in the exception handler table
     */
    private int getCatchPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2 + 1];
    }

    /**
     * Gets the exception type of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception type of element {@code i} in the exception handler table
     */
    private ClassActor getCatchTypeAt(int i) {
        return exceptionClassActors[i];
    }

    /**
     * Gets the number of entries in the exception handler table.
     */
    private int getExceptionHandlerCount() {
        return exceptionClassActors == null ? 0 : exceptionClassActors.length;
    }

    /**
     * Used in JVMTI context to get the bci for a catch address.
     */
    private int getHandlerBCIAt(int i) {
        return exceptionHandlerBCIs[i];
    }

    @HOSTED_ONLY
    private void gatherInlinedMethods(Safepoint safepoint, Set<MethodActor> inlinedMethods) {
        CiDebugInfo debugInfo = safepoint.debugInfo;
        if (debugInfo != null) {
            for (CiCodePos pos = debugInfo.codePos; pos != null; pos = pos.caller) {
                inlinedMethods.add((MethodActor) pos.method);
            }
        }
    }

    @Override
    @HOSTED_ONLY
    public void gatherCalls(Set<MethodActor> directCalls, Set<MethodActor> virtualCalls, Set<MethodActor> interfaceCalls, Set<MethodActor> inlinedMethods) {
        // first gather methods in the directCallees array
        if (directCallees != null) {
            for (Object o : directCallees) {
                if (o instanceof MethodActor) {
                    directCalls.add((MethodActor) o);
                }
            }
        }

        // iterate over direct calls
        for (Safepoint safepoint : bootstrappingCiTargetMethod.safepoints) {
            if (safepoint instanceof Call) {
                Call call = (Call) safepoint;
                MethodActor callee = CallTarget.asMethodActor(call.target);
                if (callee != null) {
                    if (call.direct) {
                        directCalls.add(callee);
                    } else {
                        if (callee.holder().isInterface()) {
                            interfaceCalls.add(callee);
                        } else {
                            virtualCalls.add(callee);
                        }
                    }
                }
            }
            gatherInlinedMethods(safepoint, inlinedMethods);
        }
    }

    @HOSTED_ONLY
    private ClassMethodActor getClassMethodActor(CiRuntimeCall runtimeCall, RiMethod method) {
        if (method != null) {
            return (ClassMethodActor) method;
        }

        assert runtimeCall != null : "A call can either be a call to a method or a runtime call";
        return MaxRuntimeCalls.getClassMethodActor(runtimeCall);
    }

    /**
     * Prepares the reference map for this frame.
     *
     * @param current the current frame
     * @param callee the callee frame
     * @param preparer the reference map preparer
     */
    @Override
    public void prepareReferenceMap(StackFrameCursor current, StackFrameCursor callee, FrameReferenceMapVisitor preparer) {
        CiCalleeSaveLayout csl = callee.csl();
        Pointer csa = callee.csa();
        TargetMethod calleeTM = callee.targetMethod();
        if (calleeTM != null) {
            Stub.Type st = calleeTM.stubType();
            if (st == StaticTrampoline || st == VirtualTrampoline || st == InterfaceTrampoline) {
                prepareTrampolineRefMap(current, callee, preparer);
            } else if (calleeTM.is(TrapStub) && Trap.Number.isStackOverflow(csa)) {
                // a method can never catch stack overflow for itself so there
                // is no need to scan the references in the trapped method
                return;
            }
        }

        int safepointIndex = findSafepointIndex(current.vmIP());
        if (safepointIndex < 0) {
            // this is very bad.
            Log.print(posFor(current.vmIP()));
            Log.print(" ");
            Log.printLocation(current.targetMethod(), current.vmIP(), false);
            Log.println();
            throw FatalError.unexpected("could not find safepoint index");
        }

        int frameRefMapSize = frameRefMapSize();
        if (!csa.isZero()) {
            // the callee contains register state from this frame;
            // use register reference maps in this method to fill in the map for the callee
            Pointer slotPointer = csa;
            int byteIndex = debugInfo.regRefMapStart(safepointIndex);
            preparer.logPrepareReferenceMap(this, safepointIndex, slotPointer, "registers");

            // Need to translate from register numbers (as stored in the reg ref maps) to frame slots.
            for (int i = 0; i < regRefMapSize(); i++) {
                int b = debugInfo.data[byteIndex] & 0xff;
                int reg = i * 8;
                while (b != 0) {
                    if ((b & 1) != 0) {
                        int offset = csl.offsetOf(reg);
                        if (logStackRootScanning()) {
                            StackReferenceMapPreparer.stackRootScanLogger.logRegisterState(csl.registers[reg]);
                        }
                        preparer.visitReferenceMapBits(callee, slotPointer.plus(offset), 1, 1);
                    }
                    reg++;
                    b = b >>> 1;
                }
                byteIndex++;
            }
        }

        // prepare the map for this stack frame
        Pointer slotPointer = current.sp();
        preparer.logPrepareReferenceMap(this, safepointIndex, slotPointer, "frame");
        int byteIndex = debugInfo.frameRefMapStart(safepointIndex);
        for (int i = 0; i < frameRefMapSize; i++) {
            preparer.visitReferenceMapBits(current, slotPointer, debugInfo.data[byteIndex] & 0xff, Bytes.WIDTH);
            slotPointer = slotPointer.plusWords(Bytes.WIDTH);
            byteIndex++;
        }
    }

    /**
     * Prepares the reference map for the frame of a call to a trampoline from an OPT compiled method.
     *
     * An opto-compiled caller may pass some arguments in registers.  The trampoline is polymorphic, i.e. it does not have any
     * helpful maps regarding the actual callee.  It does store all potential parameter registers on its stack, though,
     * and recovers them before returning.  We mark those that contain references.
     *
     * @param current
     * @param callee
     * @param preparer
     */
    public static void prepareTrampolineRefMap(StackFrameCursor current, StackFrameCursor callee, FrameReferenceMapVisitor preparer) {
        RiRegisterConfig registerConfig = vm().registerConfigs.trampoline;
        TargetMethod trampoline = callee.targetMethod();
        ClassMethodActor calledMethod = null;
        MaxTargetMethod targetMethod = (MaxTargetMethod) current.targetMethod();

        CiCalleeSaveLayout csl = callee.csl();
        Pointer csa = callee.csa();
        FatalError.check(csl != null && !csa.isZero(), "trampoline must have callee save area");
        CiRegister[] cpuRegs = registerConfig.getCallingConventionRegisters(Type.JavaCall, RegisterFlag.CPU);

        // figure out what method the caller is trying to call
        if (trampoline.is(StaticTrampoline)) {
            int dcIndex = 0;
            Safepoints safepoints = targetMethod.safepoints();
            int safepointPos = targetMethod.posFor(current.vmIP());
            for (int safepointIndex = safepoints.nextDirectCall(0); safepointIndex >= 0; safepointIndex = safepoints.nextDirectCall(safepointIndex + 1)) {
                if (safepoints.posAt(safepointIndex) == safepointPos) {
                    calledMethod = (ClassMethodActor) targetMethod.directCallees()[dcIndex];
                    break;
                }
                dcIndex++;
            }
            if (calledMethod == null) {
                // this is very bad.
                throw FatalError.unexpected("could not find stop index");
            }
        } else {
            // this is a virtual or interface call; figure out the receiver method based on the
            // virtual or interface index
            Object receiver = csa.plus(csl.offsetOf(cpuRegs[0])).getReference().toJava();
            ClassActor classActor = ObjectAccess.readClassActor(receiver);
            // The virtual dispatch trampoline stubs put the virtual dispatch index into the
            // scratch register and then saves it to the stack.
            int index = vm().stubs.readVirtualDispatchIndexFromTrampolineFrame(csa);
            if (trampoline.is(VirtualTrampoline)) {
                calledMethod = classActor.getVirtualMethodActorByVTableIndex(index);
            } else {
                assert trampoline.is(InterfaceTrampoline);
                calledMethod = classActor.getVirtualMethodActorByIIndex(index);
            }
        }

        int regIndex = 0;
        boolean isStatic = calledMethod.isStatic();
        if (!isStatic) {
            // set a bit for the receiver object
            int offset = csl.offsetOf(cpuRegs[regIndex++]);
            preparer.visitReferenceMapBits(current, csa.plus(offset), 1, 1);
        }

        SignatureDescriptor sig = calledMethod.descriptor();
        int numParameters = sig.numberOfParameters();
        for (int i = 0; i < numParameters && regIndex < cpuRegs.length; ++i) {
            TypeDescriptor arg = sig.parameterDescriptorAt(i);
            CiRegister reg = cpuRegs[regIndex];
            Kind kind = arg.toKind();
            if (kind.isReference) {
                // set a bit for this parameter
                int offset = csl.offsetOf(reg);
                preparer.visitReferenceMapBits(current, csa.plus(offset), 1, 1);
            }
            if (kind != Kind.FLOAT && kind != Kind.DOUBLE) {
                // Only iterating over the integral arg registers
                regIndex++;
            }
        }

        // Now handle any overflow arguments that might be destroyed by a GC during the trampoline.
        // The need for this is compiler dependent.
        if (!targetMethod.needsTrampolineRefMapOverflowArgHandling()) {
            return;
        }

        d1(regIndex, numParameters);
        if (numParameters + (isStatic ? 0 : 1) < regIndex) {
            // Can't be any overflow reference parameters
            return;
        }
        // This is tricky since we can't allocate, so can't just use the normal method in CiRegisterConfig
        // that assigns registers and computes stack slot offsets. Also, since the latter is VM independent,
        // we can't create a variant that uses a SignatureDescriptor, so we inline similar logic here.

        CiRegister[] fpuRegs = registerConfig.getCallingConventionRegisters(Type.JavaCall, RegisterFlag.FPU);
        CiRegisterConfig javaCallConfig = vm().registerConfigs.standard;
        int currentGeneral = isStatic ? 0 : 1;
        int currentXMM = 0;
        int firstStackIndex = (javaCallConfig.stackArg0Offsets[Type.JavaCall.ordinal()]) / Platform.platform().target.spillSlotSize;
        int currentStackIndex = firstStackIndex;

        for (int i = 0; i < numParameters; i++) {
            final CiKind kind = sig.argumentKindAt(i, true);
            boolean inReg = false;

            if (kind == CiKind.Byte || kind == CiKind.Boolean || kind == CiKind.Short || kind == CiKind.Char || kind == CiKind.Int ||
                            kind == CiKind.Long || kind == CiKind.Object) {
                if (currentGeneral < cpuRegs.length) {
                    inReg = true;
                    currentGeneral++;
                }
            } else if (kind == CiKind.Float || kind == CiKind.Double) {
                if (currentXMM < fpuRegs.length) {
                    currentXMM++;
                    inReg = true;
                }
            }
            d2(kind, inReg);

            if (!inReg) {
                if (kind == CiKind.Object) {
                    targetMethod.prepareTrampolineRefMapHandleOverflowParam(current, calledMethod, currentStackIndex * Platform.platform().target.spillSlotSize, preparer);
                }
                currentStackIndex += Platform.platform().target.spillSlots(kind);
            }
        }
    }

    @NEVER_INLINE
    private static void d1(int regIndex, int numParameters) {

    }

    @NEVER_INLINE
    private static void d2(CiKind kind, boolean inReg) {

    }

    protected boolean needsTrampolineRefMapOverflowArgHandling() {
        return false;
    }

    /**
     * Provides a hook for handling an overflow (stack stored) reference parameter in the reference map.
     * The default implementation does nothing as C1X handles this in an ad hoc way.
     * @param current
     * @param calledMethod
     * @param offset
     * @param preparer
     */
    protected void prepareTrampolineRefMapHandleOverflowParam(StackFrameCursor current, ClassMethodActor calledMethod, int offset, FrameReferenceMapVisitor preparer) {
    }

    /**
     * Attempt to catch an exception that has been thrown with this method on the call stack.
     * @param current the current stack frame
     * @param callee the callee stack frame
     * @param context the stack unwinding context
     */
    @Override
    public void catchException(StackFrameCursor current, StackFrameCursor callee, StackUnwindingContext context) {
        CodePointer ip = current.vmIP();
        Pointer sp = current.sp();
        Pointer fp = current.fp();
        Throwable throwable = context.throwable;
        ClassMethodActor calleeCMA = context.lastCalleeCMA;
        CodePointer catchAddress = throwAddressToCatchAddress(ip, throwable);
        boolean isMethodDeoptimized = invalidated() != null;
        boolean isMethodHandlerFound = !catchAddress.isZero();
        boolean isOriginalHandlerFoundOrMethodSynchronized = false;

        TargetMethod tm = current.targetMethod();
        int si = tm.findSafepointIndex(ip);
        // skip method of the frame being unwinded or not at a safepoint
        if (si != -1 && calleeCMA != MaxMiscLoweringsThrowExceptionMethodActor && calleeCMA != MaxRuntimeCallsRuntimeUnwindExceptionMethodActor) {
            int frameChainLength = debugInfo.retrieveFrameChainLength(si, true);
            for (int frameIndex = 0; frameIndex < frameChainLength; frameIndex++) {
                ClassMethodActor frameCMA = debugInfo.retrieveClassMethodActor(si, true, frameIndex);
                int frameBCI = debugInfo.retrieveBCI(si, true, frameIndex);
                if (frameCMA.findCachedHandlerForException(frameBCI, throwable) != null ||
                    frameCMA.isSynchronized() && (frameIndex != 0 || calleeCMA != MaxXirGeneratorRuntimeCallsMonitorEnterMethodActor)) {
                    // mark current method for deoptimization if exception handler was not compiled
                    if (catchAddress.isZero()) {
                        Pointer returnAddress = callee.targetMethod().returnAddressPointer(callee).readWord(0).asPointer();
                        if (!Stub.isDeoptStubEntry(returnAddress, Code.codePointerToTargetMethod(returnAddress), callee)) {
                            Deoptimization.patchReturnAddress(current, callee, calleeCMA);
                        }
                        isMethodDeoptimized = true;
                    }
                    // set indication that original exception handler exists for this frame location and exception
                    // or method is synchronized (so that an extra exception handler exists in order to exit a monitor)
                    isOriginalHandlerFoundOrMethodSynchronized = true;
                    break;
                }
            }
        }
        if (isOriginalHandlerFoundOrMethodSynchronized || isMethodHandlerFound && !isMethodDeoptimized) {
            if (StackFrameWalker.TraceStackWalk) {
                Log.print("StackFrameWalk: Handler position for exception at position ");
                Log.print(ip.minus(codeStart()).toInt());
                Log.print(" is ");
                if (catchAddress.isZero()) {
                    Log.println("absent");
                } else {
                    Log.println(catchAddress.minus(codeStart()).toInt());
                }
            }

            int catchPos = posFor(isMethodDeoptimized ? ip : catchAddress);
            if (isMethodDeoptimized) {
                assert current.sp().readWord(DEOPT_RETURN_ADDRESS_OFFSET).equals(current.ipAsPointer()) : "real caller IP should have been saved in rescue slot";
                Pointer returnAddress = callee.targetMethod().returnAddressPointer(callee).readWord(0).asPointer();
                assert Stub.isDeoptStubEntry(returnAddress, Code.codePointerToTargetMethod(returnAddress), callee) :
                    "the return address of a method that was on the stack when marked for deoptimization should have been patched with a deopt stub";

                Stub voidDeoptStub = vm().stubs.deoptStub(CiKind.Void, callee.targetMethod().is(CompilerStub));
                if (deoptLogger.enabled()) {
                    deoptLogger.logCatchException(this, Code.codePointerToTargetMethod(returnAddress), voidDeoptStub, sp, fp);
                }

                catchAddress = voidDeoptStub.codeStart();
            }

            TargetMethod calleeMethod = callee.targetMethod();
            // Reset the stack walker
            current.stackFrameWalker().reset();

            // Store the exception for the handler
            VmThread.current().storeExceptionForHandler(throwable, this, catchPos);

            if (calleeMethod != null && calleeMethod.registerRestoreEpilogueOffset() != -1) {
                unwindToCalleeEpilogue(catchAddress, sp, calleeMethod);
            } else {
                Stubs.unwind(catchAddress.toPointer(), sp, fp);
            }
            throw ProgramError.unexpected("Should not reach here, unwind must jump to the exception handler!");
        }
    }

    @NEVER_INLINE
    public static void unwindToCalleeEpilogue(CodePointer catchAddress, Pointer stackPointer, TargetMethod lastJavaCallee) {
        // Overwrite return address of callee with catch address
        final int slotSize = target().arch.isAarch64() || target().arch.isRISCV64() ? JVMSFrameLayout.JVMS_SLOT_SIZE : Word.size();
        final Pointer returnAddressPointer = stackPointer.minus(slotSize);
        returnAddressPointer.setWord(catchAddress.toAddress());

        CodePointer epilogueAddress = lastJavaCallee.codeAt(lastJavaCallee.registerRestoreEpilogueOffset());

        final Pointer calleeStackPointer = stackPointer.minus(slotSize).minus(lastJavaCallee.frameSize());
        Stubs.unwind(epilogueAddress.toPointer(), calleeStackPointer, Pointer.zero());
    }

    /**
     * Accept a visitor for this frame.
     * @param current the current stack frame
     * @param visitor the visitor
     * @return {@code true} if the stack walker should continue walking, {@code false} if the visitor is finished visiting
     */
    @Override
    @HOSTED_ONLY
    public boolean acceptStackFrameVisitor(StackFrameCursor current, StackFrameVisitor visitor) {
        if (platform().isa == ISA.AMD64) {
            return AMD64TargetMethodUtil.acceptStackFrameVisitor(current, visitor);
        } else if (platform().isa == ISA.ARM) {
            return ARMTargetMethodUtil.acceptStackFrameVisitor(current, visitor);
        }
        throw FatalError.unimplemented("acceptStackFrameVisitor");
    }

    /**
     * Advances the cursor to the caller's frame.
     * @param current the current frame
     */
    @Override
    public void advance(StackFrameCursor current) {
        CiCalleeSaveLayout csl = calleeSaveLayout();
        Pointer csa = Pointer.zero();
        if (csl != null) {
            // See FrameMap
            csa = current.sp().plus(frameSize() - csl.size);
        }
        advanceHelper(current, csl, csa);
    }

    @Override
    public Pointer returnAddressPointer(StackFrameCursor frame) {
        if (platform().isa == ISA.AMD64) {
            return AMD64TargetMethodUtil.returnAddressPointer(frame);
        } else if (platform().isa == ISA.ARM) {
            return ARMTargetMethodUtil.returnAddressPointer(frame);
        } else if (platform().isa == ISA.Aarch64) {
            return Aarch64TargetMethodUtil.returnAddressPointer(frame);
        } else if (platform().isa == ISA.RISCV64) {
            return RISCV64TargetMethodUtil.returnAddressPointer(frame);
        } else {
            throw FatalError.unimplemented("com.oracle.max.vm.ext.maxri.MaxTargetMethod.returnAddressPointer");
        }
    }

    @Override
    public int forEachCodePos(CodePosClosure cpc, CodePointer ip) {
        int index = findSafepointIndex(ip);
        if (index < 0) {
            return 0;
        }

        return debugInfo.forEachCodePos(cpc, index);
    }

    @Override
    public CiDebugInfo debugInfoAt(int stopIndex, FrameAccess fa) {
        return debugInfo.infoAt(stopIndex, fa, true);
    }

    @HOSTED_ONLY
    public static void initializeMaxMiscLoweringsThrowExceptionMethodActor(StaticMethodActor methodActor) {
        MaxMiscLoweringsThrowExceptionMethodActor = methodActor;
    }

    @HOSTED_ONLY
    public static void initializeMaxRuntimeCallsRuntimeUnwindExceptionMethodActor(StaticMethodActor methodActor) {
        MaxRuntimeCallsRuntimeUnwindExceptionMethodActor = methodActor;
    }

    @HOSTED_ONLY
    public static void initializeMaxXirGeneratorRuntimeCallsMonitorEnterMethodActor(StaticMethodActor methodActor) {
        MaxXirGeneratorRuntimeCallsMonitorEnterMethodActor = methodActor;
    }
}
