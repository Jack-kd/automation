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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.jackkd.automation.R;
import com.jackkd.automation.core.AutomationHolder;
import com.jackkd.automation.core.ImageMatcher;
import com.jackkd.automation.core.SoundPlayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class FloatWidgetService extends Service {

    private static final String CH_ID = "automation_monitor";
    private static final int NOTIF_ID = 1001;
    private static final long CAPTURE_MS = 500;
    private static final String TARGET_DIR = "/storage/emulated/0/自动化目标";

    private WindowManager wm;
    private View widget;
    private TextView modeLabel;
    private View statusDot;

    private MediaProjection projection;
    private VirtualDisplay vDisplay;
    private ImageReader reader;
    private int scrW, scrH, scrDpi;

    private HandlerThread workerThread;
    private Handler worker;
    private Handler mainHandler;

    private Bitmap targetBmp;
    private String targetTxt;
    private volatile boolean active = false;

    private android.os.FileObserver fileObs;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        workerThread = new HandlerThread("auto_worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        scrW = dm.widthPixels;
        scrH = dm.heightPixels;
        scrDpi = dm.densityDpi;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        Notification n = buildNotif("初始化中...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }

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
                    @Override
                    public void onStop() {
                        mainHandler.post(() -> updateStatus("断开", Color.RED));
                    }
                }, worker);
            }
        }

        showWidget();

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
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    /* ========================= 悬浮窗 ========================= */

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

        setupDrag(widget, p);
        wm.addView(widget, p);
    }

    private View buildWidgetView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(12), dp(8), dp(12), dp(8));
        container.setBackground(makeRoundBg(Color.argb(230, 33, 33, 33), dp(16)));

        // 状态圆点
        statusDot = new View(this);
        statusDot.setBackgroundColor(Color.YELLOW);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.rightMargin = dp(8);
        container.addView(statusDot, dotLp);

        // 模式标签
        modeLabel = new TextView(this);
        modeLabel.setText("...");
        modeLabel.setTextColor(Color.WHITE);
        modeLabel.setTextSize(14);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.rightMargin = dp(14);
        container.addView(modeLabel, labelLp);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(60, 255, 255, 255));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(18));
        divLp.rightMargin = dp(14);
        container.addView(divider, divLp);

        // "重新检测" 按钮
        TextView btnRedetect = new TextView(this);
        btnRedetect.setText("重新检测");
        btnRedetect.setTextColor(Color.parseColor("#90CAF9"));
        btnRedetect.setTextSize(13);
        btnRedetect.setPadding(dp(4), dp(2), dp(4), dp(2));
        container.addView(btnRedetect);

        // 点击按钮 → 停止音乐 + 重新检测
        btnRedetect.setOnClickListener(v -> {
            SoundPlayer.stop();
            resetAndReload();
        });

        return container;
    }

    private void setupDrag(View v, WindowManager.LayoutParams p) {
        final float[] rawX = new float[1], rawY = new float[1];
        final int[] startX = new int[1], startY = new int[1];
        final long[] downTime = new long[1];
        final int TOUCH_SLOP = 12;

        v.setOnTouchListener((view, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    rawX[0] = ev.getRawX();
                    rawY[0] = ev.getRawY();
                    startX[0] = p.x;
                    startY[0] = p.y;
                    downTime[0] = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - rawX[0];
                    float dy = ev.getRawY() - rawY[0];
                    p.x = startX[0] + (int) dx;
                    p.y = startY[0] + (int) dy;
                    try {
                        wm.updateViewLayout(view, p);
                    } catch (Exception ignored) {
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    float totalDx = Math.abs(ev.getRawX() - rawX[0]);
                    float totalDy = Math.abs(ev.getRawY() - rawY[0]);
                    long elapsed = System.currentTimeMillis() - downTime[0];
                    // 长按 + 没怎么移动 → 停止
                    if (elapsed > 800 && totalDx < TOUCH_SLOP && totalDy < TOUCH_SLOP) {
                        Toast("监控已停止");
                        cleanup();
                        stopSelf();
                    }
                    return true;
            }
            return false;
        });
    }

    /* ========================= 重新检测 ========================= */

    private void resetAndReload() {
        worker.post(() -> {
            stopCapture();
            if (targetBmp != null) {
                targetBmp.recycle();
                targetBmp = null;
            }
            targetTxt = null;

            loadTargets();

            AutomationHolder.Mode m = AutomationHolder.getInstance().getMode();
            if (m != AutomationHolder.Mode.IDLE) {
                active = true;
                AutomationHolder.getInstance().setMonitoring(true);
                mainHandler.post(this::beginMonitor);
                mainHandler.post(() -> Toast("已重新检测"));
            } else {
                active = false;
                AutomationHolder.getInstance().setMonitoring(false);
                mainHandler.post(() -> {
                    updateStatus("无目标", Color.GRAY);
                    Toast("未找到目标文件");
                });
            }
        });
    }

    /* ========================= 监控控制 ========================= */

    private void beginMonitor() {
        AutomationHolder.Mode m = AutomationHolder.getInstance().getMode();
        String label = m == AutomationHolder.Mode.IMAGE ? "IMG" : "TXT";
        updateStatus(label, Color.argb(255, 76, 175, 80));
        statusDot.setBackgroundColor(Color.GREEN);

        if (m == AutomationHolder.Mode.IMAGE) {
            startCapture();
        }
    }

    /* ========================= 屏幕截图 ========================= */

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
        @Override
        public void run() {
            if (!active || reader == null) return;

            Bitmap tgt = targetBmp;
            if (tgt != null) {
                Bitmap screen = grab();
                if (screen != null) {
                    float score = ImageMatcher.match(screen, tgt);
                    int sens = AutomationHolder.getInstance().getSensitivity();
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
        if (vDisplay != null) {
            vDisplay.release();
            vDisplay = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    /* ========================= 匹配回调 ========================= */

    private void onMatch(String reason) {
        boolean played = SoundPlayer.play(this);
        if (!played) return;

        mainHandler.post(() -> {
            updateStatus("匹配!", Color.RED);
            statusDot.setBackgroundColor(Color.RED);
            mainHandler.postDelayed(() -> {
                if (active) {
                    statusDot.setBackgroundColor(Color.GREEN);
                    String lbl = AutomationHolder.getInstance().getMode()
                            == AutomationHolder.Mode.IMAGE ? "IMG" : "TXT";
                    updateStatus(lbl, Color.argb(255, 76, 175, 80));
                }
            }, 2000);
        });
    }

    /* ========================= 文件监控 ========================= */

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
        } catch (Exception e) {
            return "";
        }
    }

    private void watchDir() {
        File dir = new File(TARGET_DIR);
        if (!dir.exists()) dir.mkdirs();

        fileObs = new android.os.FileObserver(dir.getAbsolutePath(),
                android.os.FileObserver.CREATE | android.os.FileObserver.DELETE
                        | android.os.FileObserver.MODIFY
                        | android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.MOVED_FROM) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null) return;
                worker.removeCallbacks(reloadRunnable);
                worker.postDelayed(reloadRunnable, 600);
            }
        };
        fileObs.startWatching();
    }

    private final Runnable reloadRunnable = this::reload;

    private void reload() {
        SoundPlayer.stop();
        stopCapture();
        if (targetBmp != null) {
            targetBmp.recycle();
            targetBmp = null;
        }
        targetTxt = null;
        loadTargets();

        AutomationHolder.Mode m = AutomationHolder.getInstance().getMode();
        if (m != AutomationHolder.Mode.IDLE) {
            active = true;
            AutomationHolder.getInstance().setMonitoring(true);
            mainHandler.post(this::beginMonitor);
        } else {
            active = false;
            AutomationHolder.getInstance().setMonitoring(false);
            mainHandler.post(() -> updateStatus("--", Color.GRAY));
        }
    }

    /* ========================= 工具方法 ========================= */

    private void setMode(AutomationHolder.Mode m) {
        AutomationHolder.getInstance().setMode(m);
        mainHandler.post(() -> {
            if (modeLabel == null) return;
            switch (m) {
                case IMAGE:
                    modeLabel.setText("IMG");
                    break;
                case TEXT:
                    modeLabel.setText("TXT");
                    break;
                default:
                    modeLabel.setText("--");
                    break;
            }
        });
    }

    private void updateStatus(String txt, int color) {
        if (modeLabel != null) modeLabel.setText(txt);
        if (statusDot != null) statusDot.setBackgroundColor(color);
    }

    private android.graphics.drawable.GradientDrawable makeRoundBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
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
        SoundPlayer.stop();
        stopCapture();
        if (fileObs != null) {
            fileObs.stopWatching();
            fileObs = null;
        }
        if (widget != null) try {
            wm.removeView(widget);
        } catch (Exception ignored) {
        }
        if (targetBmp != null) {
            targetBmp.recycle();
            targetBmp = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (workerThread != null) workerThread.quitSafely();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private void Toast(String msg) {
        mainHandler.post(() -> android.widget.Toast.makeText(this, msg,
                android.widget.Toast.LENGTH_SHORT).show());
    }
}