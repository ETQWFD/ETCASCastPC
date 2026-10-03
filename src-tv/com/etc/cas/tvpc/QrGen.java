package com.etc.cas.tvpc;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class QrGen {

    public static BufferedImage make(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    img.setRGB(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return img;
        } catch (Exception e) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics g = img.getGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 14));
            g.drawString("QR", size / 2 - 20, size / 2);
            g.dispose();
            return img;
        }
    }

    public static void save(BufferedImage img, File f) throws Exception {
        ImageIO.write(img, "png", f);
    }
}
