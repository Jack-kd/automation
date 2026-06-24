
package com.jackkd.automation.core;

import android.graphics.Bitmap;

/**
 * 轻量级模板匹配 —— 无 OpenCV 依赖。
 * 通过缩小后逐像素滑窗比较，返回 0~1 的置信度。
 */
public class ImageMatcher {

    /**
     * @param screen   屏幕截图
     * @param template 目标图片
     * @return 匹配置信度 0.0 - 1.0
     */
    public static float match(Bitmap screen, Bitmap template) {
        if (screen == null || template == null) return 0f;
        if (screen.isRecycled() || template.isRecycled()) return 0f;

        // 确定缩放因子（保证性能）
        int maxDim = 320;
        int sScale = Math.max(1, Math.max(screen.getWidth(), screen.getHeight()) / maxDim);
        int tScale = Math.max(1, Math.max(template.getWidth(), template.getHeight()) / (maxDim / 2));
        int scale  = Math.max(sScale, tScale);

        int sw = screen.getWidth() / scale;
        int sh = screen.getHeight() / scale;
        int tw = template.getWidth() / scale;
        int th = template.getHeight() / scale;

        if (tw < 4 || th < 4 || tw > sw || th > sh) return 0f;

        Bitmap ss = Bitmap.createScaledBitmap(screen, sw, sh, true);
        Bitmap st = Bitmap.createScaledBitmap(template, tw, th, true);
        boolean recycleSs = (ss != screen);
        boolean recycleSt = (st != template);

        int[] sp = new int[sw * sh];
        int[] tp = new int[tw * th];
        ss.getPixels(sp, 0, sw, 0, 0, sw, sh);
        st.getPixels(tp, 0, tw, 0, 0, tw, th);

        float best = 0f;
        int step = Math.max(1, scale / 3);

        for (int y = 0; y <= sh - th && best < 0.97f; y += step) {
            for (int x = 0; x <= sw - tw && best < 0.97f; x += step) {
                float s = regionScore(sp, tp, sw, tw, th, x, y);
                if (s > best) best = s;
            }
        }

        if (recycleSs) ss.recycle();
        if (recycleSt) st.recycle();
        return best;
    }

    /** 计算区域的相似度（基于 MSE 的归一化） */
    private static float regionScore(int[] scr, int[] tpl,
                                     int sw, int tw, int th, int ox, int oy) {
        long diff = 0;
        for (int ty = 0; ty < th; ty++) {
            int sr = (oy + ty) * sw + ox;
            int tr = ty * tw;
            for (int tx = 0; tx < tw; tx++) {
                int sv = scr[sr + tx];
                int tv = tpl[tr + tx];
                int dr = ((sv >> 16) & 0xFF) - ((tv >> 16) & 0xFF);
                int dg = ((sv >>  8) & 0xFF) - ((tv >>  8) & 0xFF);
                int db = ( sv        & 0xFF) - ( tv         & 0xFF);
                diff += dr * dr + dg * dg + db * db;
            }
        }
        double mse = diff / (double) (tw * th);
        // 441.67 = sqrt(255^2 * 3)
        return (float) (1.0 - Math.sqrt(mse) / 441.67);
    }
}
