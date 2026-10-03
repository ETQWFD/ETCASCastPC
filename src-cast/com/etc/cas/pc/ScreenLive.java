package com.etc.cas.pc;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

public class ScreenLive {

    public static final AtomicBoolean running = new AtomicBoolean(false);
    private static Thread thread;
    public static volatile int fps = 10;

    public static void start() {
        if (running.get()) return;
        running.set(true);
        FileServer.mirrorFrame = null;
        thread = new Thread(() -> {
            try {
                Robot robot = new Robot();
                Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                while (running.get()) {
                    long t0 = System.currentTimeMillis();
                    try {
                        BufferedImage img = robot.createScreenCapture(screen);
                        int w = Math.max(320, img.getWidth() / 2);
                        int h = Math.max(180, img.getHeight() / 2);
                        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        small.getGraphics().drawImage(img, 0, 0, w, h, null);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ImageIO.write(small, "jpg", bos);
                        FileServer.mirrorFrame = bos.toByteArray();
                        FileServer.mirrorSeq++;
                    } catch (Exception ignored) {}
                    long wait = 1000L / fps - (System.currentTimeMillis() - t0);
                    if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
                }
            } catch (Exception e) {
                running.set(false);
            }
        }, "etcas-pc-screen");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        FileServer.mirrorFrame = null;
    }
}
