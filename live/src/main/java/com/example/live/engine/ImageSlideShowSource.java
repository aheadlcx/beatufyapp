package com.example.live.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;

import java.nio.ByteBuffer;

/**
 * 图片轮播源（无摄像头场景的推流信号源）。
 *
 * <p>程序生成若干张"图片"（纯色底 + 大字），定时逐张绘制到 Bitmap，
 * 每次切换把 Bitmap 转成 I420（YUV420P，libwebrtc 的原生帧格式）后经
 * CapturerObserver 送入 VideoSource——与摄像头采集等价，编码推流链路完全复用。</p>
 *
 * <p>轮播频率 2s/张；分辨率 540x960（9:16 竖屏，转码开销小）。
 * 只在切换时产生一帧，静止期间编码器自动输出低码率，节省带宽。</p>
 */
public class ImageSlideShowSource {

    private static final int WIDTH = 540;
    private static final int HEIGHT = 960;
    private static final long SLIDE_INTERVAL_MS = 2000;

    public interface Callback {
        void onSlideChanged(int index, int total);
    }

    private final VideoSource videoSource;
    private final CapturerObserver observer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Bitmap[] slides;
    private final Callback callback;
    private final int slideCount;
    private int index = 0;
    private boolean running = false;
    private long frameTimestampNs = 0;

    public ImageSlideShowSource(Context context, int slideCount, Callback callback) {
        this.slideCount = Math.max(1, slideCount);
        this.callback = callback;
        PeerFactoryProvider.factory(context);
        videoSource = PeerFactoryProvider.factory(context).createVideoSource(false);
        observer = videoSource.getCapturerObserver();
        slides = new Bitmap[this.slideCount];
        for (int i = 0; i < this.slideCount; i++) {
            slides[i] = renderSlide(i);
        }
    }

    public VideoSource videoSource() {
        return videoSource;
    }

    public void start() {
        if (running) return;
        running = true;
        pushSlide();
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void pushSlide() {
        if (!running) return;
        pushBitmap(slides[index]);
        if (callback != null) callback.onSlideChanged(index, slideCount);
        index = (index + 1) % slideCount;
        handler.postDelayed(this::pushSlide, SLIDE_INTERVAL_MS);
    }

    private void pushBitmap(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        JavaI420Buffer buffer = JavaI420Buffer.allocate(w, h);
        ByteBuffer y = buffer.getDataY();
        ByteBuffer u = buffer.getDataU();
        ByteBuffer v = buffer.getDataV();
        bitmapToI420(bmp, w, h, y, u, v);
        frameTimestampNs += 1_000_000_000L / 30; // 伪 30fps 时间戳，实际 2s/帧
        observer.onFrameCaptured(new VideoFrame(buffer, 0, frameTimestampNs));
    }

    /** 生成第 i 张轮播图：纯色底 + 序号大字 + 时间戳小字。 */
    private static Bitmap renderSlide(int i) {
        int[] colors = {Color.rgb(26, 115, 232), Color.rgb(232, 113, 10), Color.rgb(24, 128, 56)};
        Bitmap bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(colors[i % colors.length]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(120);
        canvas.drawText("图片 " + (i + 1), WIDTH / 2f, HEIGHT / 2f, paint);
        paint.setTextSize(48);
        canvas.drawText(android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                .toString(), WIDTH / 2f, HEIGHT / 2f + 160, paint);
        return bmp;
    }

    /** ARGB → I420（YUV420 平面格式，2x2 下采样的 U/V）。2 秒一次，纯 Java 足够。 */
    private static void bitmapToI420(Bitmap bmp, int w, int h,
                                     ByteBuffer y, ByteBuffer u, ByteBuffer v) {
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int yIdx = 0;
        int uIdx = 0;
        int vIdx = 0;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int p = px[row * w + col];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                y.put(yIdx++, (byte) clampY((77 * r + 150 * g + 29 * b) >> 8));
                if ((row & 1) == 0 && (col & 1) == 0) {
                    u.put(uIdx++, (byte) clampY(((-43 * r - 85 * g + 128 * b) >> 8) + 128));
                    v.put(vIdx++, (byte) clampY(((128 * r - 107 * g - 21 * b) >> 8) + 128));
                }
            }
        }
        y.rewind();
        u.rewind();
        v.rewind();
    }

    private static int clampY(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
