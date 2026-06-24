package com.jackkd.automation.core;

import android.content.Context;
import android.media.MediaPlayer;

import java.io.File;

public class SoundPlayer {

    private static final String SOUND_PATH =
            "/storage/emulated/0/自动化目标/李麻花处刑曲.mp3";
    private static final long COOLDOWN_MS = 5000;

    private static MediaPlayer player;
    private static long lastPlayTime = 0;

    public static synchronized boolean play(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastPlayTime < COOLDOWN_MS) return false;

        try {
            if (player != null) {
                if (player.isPlaying()) return false;
                player.release();
                player = null;
            }

            File f = new File(SOUND_PATH);
            if (!f.exists()) return false;

            lastPlayTime = now;
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
            player.setOnCompletionListener(mp -> {
                mp.release();
                player = null;
            });
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            if (player != null) {
                player.release();
                player = null;
            }
            return false;
        }
    }

    /** 停止正在播放的音乐并重置冷却 */
    public static synchronized void stop() {
        try {
            if (player != null) {
                if (player.isPlaying()) player.stop();
                player.release();
                player = null;
            }
        } catch (Exception e) {
            if (player != null) {
                player.release();
                player = null;
            }
        }
        lastPlayTime = 0;
    }
}
