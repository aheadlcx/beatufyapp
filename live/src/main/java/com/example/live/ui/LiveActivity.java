package com.example.live.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import com.example.live.engine.LiveEngine;
import com.example.live.engine.PeerFactoryProvider;

/**
 * 直播界面：同一界面覆盖"开播"与"观看"两种角色。
 *
 * <p>启动状态显示设置面板（服务器地址 + 房间号 + 开播/观看按钮）；
 * 开始后：全屏渲染 + 底部状态栏（C++ 统计快照 / 观众数）+ 弹幕列表 + 输入框。
 * 权限（相机/麦克风）在开播前动态申请。</p>
 */
public class LiveActivity extends AppCompatActivity implements LiveEngine.Listener {

    private static final int REQ_PERMS = 2;

    private SurfaceViewRenderer renderer;
    private LinearLayout setupPanel;
    private EditText urlEdit;
    private EditText roomEdit;
    private TextView statusView;
    private TextView statsView;
    private TextView chatView;
    private EditText chatEdit;
    private View controlBar;

    private LiveEngine engine;
    private final StringBuilder chatLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        renderer = new SurfaceViewRenderer(this);
        renderer.init(PeerFactoryProvider.eglContext(this), null);
        renderer.setZOrderMediaOverlay(false);

        setContentView(buildUi());
        statusView.setText("待开始。默认服务器为本机/局域网信令，公网部署见 server/README.md");
    }

    // ---- UI 构建（纯代码，避免 live 模块引入过多资源依赖）----

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(renderer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusView.setTextSize(13);
        root.addView(statusView);

        statsView = new TextView(this);
        statsView.setPadding(dp(12), dp(2), dp(12), dp(2));
        statsView.setTextSize(12);
        statsView.setTextColor(0xFF2ECC71);
        statsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(statsView);

        chatView = new TextView(this);
        chatView.setPadding(dp(12), dp(2), dp(12), dp(2));
        chatView.setTextSize(12);
        chatView.setMaxLines(3);
        chatView.setScrollContainer(true);
        root.addView(chatView);

        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setPadding(dp(12), dp(6), dp(12), dp(6));
        urlEdit = new EditText(this);
        urlEdit.setHint("信令 ws://地址:端口");
        urlEdit.setText("ws://10.0.2.2:8080");
        urlEdit.setSingleLine();
        setupPanel.addView(urlEdit);
        roomEdit = new EditText(this);
        roomEdit.setHint("房间号");
        roomEdit.setText("room1");
        roomEdit.setSingleLine();
        setupPanel.addView(roomEdit);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button broadcasterBtn = new Button(this);
        broadcasterBtn.setText("开播(主播)");
        broadcasterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ensurePerms()) {
                    start(true);
                }
            }
        });
        Button viewerBtn = new Button(this);
        viewerBtn.setText("观看(观众)");
        viewerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start(false);
            }
        });
        buttons.addView(broadcasterBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(viewerBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        setupPanel.addView(buttons);
        root.addView(setupPanel);

        LinearLayout chatBar = new LinearLayout(this);
        chatBar.setOrientation(LinearLayout.HORIZONTAL);
        chatEdit = new EditText(this);
        chatEdit.setHint("弹幕…");
        chatEdit.setSingleLine();
        chatBar.addView(chatEdit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = chatEdit.getText().toString().trim();
                if (!t.isEmpty() && engine != null) {
                    engine.sendChat(t);
                    chatEdit.setText("");
                }
            }
        });
        chatBar.addView(sendBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chatBar);

        controlBar = chatBar;
        return root;
    }

    private void start(boolean asBroadcaster) {
        engine = new LiveEngine(this, this, null);
        if (asBroadcaster) {
            engine.startAsBroadcaster(urlEdit.getText().toString().trim(),
                    roomEdit.getText().toString().trim(), renderer);
            statusView.setText("开播中…");
        } else {
            engine.startAsViewer(urlEdit.getText().toString().trim(),
                    roomEdit.getText().toString().trim(), renderer);
            statusView.setText("连接房间…");
        }
        setupPanel.setVisibility(View.GONE);
    }

    private boolean ensurePerms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            start(true);
        } else {
            Toast.makeText(this, "需要相机与麦克风权限", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- LiveEngine.Listener ----

    @Override
    public void onStatus(String message) {
        statusView.setText(message);
    }

    @Override
    public void onStats(String json) {
        statsView.setText(json);
    }

    @Override
    public void onChat(String from, String text) {
        chatLog.append('[').append(from).append("] ").append(text).append('\n');
        chatView.setText(chatLog.toString());
    }

    @Override
    public void onViewerCount(int count) {
        statusView.setText("在线观众: " + count);
    }

    @Override
    public void onRemoteVideo(VideoTrack track) {
        // LiveEngine 已把 track addSink 到 renderer
        statusView.setText("已收到主播视频");
    }

    @Override
    public void onEnded(String reason) {
        chatLog.append("— 会话结束: ").append(reason).append(" —\n");
        chatView.setText(chatLog.toString());
        setupPanel.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        renderer.release();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
