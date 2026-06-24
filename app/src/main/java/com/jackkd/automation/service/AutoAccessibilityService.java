
package com.jackkd.automation.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.jackkd.automation.core.AutomationHolder;
import com.jackkd.automation.core.SoundPlayer;

/**
 * 文本模式的核心：监听屏幕内容变化，检查是否包含目标文本。
 */
public class AutoAccessibilityService extends AccessibilityService {

    private long lastCheck = 0;
    private static final long THROTTLE_MS = 500;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!AutomationHolder.getInstance().isMonitoring()) return;
        if (AutomationHolder.getInstance().getMode() != AutomationHolder.Mode.TEXT) return;

        long now = System.currentTimeMillis();
        if (now - lastCheck < THROTTLE_MS) return;
        lastCheck = now;

        String target = AutomationHolder.getInstance().getTargetText();
        if (target == null || target.isEmpty()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        root.recycle();

        if (sb.toString().contains(target)) {
            SoundPlayer.play(this);
        }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;

        CharSequence t = node.getText();
        if (t != null && t.length() > 0) sb.append(t).append(' ');

        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) sb.append(d).append(' ');

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, sb);
                child.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() { }
}
