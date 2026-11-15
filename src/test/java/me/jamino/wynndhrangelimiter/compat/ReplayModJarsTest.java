package me.jamino.wynndhrangelimiter.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.jamino.wynndhrangelimiter.compat.flashback.FlashbackSupport;
import me.jamino.wynndhrangelimiter.compat.replaymod.ReplayModSupport;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the released jars of ReplayMod and Flashback for every member WynnVista reaches by name
 * ({@code ReplayModSupport.REFLECTED}, {@code FlashbackSupport.REFLECTED}). When one of the mods renames,
 * removes, hides or retypes such a member in an update, the game only logs a warning and replays lose their
 * masking and effects; here it is a failed test that names the member.
 *
 * <p>The jars come from the build ({@code wynnvista.replayModJars}: those of {@code gradle.properties}, or
 * the folder given with {@code -PreplayModJars}). They are read as class files and never loaded: a released
 * jar refers to Minecraft by names this build does not have, so its classes could not be linked here.
 */
class ReplayModJarsTest {
    private static final String JARS = "wynnvista.replayModJars";

    @Test
    void replayModHasWhatReplayModSupportReads() throws IOException {
        check("replaymod", ReplayModSupport.REFLECTED);
    }

    @Test
    void flashbackHasWhatFlashbackSupportReads() throws IOException {
        check("flashback", FlashbackSupport.REFLECTED);
    }

    /** Without this, a build that passes no jars would skip both tests above and look fine. */
    @Test
    void theBuildSuppliesBothMods() throws IOException {
        assumeTrue(System.getProperty(JARS) != null, "not run by Gradle: no " + JARS);
        // A folder given by hand may hold only the mod that is being tried.
        assumeTrue(!Boolean.getBoolean("wynnvista.replayModJars.byHand"), "jars given with -PreplayModJars");
        Set<String> ids = new HashSet<>();
        for (Path jar : jars()) ids.add(modId(jar));
        assertTrue(ids.contains("replaymod") && ids.contains("flashback"), "mods found: " + ids);
    }

    private static void check(String modId, List<Reflected> members) throws IOException {
        Path jar = null;
        for (Path candidate : jars()) {
            if (modId.equals(modId(candidate))) jar = candidate;
        }
        assumeTrue(jar != null, "no jar of '" + modId + "' in " + JARS);
        Map<String, ClassNode> classes = new HashMap<>();
        try (InputStream in = Files.newInputStream(jar)) {
            readClasses(in, classes);
        }
        List<String> problems = new ArrayList<>();
        for (Reflected member : members) {
            String problem = problem(member, classes);
            if (problem != null) problems.add(member + ": " + problem);
        }
        if (!problems.isEmpty()) {
            fail(jar.getFileName() + " no longer has what WynnVista reads from it:\n  "
                    + String.join("\n  ", problems));
        }
    }

    /** What stops the lookup the support class makes for this member, or null when it would succeed. */
    private static String problem(Reflected member, Map<String, ClassNode> classes) {
        ClassNode owner = classes.get(member.owner().replace('.', '/'));
        if (owner == null) return "class " + member.owner() + " is gone";
        boolean declaredOnly = member.kind() == Reflected.Kind.DECLARED_FIELD;
        boolean leftTheJar = false;
        Deque<ClassNode> toSearch = new ArrayDeque<>(List.of(owner));
        Set<String> searched = new HashSet<>();
        while (!toSearch.isEmpty()) {
            ClassNode c = toSearch.pop();
            if (!searched.add(c.name)) continue;
            int access = -1;
            String descriptor = null;
            if (member.kind() == Reflected.Kind.METHOD) {
                for (MethodNode method : c.methods) {
                    if (method.name.equals(member.name()) && method.desc.startsWith("()")
                            && (method.access & Opcodes.ACC_SYNTHETIC) == 0) {
                        access = method.access;
                        descriptor = Type.getReturnType(method.desc).getClassName();
                    }
                }
            } else {
                for (FieldNode field : c.fields) {
                    if (field.name.equals(member.name())) {
                        access = field.access;
                        descriptor = Type.getType(field.desc).getClassName();
                    }
                }
            }
            if (access != -1) {
                if (!declaredOnly) {
                    if ((access & Opcodes.ACC_PUBLIC) == 0) return "is no longer public";
                    if ((c.access & Opcodes.ACC_PUBLIC) == 0) return "its class " + className(c) + " is not public";
                }
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                if (isStatic != member.isStatic()) return isStatic ? "is now static" : "is no longer static";
                if (member.type() != null && !member.type().equals(descriptor)) {
                    return "is now of type " + descriptor + ", not " + member.type();
                }
                return null;
            }
            if (declaredOnly) break;
            List<String> supers = new ArrayList<>(c.interfaces);
            if (c.superName != null) supers.add(c.superName);
            for (String name : supers) {
                ClassNode parent = classes.get(name);
                if (parent != null) toSearch.push(parent);
                else if (!name.equals("java/lang/Object")) leftTheJar = true;
            }
        }
        return "not found in " + className(owner) + (declaredOnly ? "" : " or what it extends within the jar")
                + (leftTheJar ? " (it also extends classes outside the jar, which are not looked into)" : "");
    }

    private static String className(ClassNode c) {
        return c.name.replace('/', '.');
    }

    private static List<Path> jars() {
        String property = System.getProperty(JARS);
        assumeTrue(property != null, "not run by Gradle: no " + JARS);
        List<Path> jars = new ArrayList<>();
        for (String path : property.split(File.pathSeparator)) {
            if (!path.isBlank()) jars.add(Path.of(path));
        }
        return jars;
    }

    /** The id in the jar's {@code fabric.mod.json}; empty when it has none. */
    private static String modId(Path jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.getName().equals("fabric.mod.json")) continue;
                JsonObject json = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                return json.has("id") ? json.get("id").getAsString() : "";
            }
        }
        return "";
    }

    /** Every class of a jar and of the jars bundled inside it, without their code. */
    private static void readClasses(InputStream jar, Map<String, ClassNode> classes) throws IOException {
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            String name = entry.getName();
            if (name.endsWith(".class")) {
                ClassNode node = new ClassNode();
                new ClassReader(zip.readAllBytes()).accept(node,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.putIfAbsent(node.name, node);
            } else if (name.endsWith(".jar")) {
                readClasses(new ByteArrayInputStream(zip.readAllBytes()), classes);
            }
        }
    }
}
