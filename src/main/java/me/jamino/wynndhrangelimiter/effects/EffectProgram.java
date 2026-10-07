package me.jamino.wynndhrangelimiter.effects;

import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A linked effect shader. Uniform setters act on the bound program; unknown names are ignored. */
public final class EffectProgram {
    private static final String SHADERS = "/assets/wynnvista/shaders/effects/";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^#include \"([\\w.]+)\"\\s*$");

    private final int id;
    private final Map<String, Integer> uniforms = new HashMap<>();

    private EffectProgram(int id) {
        this.id = id;
    }

    static EffectProgram link(String fragmentName) {
        int vertex = compile(GL20C.GL_VERTEX_SHADER, "fullscreen.vsh");
        int fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentName);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException("Effect program " + fragmentName + " did not link: " + log);
        }
        return new EffectProgram(program);
    }

    void use() {
        GL20C.glUseProgram(id);
    }

    public void set(String name, int value) {
        GL20C.glUniform1i(location(name), value);
    }

    public void set(String name, float value) {
        GL20C.glUniform1f(location(name), value);
    }

    public void set(String name, float x, float y) {
        GL20C.glUniform2f(location(name), x, y);
    }

    public void set(String name, float x, float y, float z) {
        GL20C.glUniform3f(location(name), x, y, z);
    }

    public void set(String name, Matrix4fc matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(location(name), false, matrix.get(stack.mallocFloat(16)));
        }
    }

    private int location(String name) {
        return uniforms.computeIfAbsent(name, key -> GL20C.glGetUniformLocation(id, key));
    }

    private static int compile(int type, String name) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source(name));
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Effect shader " + name + " did not compile: " + log);
        }
        return shader;
    }

    /** Reads a shader, replacing each {@code #include "file"} line with that file's text. */
    static String source(String name) {
        String text;
        try (InputStream in = EffectProgram.class.getResourceAsStream(SHADERS + name)) {
            if (in == null) throw new IllegalStateException("Missing effect shader: " + name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable effect shader: " + name, e);
        }
        Matcher include = INCLUDE.matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (include.find()) {
            include.appendReplacement(expanded, Matcher.quoteReplacement(source(include.group(1))));
        }
        return include.appendTail(expanded).toString();
    }
}
