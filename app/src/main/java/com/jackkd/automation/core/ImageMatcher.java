package com.jackkd.automation.core;

import android.graphics.Bitmap;

public class ImageMatcher {

    public static float match(Bitmap screen, Bitmap template) {
        if (screen == null || template == null) return 0f;
        if (screen.isRecycled() || template.isRecycled()) return 0f;
        int sw = screen.getWidth(), sh = screen.getHeight();
        int tw = template.getWidth(), th = template.getHeight();
        if (tw > sw || th > sh) return 0f;

        int maxDim = 360;
        float scale = Math.max(1f, Math.max(sw, sh) / (float) maxDim);
        int ssw = Math.round(sw / scale);
        int ssh = Math.round(sh / scale);
        int stw = Math.max(6, Math.round(tw / scale));
        int sth = Math.max(6, Math.round(th / scale));
        if (stw > ssw || sth > ssh) return 0f;

        Bitmap ss = Bitmap.createScaledBitmap(screen, ssw, ssh, true);
        Bitmap st = Bitmap.createScaledBitmap(template, stw, sth, true);
        int[] sp = new int[ssw * ssh];
        int[] tp = new int[stw * sth];
        ss.getPixels(sp, 0, ssw, 0, 0, ssw, ssh);
        st.getPixels(tp, 0, stw, 0, 0, stw, sth);
        if (ss != screen) ss.recycle();
        if (st != template) st.recycle();

        float best = 0f;
        int step = Math.max(1, (int)(scale * 0.5f));
        for (int y = 0; y <= ssh - sth && best < 0.97f; y += step) {
            for (int x = 0; x <= ssw - stw && best < 0.97f; x += step) {
                float s = pixelMatch(sp, tp, ssw, stw, sth, x, y);
                if (s > best) best = s;
            }
        }
        return best;
    }

    private static float pixelMatch(int[] s, int[] t, int sStride,
                                     int tw, int th, int ox, int oy) {
        int match = 0;
        int total = tw * th;
        for (int ty = 0; ty < th; ty++) {
            int si = (oy + ty) * sStride + ox;
            int ti = ty * tw;
            for (int tx = 0; tx < tw; tx++) {
                int sv = s[si + tx] | 0xFF000000;
                int tv = t[ti + tx] | 0xFF000000;
                int d = Math.abs(((sv >> 16) & 0xFF) - ((tv >> 16) & 0xFF))
                      + Math.abs(((sv >>  8) & 0xFF) - ((tv >>  8) & 0xFF))
                      + Math.abs(( sv        & 0xFF) - ( tv        & 0xFF));
                if (d < 60) match++;
            }
        }
        return (float) match / total;
    }
}
