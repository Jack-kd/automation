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
import java.nio.ByteBuffer;

public class FloatWidgetService extends Service {
    private static final String CH_ID = "automation_monitor";
    private static final int NOTIF_ID = 1001;
    private static final long INTERVAL = 500;
    private static final String TARGET_DIR = "/storage/emulated/0/自动化目标";

    private WindowManager wm;
    private View widget;
    private TextView modeLabel, scoreLabel;
    private View statusDot;
    private MediaProjection projection;
    private VirtualDisplay vDisplay;
    private ImageReader reader;
    private int scrW, scrH, scrDpi;
    private HandlerThread workerThread;
    private Handler worker, mainHandler;
    private Bitmap targetBmp;
    private String targetTxt;
    private volatile boolean active = false;
    private android.os.FileObserver fileObs;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        workerThread = new HandlerThread("w");
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
        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("auto").setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else
            startForeground(NOTIF_ID, n);

        int code = intent.getIntExtra("resultCode", 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33)
            data = intent.getParcelableExtra("data", Intent.class);
        else
            data = intent.getParcelableExtra("data");
        if (data != null) {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
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
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() { cleanup(); super.onDestroy(); }

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
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.setBackground(roundBg(Color.argb(230, 33, 33, 33), dp(16)));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new View(this);
        statusDot.setBackgroundColor(Color.YELLOW);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(8);
        top.addView(statusDot, dlp);

        modeLabel = new TextView(this);
        modeLabel.setText("...");
        modeLabel.setTextColor(Color.WHITE);
        modeLabel.setTextSize(14);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        top.addView(modeLabel, mlp);

        TextView btn = new TextView(this);
        btn.setText("重新检测");
        btn.setTextColor(Color.parseColor("#90CAF9"));
        btn.setTextSize(13);
        btn.setPadding(dp(6), 0, 0, 0);
        top.addView(btn);
        btn.setOnClickListener(v -> { SoundPlayer.stop(); resetAndReload(); });
        c.addView(top);

        scoreLabel = new TextView(this);
        scoreLabel.setText("");
        scoreLabel.setTextColor(Color.parseColor("#888888"));
        scoreLabel.setTextSize(10);
        scoreLabel.setPadding(dp(18), dp(2), 0, 0);
        c.addView(scoreLabel);
        return c;
    }

    private void setupDrag(View v, WindowManager.LayoutParams p) {
        final float[] rx = {0}, ry = {0};
        final int[] sx = {0}, sy = {0};
        final long[] dt = {0};
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    rx[0] = ev.getRawX(); ry[0] = ev.getRawY();
                    sx[0] = p.x; sy[0] = p.y;
                    dt[0] = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    p.x = sx[0] + (int)(ev.getRawX() - rx[0]);
                    p.y = sy[0] + (int)(ev.getRawY() - ry[0]);
                    try { wm.updateViewLayout(view, p); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (System.currentTimeMillis() - dt[0] > 800
                            && Math.abs(ev.getRawX() - rx[0]) < 12
                            && Math.abs(ev.getRawY() - ry[0]) < 12) {
                        cleanup(); stopSelf();
                    }
                    return true;
            }
            return false;
        });
    }
// PART2_BELOW
    private void resetAndReload() {
        worker.post(() -> {
            stopCapture();
            if (targetBmp != null) { targetBmp.recycle(); targetBmp = null; }
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
                mainHandler.post(() -> { modeLabel.setText("--"); statusDot.setBackgroundColor(Color.GRAY); });
            }
        });
    }

    private void beginMonitor() {
        AutomationHolder.Mode m = AutomationHolder.getInstance().getMode();
        modeLabel.setText(m == AutomationHolder.Mode.IMAGE ? "IMG" : "TXT");
        statusDot.setBackgroundColor(Color.GREEN);
        if (m == AutomationHolder.Mode.IMAGE) startCapture();
    }

    private void startCapture() {
        if (projection == null || reader != null) return;
        reader = ImageReader.newInstance(scrW, scrH, PixelFormat.RGBA_8888, 2);
        vDisplay = projection.createVirtualDisplay("AC", scrW, scrH, scrDpi,
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
                    float threshold = 1.0f - (sens / 100.0f) * 0.5f;
                    final float sc = score;
                    final float th = threshold;
                    mainHandler.post(() -> {
                        if (scoreLabel != null)
                            scoreLabel.setText(screen.getWidth() + "x" + screen.getHeight()
                                    + "  score=" + String.format("%.2f", sc)
                                    + "  thr=" + String.format("%.2f", th));
                    });
                    if (score >= threshold) onMatch();
                    screen.recycle();
                } else {
                    mainHandler.post(() -> {
                        if (scoreLabel != null) scoreLabel.setText("grab=null");
                    });
                }
            }
            worker.postDelayed(this, INTERVAL);
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
            ByteBuffer buf = pl.getBuffer();
            buf.rewind();
            int ps = pl.getPixelStride(), rs = pl.getRowStride();
            int pad = rs - ps * w;
            Bitmap bmp = Bitmap.createBitmap(w + pad / ps, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            if (bmp.getWidth() != w) {
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
        if (reader != null) { reader.close(); reader = null; }
    }

    private void onMatch() {
        if (!SoundPlayer.play(this)) return;
        mainHandler.post(() -> {
            statusDot.setBackgroundColor(Color.RED);
            modeLabel.setText("HIT!");
            mainHandler.postDelayed(() -> {
                if (active) {
                    statusDot.setBackgroundColor(Color.GREEN);
                    modeLabel.setText(AutomationHolder.getInstance().getMode()
                            == AutomationHolder.Mode.IMAGE ? "IMG" : "TXT");
                }
            }, 2000);
        });
    }

    private void loadTargets() {
        File dir = new File(TARGET_DIR);
        if (!dir.exists()) dir.mkdirs();
        File png = null, txt = null;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".png") && png == null) png = f;
            else if (n.endsWith(".txt") && txt == null) txt = f;
        }
        if (png != null) {
            targetBmp = loadBmp(png);
            AutomationHolder.getInstance().setTargetBitmap(targetBmp);
            setMode(AutomationHolder.Mode.IMAGE);
        } else if (txt != null) {
            targetTxt = readTxt(txt);
            AutomationHolder.getInstance().setTargetText(targetTxt);
            setMode(AutomationHolder.Mode.TEXT);
        } else {
            setMode(AutomationHolder.Mode.IDLE);
        }
    }

    private Bitmap loadBmp(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sc = 1;
        while (o.outWidth / sc > 800 || o.outHeight / sc > 800) sc *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sc;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private String readTxt(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append("\n");
            br.close();
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private void watchDir() {
        File dir = new File(TARGET_DIR);
        if (!dir.exists()) dir.mkdirs();
        fileObs = new android.os.FileObserver(dir.getAbsolutePath(),
                android.os.FileObserver.CREATE | android.os.FileObserver.DELETE
                        | android.os.FileObserver.MODIFY | android.os.FileObserver.MOVED_TO
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
        SoundPlayer.stop();
        stopCapture();
        if (targetBmp != null) { targetBmp.recycle(); targetBmp = null; }
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
            mainHandler.post(() -> { modeLabel.setText("--"); statusDot.setBackgroundColor(Color.GRAY); });
        }
    }

    private void setMode(AutomationHolder.Mode m) {
        AutomationHolder.getInstance().setMode(m);
        mainHandler.post(() -> {
            if (modeLabel == null) return;
            modeLabel.setText(m == AutomationHolder.Mode.IMAGE ? "IMG"
                    : m == AutomationHolder.Mode.TEXT ? "TXT" : "--");
        });
    }

    private android.graphics.drawable.GradientDrawable roundBg(int color, int r) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(r);
        return d;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "auto", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void cleanup() {
        active = false;
        AutomationHolder.getInstance().setMonitoring(false);
        SoundPlayer.stop();
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
}