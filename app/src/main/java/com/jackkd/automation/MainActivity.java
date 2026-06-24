package com.jackkd.automation;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jackkd.automation.core.AutomationHolder;
import com.jackkd.automation.service.AutoAccessibilityService;
import com.jackkd.automation.service.FloatWidgetService;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 100;
    private static final int REQ_CAPTURE = 101;

    private TextView tvStatus;
    private SeekBar seekSensitivity;
    private TextView tvSensitivity;
    private Button btnStart;
    private boolean returningFromSettings = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        seekSensitivity = findViewById(R.id.seek_sensitivity);
        tvSensitivity = findViewById(R.id.tv_sensitivity);
        btnStart = findViewById(R.id.btn_start);

        int saved = getSharedPreferences("cfg", MODE_PRIVATE).getInt("sens", 70);
        seekSensitivity.setProgress(saved);
        tvSensitivity.setText("灵敏度: " + saved + "%");
        AutomationHolder.getInstance().setSensitivity(saved);

        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                v = Math.max(1, v);
                tvSensitivity.setText("灵敏度: " + v + "%");
                AutomationHolder.getInstance().setSensitivity(v);
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("sens", v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnStart.setOnClickListener(v -> requestCapture());
        checkAllPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (returningFromSettings) {
            returningFromSettings = false;
            checkAllPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        checkAllPermissions();
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req == REQ_CAPTURE && code == Activity.RESULT_OK && data != null) {
            Intent svc = new Intent(this, FloatWidgetService.class);
            svc.putExtra("resultCode", code);
            svc.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Toast.makeText(this, "监控已启动，可切到其他应用", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        } else if (req == REQ_CAPTURE) {
            Toast.makeText(this, "需要屏幕录制权限以进行图片匹配", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAllPermissions() {
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;

        boolean storage = checkStorage();
        sb.append(storage ? "OK 存储权限\n" : "-- 存储权限\n");
        if (!storage) {
            allOk = false;
            returningFromSettings = true;
            requestStorage();
            tvStatus.setText(sb);
            btnStart.setEnabled(false);
            return;
        }

        boolean overlay = Settings.canDrawOverlays(this);
        sb.append(overlay ? "OK 悬浮窗权限\n" : "-- 悬浮窗权限\n");
        if (!overlay) {
            allOk = false;
            returningFromSettings = true;
            requestOverlay();
            tvStatus.setText(sb);
            btnStart.setEnabled(false);
            return;
        }

        boolean notif = checkNotification();
        sb.append(notif ? "OK 通知权限\n" : "-- 通知权限\n");
        if (!notif) {
            allOk = false;
            tvStatus.setText(sb);
            btnStart.setEnabled(false);
            return;
        }

        boolean acc = isAccessibilityEnabled();
        sb.append(acc ? "OK 无障碍服务\n" : "-- 无障碍服务\n");
        if (!acc) {
            allOk = false;
            returningFromSettings = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            tvStatus.setText(sb);
            btnStart.setEnabled(false);
            return;
        }

        tvStatus.setText(sb);
        btnStart.setEnabled(allOk);
        btnStart.setText(allOk ? "启动监控" : "请先授予权限");
    }

    private boolean checkStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    private void requestOverlay() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean checkNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
                return false;
            }
        }
        return true;
    }

    private boolean isAccessibilityEnabled() {
        String expected = getPackageName() + "/"
                + AutoAccessibilityService.class.getCanonicalName();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String s : enabled.split(":")) {
            if (s.trim().equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    private void requestCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }
}
