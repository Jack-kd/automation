
package com.jackkd.automation.core;

import android.graphics.Bitmap;

/**
 * 全局状态单例，用于各组件之间共享自动化状态。
 */
public class AutomationHolder {

    private static volatile AutomationHolder instance;

    public enum Mode { IDLE, IMAGE, TEXT }

    private volatile Mode mode = Mode.IDLE;
    private Bitmap targetBitmap;
    private String targetText;
    private int sensitivity = 70;   // 1-100
    private volatile boolean monitoring = false;

    public static AutomationHolder getInstance() {
        if (instance == null) {
            synchronized (AutomationHolder.class) {
                if (instance == null) instance = new AutomationHolder();
            }
        }
        return instance;
    }

    // -------- getters / setters --------

    public Mode getMode()                       { return mode; }
    public void setMode(Mode m)                 { this.mode = m; }

    public Bitmap getTargetBitmap()             { return targetBitmap; }
    public void setTargetBitmap(Bitmap b)       { this.targetBitmap = b; }

    public String getTargetText()               { return targetText; }
    public void setTargetText(String t)         { this.targetText = t; }

    public int getSensitivity()                 { return sensitivity; }
    public void setSensitivity(int s)           { this.sensitivity = Math.max(1, Math.min(100, s)); }

    public boolean isMonitoring()               { return monitoring; }
    public void setMonitoring(boolean m)        { this.monitoring = m; }
}
