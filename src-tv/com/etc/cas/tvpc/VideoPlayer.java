package com.etc.cas.tvpc;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import javax.swing.*;
import java.awt.*;

public class VideoPlayer {

    private static JFrame win;
    private static JFXPanel panel;
    private static MediaPlayer player;

    public static void play(final String url, final String title) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (win == null || !win.isVisible()) {
                    win = new JFrame("ETCAS 投屏接收 · 视频");
                    win.setSize(960, 540);
                    win.setLocationRelativeTo(null);
                    win.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
                    win.setBackground(java.awt.Color.BLACK);
                    panel = new JFXPanel();
                    win.setContentPane(panel);
                    win.setVisible(true);
                }
                Platform.runLater(() -> {
                    try {
                        if (player != null) {
                            player.stop();
                            player.dispose();
                            player = null;
                        }
                        Media media = new Media(url);
                        player = new MediaPlayer(media);
                        MediaView view = new MediaView(player);
                        view.setPreserveRatio(true);
                        StackPane root = new StackPane();
                        root.setStyle("-fx-background-color:#000");
                        root.getChildren().add(view);
                        Text tip = new Text(title == null || title.isEmpty() ? "正在播放" : title);
                        tip.setFont(new Font("Microsoft YaHei", 18));
                        tip.setFill(Color.WHITE);
                        root.getChildren().add(tip);
                        Scene scene = new Scene(root);
                        panel.setScene(scene);
                        player.setRate(1.0);
                        player.play();
                        tip.setVisible(false);
                    } catch (Throwable t) {
                        fallback(url);
                    }
                });
            } catch (Throwable t) {
                fallback(url);
            }
        });
    }

    public static void setRate(final float rate) {
        Platform.runLater(() -> {
            if (player != null) player.setRate(rate);
        });
    }

    private static void fallback(String url) {
        SwingUtilities.invokeLater(() -> {
            try {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ignored) {}
        });
    }
}
