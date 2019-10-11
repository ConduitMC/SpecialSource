/**
 * Copyright (c) 2012, md_5. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * The name of the author may not be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.md_5.specialsource;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.objectweb.asm.Opcodes.*;

// https://github.com/ModCoderPack/MCInjector/blob/master/src/main/java/de/oceanlabs/mcp/mcinjector/MCInjectorImpl.java
public class ParameterAnnotationFixer extends ClassVisitor {
    private static final Logger LOGGER = Logger.getLogger("MCInjector");

    ParameterAnnotationFixer(ClassVisitor cn) {
        super(Opcodes.ASM6, cn);
        // Extra version check, since these were added in ASM 6.1 and there
        // isn't a constant for it
        try {
            MethodNode.class.getField("visibleAnnotableParameterCount");
            MethodNode.class.getField("invisibleAnnotableParameterCount");
        } catch (Exception ex) {
            throw new IllegalArgumentException("AnnotableParameterCount fields are not present -- wrong ASM version?", ex);
        }
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
        ClassNode cls = getClassNode(cv);
        Type[] syntheticParams = getExpectedSyntheticParams(cls);
        if (syntheticParams != null) {
            for (MethodNode mn : cls.methods) {
                if (mn.name.equals("<init>")) {
                    processConstructor(cls, mn, syntheticParams);
                }
            }
        }
    }

    private static Field field_cv;
    private static ClassNode getClassNode(ClassVisitor cv) {
        try {
            if (field_cv == null) {
                field_cv = ClassVisitor.class.getDeclaredField("cv");
                field_cv.setAccessible(true);
            }
            ClassVisitor tmp = cv;
            while (!(tmp instanceof ClassNode) && tmp != null)
                tmp = (ClassVisitor)field_cv.get(tmp);
            return (ClassNode)tmp;
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks if the given class might have synthetic parameters in the
     * constructor. There are two cases where this might happen:
     * <ol>
     * <li>If the given class is an inner class, the first parameter is the
     * instance of the outer class.</li>
     * <li>If the given class is an enum, the first parameter is the enum
     * constant name and the second parameter is its ordinal.</li>
     * </ol>
     *
     * @return An array of types for synthetic parameters if the class can have
     *         synthetic parameters, otherwise null.
     */
    private Type[] getExpectedSyntheticParams(ClassNode cls) {
        // Check for enum
        // http://hg.openjdk.java.net/jdk8/jdk8/langtools/file/1ff9d5118aae/src/share/classes/com/sun/tools/javac/comp/Lower.java#l2866
        if ((cls.access & ACC_ENUM) != 0) {
            if (SpecialSource.verbose()) LOGGER.fine("  Considering " + cls.name + " for extra parameter annotations as it is an enum");
            return new Type[] { Type.getObjectType("java/lang/String"), Type.INT_TYPE };
        }
        // Check for inner class
        InnerClassNode info = null;
        for (InnerClassNode node : cls.innerClasses) { // note: cls.innerClasses is never null
            if (node.name.equals(cls.name)) {
                info = node;
                break;
            }
        }
        // http://hg.openjdk.java.net/jdk8/jdk8/langtools/file/1ff9d5118aae/src/share/classes/com/sun/tools/javac/code/Symbol.java#l398
        if (info == null) {
            if (SpecialSource.verbose()) LOGGER.fine("  Not considering " + cls.name + " for extra parameter annotations as it is not an inner class");
            return null; // It's not an inner class
        }
        if ((info.access & (ACC_STATIC | ACC_INTERFACE)) != 0) {
            if (SpecialSource.verbose()) LOGGER.fine("  Not considering " + cls.name + " for extra parameter annotations as is an interface or static");
            return null; // It's static or can't have a constructor
        }
        // http://hg.openjdk.java.net/jdk8/jdk8/langtools/file/1ff9d5118aae/src/share/classes/com/sun/tools/javac/jvm/ClassReader.java#l2011
        if (info.innerName == null) {
            if (SpecialSource.verbose()) LOGGER.fine("  Not considering " + cls.name + " for extra parameter annotations as it is annonymous");
            return null; // It's an anonymous class
        }
        if (SpecialSource.verbose()) LOGGER.fine("  Considering " + cls.name + " for extra parameter annotations as it is an inner class of " + info.outerName);
        return new Type[] { Type.getObjectType(info.outerName) };
    }

    /**
     * Removes the parameter annotations for the given synthetic parameters,
     * if there are parameter annotations and the synthetic parameters exist.
     */
    private void processConstructor(ClassNode cls, MethodNode mn, Type[] syntheticParams) {
        String methodInfo = mn.name + mn.desc + " in " + cls.name;
        Type[] params = Type.getArgumentTypes(mn.desc);

        if (beginsWith(params, syntheticParams)) {
            mn.visibleParameterAnnotations = process(methodInfo, "RuntimeVisibleParameterAnnotations", params.length, syntheticParams.length, mn.visibleParameterAnnotations);
            mn.invisibleParameterAnnotations = process(methodInfo, "RuntimeInvisibleParameterAnnotations", params.length, syntheticParams.length, mn.invisibleParameterAnnotations);
            // ASM uses this value, not the length of the array
            // Note that this was added in ASM 6.1
            if (mn.visibleParameterAnnotations != null) {
                mn.visibleAnnotableParameterCount = mn.visibleParameterAnnotations.length;
            }
            if (mn.invisibleParameterAnnotations != null) {
                mn.invisibleAnnotableParameterCount = mn.invisibleParameterAnnotations.length;
            }
        } else {
            if (SpecialSource.verbose()) LOGGER.warning("Unexpected lack of synthetic args to the constructor: expected " + Arrays.toString(syntheticParams) + " at the start of " + methodInfo);
        }
    }

    private boolean beginsWith(Type[] values, Type[] prefix) {
        if (values.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (!values[i].equals(prefix[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes annotation nodes corresponding to synthetic parameters, after
     * the existence of synthetic parameters has already been checked.
     *
     * @param methodInfo
     *            A description of the method, for logging
     * @param attributeName
     *            The name of the attribute, for logging
     * @param numParams
     *            The number of parameters in the method
     * @param numSynthetic
     *            The number of synthetic parameters (should not be 0)
     * @param annotations
     *            The current array of annotation nodes, may be null
     * @return The new array of annotation nodes, may be null
     */
    private List<AnnotationNode>[] process(String methodInfo, String attributeName, int numParams, int numSynthetic, List<AnnotationNode>[] annotations) {
        if (annotations == null) {
            if (SpecialSource.verbose()) LOGGER.finer("    " + methodInfo + " does not have a " + attributeName + " attribute");
            return null;
        }
        int numAnnotations = annotations.length;
        if (numParams == numAnnotations) {
            if (SpecialSource.verbose()) LOGGER.info("Found extra " + attributeName + " entries in " + methodInfo + ": removing " + numSynthetic);
            return Arrays.copyOfRange(annotations, numSynthetic, numAnnotations);
        }
        else if (numParams == numAnnotations - numSynthetic) {
            if (SpecialSource.verbose()) LOGGER.info("Number of " + attributeName + " entries in " + methodInfo + " is already as we want");
            return annotations;
        } else {
            if (SpecialSource.verbose()) LOGGER.warning("Unexpected number of " + attributeName + " entries in " + methodInfo + ": " + numAnnotations);
            return annotations;
        }
    }
}
