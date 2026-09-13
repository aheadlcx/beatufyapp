package com.example.beautycamera;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.FloatBuffer;

/**
 * 全屏矩形（两个三角形）+ 统一的一次 pass 绘制入口。
 *
 * <p>GPU 渲染一张图到另一个纹理，固定要做：绑定顶点数据 → 使用 program →
 * 设置公共 uniform → 绑定输入纹理到纹理单元 0..n → 绑定目标帧缓冲 → 设置视口 →
 * 绘制。本类把这些样板收拢：调用方只需给出 program、目标 FBO、
 * 纹理名/纹理对，以及一个 {@link Extra} 回调设置本 pass 特有的 uniform。</p>
 *
 * <p>默认 uniform：uSTMatrix=单位阵、uCrop=(1,1)、uMirror=0（Extra 可覆盖）。</p>
 */
public class QuadDrawer {

    /** Per-pass uniform hook, called right before drawing. */
    public interface Extra {
        void onProgram(GlProgram p);
    }

    private final FloatBuffer pos;
    private final FloatBuffer uv;
    private final float[] identity = new float[16];

    public QuadDrawer() {
        pos = GlProgram.floatBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        uv = GlProgram.floatBuffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
        Matrix.setIdentityM(identity, 0);
    }

    /** Draw into an FBO. */
    public void draw(GlProgram p, Fbo dst, String[] texNames, int[] texIds, Extra extra) {
        draw(p, dst.fbo, dst.w, dst.h, texNames, texIds, extra);
    }

    /** Draw into the default framebuffer (screen). */
    public void draw(GlProgram p, int dstW, int dstH, String[] texNames, int[] texIds, Extra extra) {
        draw(p, 0, dstW, dstH, texNames, texIds, extra);
    }

    private void draw(GlProgram p, int fbo, int w, int h,
                      String[] texNames, int[] texIds, Extra extra) {
        p.use();
        bindMesh(p);
        p.setMat4("uSTMatrix", identity);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        if (extra != null) extra.onProgram(p);
        for (int i = 0; i < texNames.length; i++) {
            p.setInt(texNames[i], i);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[i]);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void bindMesh(GlProgram p) {
        int aPos = GLES20.glGetAttribLocation(p.handle(), "aPosition");
        int aUv = GLES20.glGetAttribLocation(p.handle(), "aTexCoord");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv);
    }
}
