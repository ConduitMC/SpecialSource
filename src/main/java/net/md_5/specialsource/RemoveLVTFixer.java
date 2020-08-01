package net.md_5.specialsource;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;

public class RemoveLVTFixer extends ClassVisitor {

    RemoveLVTFixer(ClassVisitor cn) {
        super(Opcodes.ASM6, cn);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        return new MethodVisitor(api, cv.visitMethod(access, name, desc, signature, exceptions)) {
            @Override
            public void visitEnd() {
                MethodNode mn = getMethodNode(mv);
                if (mn.localVariables != null && mn.localVariables.size() > 0) {
                    mn.localVariables = null;
                }
                super.visitEnd();
            }
        };
    }

    private static Field field_mv;
    private static MethodNode getMethodNode(MethodVisitor cv) {
        try {
            if (field_mv == null) {
                field_mv = MethodVisitor.class.getDeclaredField("mv");
                field_mv.setAccessible(true);
            }
            MethodVisitor tmp = cv;
            while (!(tmp instanceof MethodNode) && tmp != null)
                tmp = (MethodVisitor) field_mv.get(tmp);
            return (MethodNode)tmp;
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            throw new RuntimeException(e);
        }
    }
}


