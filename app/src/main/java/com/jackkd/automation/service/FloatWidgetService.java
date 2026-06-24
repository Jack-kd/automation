
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
    public IBinder on
