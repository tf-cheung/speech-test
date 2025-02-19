package com.example.tts.service;

import org.springframework.stereotype.Service;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import java.util.concurrent.Future;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class TTSService {
    private final SpeechConfig speechConfig;

    public TTSService(SpeechConfig speechConfig) {
        this.speechConfig = speechConfig;
        // 设置语音服务的语言
        speechConfig.setSpeechRecognitionLanguage("zh-CN");
        speechConfig.setSpeechSynthesisLanguage("zh-CN");
    }

    public byte[] textToSpeech(String text) throws Exception {
        try (SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig)) {
            SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text).get();

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                return result.getAudioData();
            } else {
                throw new Exception("Speech synthesis failed: " + result.getReason());
            }
        }
    }

    public String speechToText(byte[] audioData) throws Exception {
        System.out.println("接收到音频数据，大小: " + audioData.length + " bytes");

        // 创建临时目录（如果不存在）
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "speech-recognition");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        // 创建临时文件
        File tempFile = File.createTempFile("speech", ".wav", tempDir);
        System.out.println("创建临时文件: " + tempFile.getAbsolutePath());

        try {
            // 保存接收到的音频数据
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }
            System.out.println("音频数据已写入临时文件");

            // 从文件创建音频配置
            AudioConfig audioConfig = AudioConfig.fromWavFileInput(tempFile.getAbsolutePath());
            System.out.println("已创建音频配置");

            try (SpeechRecognizer recognizer = new SpeechRecognizer(speechConfig, audioConfig)) {
                System.out.println("开始识别语音...");

                // 设置详细的识别选项
                recognizer.getProperties().setProperty("Language", "zh-CN");
                recognizer.getProperties().setProperty("Format", "Detailed");

                // 添加识别事件处理
                final StringBuilder resultBuilder = new StringBuilder();
                recognizer.recognized.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                        resultBuilder.append(e.getResult().getText());
                    }
                });

                // 同步等待识别结果
                Future<SpeechRecognitionResult> task = recognizer.recognizeOnceAsync();
                SpeechRecognitionResult result = task.get(30, TimeUnit.SECONDS);
                System.out.println("识别完成，结果: " + result.getReason());

                if (result.getReason() == ResultReason.RecognizedSpeech) {
                    String recognizedText = result.getText();
                    System.out.println("识别成功，文本: " + recognizedText);
                    return recognizedText;
                } else if (result.getReason() == ResultReason.NoMatch) {
                    CancellationDetails cancellation = CancellationDetails.fromResult(result);
                    System.out.println("无法识别语音: " + cancellation.getErrorDetails());
                    throw new Exception("无法识别语音: " + cancellation.getErrorDetails());
                } else if (result.getReason() == ResultReason.Canceled) {
                    CancellationDetails cancellation = CancellationDetails.fromResult(result);
                    System.out.println("识别取消: " + cancellation.getErrorDetails());
                    String errorMessage = String.format(
                            "识别取消: 原因=%s, 错误码=%s, 详情=%s",
                            cancellation.getReason(),
                            cancellation.getErrorCode(),
                            cancellation.getErrorDetails());
                    throw new Exception(errorMessage);
                } else {
                    System.out.println("识别失败: " + result.getReason());
                    throw new Exception("识别失败: " + result.getReason());
                }
            }
        } catch (Exception e) {
            System.err.println("处理过程中出错: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 清理临时文件
            if (tempFile.exists()) {
                boolean deleted = tempFile.delete();
                System.out.println("临时文件删除" + (deleted ? "成功" : "失败"));
            }
        }
    }
}