package com.example.beautycamera;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/** Minimal GLSL program wrapper. */
public class GlProgram {

    private int program = 0;
    private final Map<String, Integer> locations = new HashMap<String, Integer>();

    public GlProgram(String vertexSource, String fragmentSource) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
    }

    public void use() {
        GLES20.glUseProgram(program);
    }

    public int handle() {
        return program;
    }

    public int uniform(String name) {
        Integer cached = locations.get(name);
        if (cached == null) {
            cached = GLES20.glGetUniformLocation(program, name);
            locations.put(name, cached);
        }
        return cached;
    }

    public void setFloat(String name, float v) {
        GLES20.glUniform1f(uniform(name), v);
    }

    public void setInt(String name, int v) {
        GLES20.glUniform1i(uniform(name), v);
    }

    public void setVec2(String name, float x, float y) {
        GLES20.glUniform2f(uniform(name), x, y);
    }

    public void setMat4(String name, float[] m) {
        GLES20.glUniformMatrix4fv(uniform(name), 1, false, m, 0);
    }

    public void setVec2Array(String name, float[] data, int count) {
        if (uniform(name) >= 0) GLES20.glUniform2fv(uniform(name), count, data, 0);
    }

    public void setVec3Array(String name, float[] data, int count) {
        if (uniform(name) >= 0) GLES20.glUniform3fv(uniform(name), count, data, 0);
    }

    public void release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
    }

    public static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("compile: " + GLES20.glGetShaderInfoLog(shader) + "\n" + source);
        }
        return shader;
    }

    /** Compile a program whose fragment shader samples an external OES texture. */
    public static GlProgram createExternal(String vertex, String fragmentBody) {
        return new GlProgram(vertex,
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" + fragmentBody);
    }

    public static int genTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return t[0];
    }

    public static int genOesTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return t[0];
    }

    public static FloatBuffer floatBuffer(float[] arr) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(arr.length * 4);
        bb.order(java.nio.ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(arr);
        fb.position(0);
        return fb;
    }
}
