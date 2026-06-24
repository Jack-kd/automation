package com.jackkd.automation.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.jackkd.automation.R;
import com.jackkd.automation.core.AutomationHolder;
import com.jackkd.automation.core.ImageMatcher;
import com.jackkd.automation.core.SoundPlayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 核心前台服务：
 *   - 悬浮窗状态指示器（可拖拽，点击暂停/恢复，长按停止）
 *   - 图片模式：屏幕截图 + 模板匹配
 *   - 文本模式：交给 AccessibilityService 处理
 */
public class FloatWidgetService extends Service {

    private static final String  CH_ID       = "automation_monitor";
    private static final int     NOTIF_ID    = 1001;
    private static final long    CAPTURE_MS  = 500;   // 截图间隔
    private static final String  TARGET_DIR  = "/storage/emulated/0/自动化目标";

    // UI
    private WindowManager wm;
    private View          widget;
    private TextView      modeLabel;
    private View          dot;

    // 屏幕截图
    private MediaProjection projection;
    private VirtualDisplay   vDisplay;
    private ImageReader      reader;
    private int scrW, scrH, scrDpi;

    // 线程
    private HandlerThread workerThread;
    private Handler       worker;
    private Handler       mainHandler;

    // 状态
    private Bitmap  targetBmp;
    private String  targetTxt;
    private volatile boolean active = false;

    // 长按检测
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private boolean moved = false;

    private android.os.FileObserver fileObs;

    /* ===================== lifecycle ===================== */

    @Override
    public void onCreate() {
        super.onCreate();
        wm           = (WindowManager) getSystemService(WINDOW_SERVICE);
        workerThread = new HandlerThread("auto_worker");
        workerThread.start();
        worker       = new Handler(workerThread.getLooper());
        mainHandler  = new Handler(Looper.getMainLooper());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        scrW   = dm.widthPixels;
        scrH   = dm.heightPixels;
        scrDpi = dm.densityDpi;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }

        // ① 立即前台
        createChannel();
        Notification n = buildNotif("初始化中…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        // ② MediaProjection
        int code = intent.getIntExtra("resultCode", 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra("data", Intent.class);
        } else {
            data = intent.getParcelableExtra("data");
        }
        if (data != null) {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection != null && Build.VERSION.SDK_INT >= 34) {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        mainHandler.post(() -> updateDot("断开", Color.RED));
                    }
                }, worker);
            }
        }

        // ③ 悬浮窗
        showWidget();

        // ④ 加载目标文件（worker 线程），然后自动开始监控
        worker.post(() -> {
            loadTargets();
            watchDir();
            if (AutomationHolder.getInstance().getMode() != AutomationHolder.Mode.IDLE) {
                active = true;
                AutomationHolder.getInstance().setMonitoring(true);
                mainHandler.post(this::beginMonitor);
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() { cleanup(); super.onDestroy(); }

    /* ===================== 悬浮窗 ===================== */

    private void showWidget() {
        widget = buildWidgetView();

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 20;
        p.y = statusBarHeight() + dp(12);

        setupTouch(widget, p);
        wm.addView(widget, p);
    }

    private View buildWidgetView() {
        int size = dp(56);
        FrameLayout c = new FrameLayout(this);

        View bg = new View(this);
        bg.setBackgroundColor(Color.argb(220, 33, 150, 243));
        c.addView(bg, new FrameLayout.LayoutParams(size, size));

        dot = new View(this);
        int ds = dp(12);
        dot.setBackgroundColor(Color.YELLOW);
        FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(ds, ds);
        dp2.gravity = Gravity.TOP | Gravity.END;
        dp2.topMargin = dp(6); dp2.rightMargin = dp(6);
        c.addView(dot, dp2);

        modeLabel = new TextView(this);
        modeLabel.setText("…");
        modeLabel.setTextColor(Color.WHITE);
        modeLabel.setTextSize(11);
        modeLabel.setGravity(Gravity.CENTER);
        c.addView(modeLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        return c;
    }

    private void setupTouch(View v, WindowManager.LayoutParams p) {
        final float[] rawX = new float[1], rawY = new float[1];
        final int[] startX = new int[1], startY = new int[1];

        v.setOnTouchListener((view, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    rawX[0] = ev.getRawX(); rawY[0] = ev.getRawY();
                    startX[0] = p.x;       startY[0] = p.y;
                    moved = false;
                    longPressHandler.postDelayed(longPressRunnable, 800);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - rawX[0];
                    float dy = ev.getRawY() - rawY[0];
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true;
                        longPressHandler.removeCallbacks(longPressRunnable);
                    }
                    p.x = startX[0] + (int) dx;
                    p.y = startY[0] + (int) dy;
                    try { wm.updateViewLayout(view, p); } catch (Exception ignored) {}
                    return true;

                case MotionEvent.ACTION_UP:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    if (!moved) toggleMonitor();
                    return true;
            }
            return false;
        });
    }

    private final Runnable longPressRunnable = () -> {
        Toast("监控已停止");
        cleanup();
        stopSelf();
    };

    /* ===================== 监控控制 ===================== */

    private void toggleMonitor() {
        active = !active;
        AutomationHolder.getInstance().setMonitoring(active);
        if (active) beginMonitor(); else pauseMonitor();
    }

    private void beginMonitor() {
        AutomationHolder.Mode m = AutomationHolder.getInstance().getMode();
        String label = m == AutomationHolder.Mode.IMAGE ? "IMG" : "TXT";
        updateDot(label, Color.argb(255, 76, 175, 80));
        dot.setBackgroundColor(Color.GREEN);

        if (m == AutomationHolder.Mode.IMAGE) {
            startCapture();
        }
        // TEXT 模式由 AccessibilityService 处理
    }

    private void pauseMonitor() {
        stopCapture();
        updateDot("暂停", Color.GRAY);
        dot.setBackgroundColor(Color.YELLOW);
    }

    /* ===================== 屏幕截图 & 匹配 ===================== */

    private void startCapture() {
        if (projection == null || reader != null) return;

        reader = ImageReader.newInstance(scrW, scrH, PixelFormat.RGBA_8888, 2);
        vDisplay = projection.createVirtualDisplay(
                "AutoCapture", scrW, scrH, scrDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);

        worker.post(captureLoop);
    }

    private final Runnable captureLoop = new Runnable() {
        @Override public void run() {
            if (!active || reader == null) return;

            Bitmap tgt = targetBmp;
            if (tgt != null) {
                Bitmap screen = grab();
                if (screen != null) {
                    float score = ImageMatcher.match(screen, tgt);
                    int sens = AutomationHolder.getInstance().getSensitivity();
                    // 灵敏度 1→threshold≈0.995  100→threshold≈0.5
                    float threshold = 1.0f - (sens / 100.0f) * 0.5f;
                    if (score >= threshold) {
                        onMatch("图片 " + Math.round(score * 100) + "%");
                    }
                    screen.recycle();
                }
            }
            worker.postDelayed(this, CAPTURE_MS);
        }
    };

    private Bitmap grab() {
        if (reader == null) return null;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane pl = img.getPlanes()[0];
            int ps = pl.getPixelStride(), rs = pl.getRowStride();
            int pad = rs - ps * w;
            Bitmap bmp = Bitmap.createBitmap(w + pad / ps, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(pl.getBuffer());
            if (bmp.getWidth() != w || bmp.getHeight() != h) {
                Bitmap c = Bitmap.createBitmap(bmp, 0, 0, w, h);
                bmp.recycle();
                return c;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    private void stopCapture() {
        worker.removeCallbacks(captureLoop);
        if (vDisplay != null) { vDisplay.release(); vDisplay = null; }
        if (reader != null)   { reader.close();   reader   = null; }
    }

    /* ===================== 匹配回调 ===================== */

    private void onMatch(String reason) {
        boolean played = SoundPlayer.play(this);
        if (!played) return;

        mainHandler.post(() -> {
            updateDot("匹配!", Color.RED);
            dot.setBackgroundColor(Color.RED);
            mainHandler.postDelayed(() -> {
                if (active) {
                    dot.setBackgroundColor(Color.GREEN);
                    String lbl = AutomationHolder.getInstance().getMode()
                            == AutomationHolder.Mode.IMAGE ? "IMG" : "TXT";
                    updateDot(lbl, Color.argb(255, 76, 175, 80));
                }
            }, 2000);
        });
    }

    /* ===================== 文件监控 ===================== */

    private void loadTargets() {
        File dir = new File(TARGET_DIR);
        if (!dir.exists()) dir.mkdirs();

        File png = null, txt = null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".png") && png == null) png = f;
                else if (n.endsWith(".txt") && txt == null) txt = f;
            }
        }

        if (png != null) {
            targetBmp = loadBitmapSafe(png);
            AutomationHolder.getInstance().setTargetBitmap(targetBmp);
            setMode(AutomationHolder.Mode.IMAGE);
        } else if (txt != null) {
            targetTxt = readFile(txt);
            AutomationHolder.getInstance().setTargetText(targetTxt);
            setMode(AutomationHolder.Mode.TEXT);
        } else {
            setMode(AutomationHolder.Mode.IDLE);
        }
    }

    private Bitmap loadBitmapSafe(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sc = 1, max = 800;
        while (o.outWidth / sc > max || o.outHeight / sc > max) sc *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sc;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private String readFile(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private void watchDir() {
        File dir = new File(TARGET_DIR);
        if (!dir.exists()) dir.mkdirs();

        fileObs = new android.os.FileObserver(dir.getAbsolutePath(),
                android.os.FileObserver.CREATE | android.os.FileObserver.DELETE
                        | android.os.FileObserver.MODIFY
                        | android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.MOVED_FROM) {
            @Override public void onEvent(int event, String path) {
                if (path == null) return;
                worker.removeCallbacks(reloadRunnable);
                worker.postDelayed(reloadRunnable, 600);
            }
        };
        fileObs.startWatching();
    }

    private final Runnable reloadRunnable = this::reload;

    private void reload() {
        boolean wasActive = active;
        stopCapture();
        if (targetBmp != null) { targetBmp.recycle(); targetBmp = null; }
        targetTxt = null;

        loadTargets();

        AutomationHolder.Mode m = AutomationHolder.getInstance().getMode();
        if (wasActive && m != AutomationHolder.Mode.IDLE) {
            active = true;
            AutomationHolder.getInstance().setMonitoring(true);
            mainHandler.post(this::beginMonitor);
        } else {
            active = false;
            AutomationHolder.getInstance().setMonitoring(false);
            mainHandler.post(() -> {
                if (m == AutomationHolder.Mode.IDLE) updateDot("--", Color.GRAY);
            });
        }
    }

    /* ===================== 工具方法 ===================== */

    private void setMode(AutomationHolder.Mode m) {
        AutomationHolder.getInstance().setMode(m);
        mainHandler.post(() -> {
            if (modeLabel == null) return;
            switch (m) {
                case IMAGE: modeLabel.setText("IMG"); break;
                case TEXT:  modeLabel.setText("TXT"); break;
                default:    modeLabel.setText("--");  break;
            }
        });
    }

    private void updateDot(String txt, int c) {
        if (modeLabel != null) modeLabel.setText(txt);
        if (dot != null)       dot.setBackgroundColor(c);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "自动化监控", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("自动化工具运行状态");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("自动化工具")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }

    private void cleanup() {
        active = false;
        AutomationHolder.getInstance().setMonitoring(false);
        stopCapture();
        if (fileObs != null) { fileObs.stopWatching(); fileObs = null; }
        if (widget != null) try { wm.removeView(widget); } catch (Exception ignored) {}
        if (targetBmp != null) { targetBmp.recycle(); targetBmp = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (workerThread != null) workerThread.quitSafely();
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private void Toast(String msg) {
        mainHandler.post(() -> android.widget.Toast.makeText(this, msg,
                android.widget.Toast.LENGTH_SHORT).show());
    }
}
