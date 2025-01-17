#
# Copyright (c) 2019, APT Group, School of Computer Science,
# The University of Manchester. All rights reserved.
# Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

PROJECT = ../../..

LIB = jvm

SOURCES = c.c condition.c log.c image.c $(ISA).c jni.c jvm.c maxine.c memory.c mutex.c \
          relocation.c dataio.c runtime.c snippet.c threads.c threadLocals.c time.c trap.c \
          virtualMemory.c jnitests.c sync.c signal.c jmm.c jvmti.c

TARGETOS ?= $(shell uname -s)
ifeq ($(TARGETOS),Linux)
    TARGETISA ?= $(shell uname -m)
    ifeq ($(TARGETISA), x86_64)
        ISA := amd64
    endif
endif # Linux
ifeq ($(ISA),amd64)
    SOURCES += numa.c
endif

SOURCE_DIRS = share platform substrate

include $(PROJECT)/platform/platform.mk
include $(PROJECT)/share/share.mk

all : $(LIBRARY)
