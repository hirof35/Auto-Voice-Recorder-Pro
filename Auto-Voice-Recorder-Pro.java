package autoVoiceRecorder;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class AutoVoiceRecorder extends JFrame {
    // 録音設定: ステレオ, 44.1kHz, 16bit, リトルエンディアン
    private final AudioFormat format = new AudioFormat(44100.0f, 16, 2, true, false);
    
    private TargetDataLine recordLine;
    private JProgressBar levelBar = new JProgressBar(0, 100);
    private JLabel statusLabel = new JLabel("停止中", JLabel.CENTER);
    private JSlider thresholdSlider = new JSlider(0, 2000, 300); // 感度調整範囲を拡大
    
    private volatile boolean isRunning = false;
    private volatile boolean isWriting = false;
    private ByteArrayOutputStream currentOut;
    
    // 先行録音用のキュー (約0.5秒分保持)
    private final LinkedList<byte[]> preBufferQueue = new LinkedList<>();
    private final int MAX_PREBUFFER_COUNT = 15; // 2048byte * 15 = 約0.4秒

    public AutoVoiceRecorder() {
        super("無音カット・オートレコーダー Pro");
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        statusLabel.setForeground(Color.GRAY);

        JPanel centerPanel = new JPanel(new GridLayout(5, 1, 5, 5));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        centerPanel.add(new JLabel("マイクレベル:", JLabel.LEFT));
        centerPanel.add(levelBar);
        centerPanel.add(new JLabel("開始しきい値 (RMS):", JLabel.LEFT));
        centerPanel.add(thresholdSlider);
        
        JButton masterBtn = new JButton("監視開始 / 停止");
        masterBtn.setPreferredSize(new Dimension(0, 50));
        masterBtn.setFont(new Font("SansSerif", Font.BOLD, 16));
        masterBtn.addActionListener(e -> toggleMonitoring());

        add(statusLabel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(masterBtn, BorderLayout.SOUTH);

        pack();
        setSize(400, 350);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
    }

    private void toggleMonitoring() {
        if (!isRunning) startMonitoring();
        else stopMonitoring();
    }

    private void startMonitoring() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                JOptionPane.showMessageDialog(this, "このオーディオ形式はサポートされていません。");
                return;
            }
            recordLine = (TargetDataLine) AudioSystem.getLine(info);
            recordLine.open(format);
            recordLine.start();

            isRunning = true;
            statusLabel.setText("● 監視中（音待ち）");
            statusLabel.setForeground(new Color(0, 120, 215));

            new Thread(this::captureLoop).start();
        } catch (Exception ex) {
            ex.printStackTrace();
            isRunning = false;
        }
    }

    private void captureLoop() {
        byte[] buffer = new byte[2048]; // 約23ms (44.1kHz stereo)
        int silenceCounter = 0;
        final int SILENCE_THRESHOLD_LIMIT = 50; // 約1.2秒無音で停止

        while (isRunning) {
            int count = recordLine.read(buffer, 0, buffer.length);
            if (count <= 0) continue;

            double rms = calculateRMS(buffer, count);
            updateLevelBar((int) (rms / 20)); // バーの表示感度調整

            if (rms > thresholdSlider.getValue()) {
                if (!isWriting) {
                    startFileWriting();
                }
                silenceCounter = 0;
            } else {
                if (isWriting) {
                    silenceCounter++;
                    if (silenceCounter > SILENCE_THRESHOLD_LIMIT) {
                        stopFileWriting();
                    }
                }
            }

            if (isWriting) {
                currentOut.write(buffer, 0, count);
            } else {
                // 書き込み中でない時は先行バッファに溜める
                byte[] copy = buffer.clone();
                preBufferQueue.addLast(copy);
                if (preBufferQueue.size() > MAX_PREBUFFER_COUNT) {
                    preBufferQueue.removeFirst();
                }
            }
        }
    }

    private void startFileWriting() {
        currentOut = new ByteArrayOutputStream();
        // 先行バッファを書き込む（言葉の冒頭切れ防止）
        while (!preBufferQueue.isEmpty()) {
            byte[] b = preBufferQueue.removeFirst();
            currentOut.write(b, 0, b.length);
        }
        isWriting = true;
        updateStatus("● 録音中...", Color.RED);
    }

    private void stopFileWriting() {
        isWriting = false;
        byte[] data = currentOut.toByteArray();
        if (data.length > 5000) { // 極端に短いデータ（ノイズ等）は保存しない
            saveToFile(data);
        }
        updateStatus("● 監視中（音待ち）", new Color(0, 120, 215));
    }

    private void saveToFile(byte[] audioData) {
        String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File dir = new File("recordings");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "Rec_" + timeStamp + ".wav");

        try (ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
             AudioInputStream ais = new AudioInputStream(bais, format, audioData.length / format.getFrameSize())) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file);
            System.out.println("保存完了: " + file.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopMonitoring() {
        isRunning = false;
        if (isWriting) stopFileWriting();
        if (recordLine != null) {
            recordLine.stop();
            recordLine.close();
        }
        preBufferQueue.clear();
        updateStatus("停止中", Color.GRAY);
        levelBar.setValue(0);
    }

    /**
     * ステレオ 16bit リトルエンディアン形式のデータからRMSを計算
     */
    private double calculateRMS(byte[] audioData, int length) {
        long sum = 0;
        int numSamples = length / 2; // 16bit = 2bytes per sample
        
        for (int i = 0; i < length - 1; i += 2) {
            // リトルエンディアン合成
            int sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xff));
            sum += (long) sample * sample;
        }
        return Math.sqrt((double) sum / numSamples);
    }

    private void updateStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void updateLevelBar(int value) {
        SwingUtilities.invokeLater(() -> levelBar.setValue(Math.min(100, value)));
    }

    public static void main(String[] args) {
        // LookAndFeelをシステム標準に変更（見た目の向上）
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        
        SwingUtilities.invokeLater(() -> new AutoVoiceRecorder().setVisible(true));
    }
}
