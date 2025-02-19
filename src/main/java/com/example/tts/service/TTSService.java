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
import java.util.Map;
import java.util.HashMap;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentConfig;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGradingSystem;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGranularity;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentResult;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.io.StringReader;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.HashSet;
import java.util.Set;
import java.lang.StringBuilder;

@Service
public class TTSService {
    private final SpeechConfig speechConfig;

    public TTSService(SpeechConfig speechConfig) {
        this.speechConfig = speechConfig;
        // 设置语音服务的语言，TTS保持中文，语音识别改为英文
        speechConfig.setSpeechSynthesisLanguage("zh-CN");
        speechConfig.setSpeechRecognitionLanguage("en-US"); // 改为英文
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

    public Map<String, Object> speechToTextWithAssessment(byte[] audioData, String referenceText) throws Exception {
        System.out.println("开始发音评估，参考文本: " + referenceText);
        File tempFile = File.createTempFile("speech", ".wav");

        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }

            AudioConfig audioConfig = AudioConfig.fromWavFileInput(tempFile.getAbsolutePath());
            Map<String, Object> response = new HashMap<>();
            List<Map<String, Object>> wordDetails = new ArrayList<>();
            List<Double> fluencyScores = new ArrayList<>();
            List<Double> prosodyScores = new ArrayList<>();
            List<Long> durations = new ArrayList<>();
            StringBuilder recognizedTextBuilder = new StringBuilder();

            Semaphore stopRecognitionSemaphore = new Semaphore(0);

            try (SpeechRecognizer recognizer = new SpeechRecognizer(speechConfig, audioConfig)) {
                // 配置发音评估
                PronunciationAssessmentConfig pronunciationConfig = new PronunciationAssessmentConfig(
                        referenceText,
                        PronunciationAssessmentGradingSystem.HundredMark,
                        PronunciationAssessmentGranularity.Word,
                        true);
                pronunciationConfig.applyTo(recognizer);

                // 添加识别中事件处理器
                recognizer.recognizing.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizingSpeech) {
                        System.out.println("Recognizing: " + e.getResult().getText());
                    }
                });

                // 添加识别完成事件处理器
                recognizer.recognized.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                        PronunciationAssessmentResult pronResult = PronunciationAssessmentResult
                                .fromResult(e.getResult());

                        String currentText = e.getResult().getText();
                        System.out.println("Recognized: " + currentText);
                        recognizedTextBuilder.append(currentText).append(" ");

                        // 收集评分
                        if (pronResult.getFluencyScore() != null) {
                            fluencyScores.add(pronResult.getFluencyScore());
                        }
                        if (pronResult.getProsodyScore() != null) {
                            prosodyScores.add(pronResult.getProsodyScore());
                        }

                        try {
                            String jsonResult = e.getResult().getProperties()
                                    .getProperty(PropertyId.SpeechServiceResponse_JsonResult);

                            JSONObject jsonObject = new JSONObject(jsonResult);
                            JSONArray nBestArray = jsonObject.getJSONArray("NBest");

                            for (int i = 0; i < nBestArray.length(); i++) {
                                JSONObject nBestObj = nBestArray.getJSONObject(i);
                                JSONArray words = nBestObj.getJSONArray("Words");
                                long durationSum = 0;

                                for (int j = 0; j < words.length(); j++) {
                                    JSONObject wordObj = words.getJSONObject(j);
                                    Map<String, Object> wordDetail = new HashMap<>();

                                    String word = wordObj.getString("Word");
                                    long duration = wordObj.getLong("Duration");

                                    JSONObject assessment = wordObj.getJSONObject("PronunciationAssessment");
                                    double accuracyScore = assessment.getDouble("AccuracyScore");
                                    String errorType = assessment.getString("ErrorType");

                                    wordDetail.put("word", word);
                                    wordDetail.put("duration", duration);
                                    wordDetail.put("accuracyScore", accuracyScore);
                                    wordDetail.put("errorType", errorType);
                                    wordDetail.put("offset", wordObj.getLong("Offset"));

                                    // 尝试获取音素信息
                                    if (wordObj.has("Phonemes")) {
                                        JSONArray phonemes = wordObj.getJSONArray("Phonemes");
                                        List<Map<String, Object>> phonemeList = new ArrayList<>();
                                        for (int k = 0; k < phonemes.length(); k++) {
                                            JSONObject phoneme = phonemes.getJSONObject(k);
                                            Map<String, Object> phonemeDetail = new HashMap<>();
                                            phonemeDetail.put("phoneme", phoneme.getString("Phoneme"));
                                            phonemeDetail.put("accuracyScore", phoneme.getDouble("AccuracyScore"));
                                            phonemeList.add(phonemeDetail);
                                        }
                                        wordDetail.put("phonemes", phonemeList);
                                    }

                                    wordDetails.add(wordDetail);
                                    durationSum += duration;
                                }
                                durations.add(durationSum);
                            }
                        } catch (Exception ex) {
                            System.err.println("Error parsing JSON result: " + ex.getMessage());
                        }
                    }
                });

                // 添加会话事件处理器
                recognizer.sessionStarted.addEventListener((s, e) -> {
                    System.out.println("Session started");
                });

                recognizer.sessionStopped.addEventListener((s, e) -> {
                    System.out.println("Session stopped");
                    stopRecognitionSemaphore.release();
                });

                // 开始连续识别
                recognizer.startContinuousRecognitionAsync().get();

                // 等待识别完成
                if (!stopRecognitionSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                    recognizer.stopContinuousRecognitionAsync().get();
                }

                // 处理最终结果
                String recognizedText = recognizedTextBuilder.toString().trim();
                calculateFinalScores(response, recognizedText, referenceText, wordDetails,
                        fluencyScores, prosodyScores, durations);

                return response;
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // 首先添加一个 Word 类来存储单词信息
    private static class Word {
        private String word;
        private String errorType;
        private double accuracyScore;

        public Word(String word, String errorType, double accuracyScore) {
            this.word = word;
            this.errorType = errorType;
            this.accuracyScore = accuracyScore;
        }

        public Word(String word, String errorType) {
            this(word, errorType, 0.0);
        }
    }

    private void calculateFinalScores(Map<String, Object> response,
            String recognizedText,
            String referenceText,
            List<Map<String, Object>> wordDetails,
            List<Double> fluencyScores,
            List<Double> prosodyScores,
            List<Long> durations) {

        // 转换 wordDetails 为 Word 对象列表
        List<Word> pronWords = new ArrayList<>();
        List<String> recognizedWords = new ArrayList<>();

        for (Map<String, Object> detail : wordDetails) {
            String word = ((String) detail.get("word")).toLowerCase();
            String errorType = (String) detail.get("errorType");
            double accuracyScore = ((Number) detail.get("accuracyScore")).doubleValue();

            pronWords.add(new Word(word, errorType, accuracyScore));
            recognizedWords.add(word);
        }

        // 处理参考文本
        String[] referenceWords = referenceText.toLowerCase()
                .replaceAll("[^a-z\\s]", "")
                .split("\\s+");

        // 创建最终的单词列表
        List<Word> finalWords = new ArrayList<>();

        // 比较参考文本和识别文本
        int currentIdx = 0;
        for (String refWord : referenceWords) {
            if (currentIdx < recognizedWords.size()) {
                String recWord = recognizedWords.get(currentIdx);
                if (refWord.equals(recWord)) {
                    // 单词匹配
                    finalWords.add(pronWords.get(currentIdx));
                    currentIdx++;
                } else {
                    // 检查是否是遗漏或插入
                    boolean found = false;
                    for (int i = currentIdx + 1; i < Math.min(currentIdx + 3, recognizedWords.size()); i++) {
                        if (refWord.equals(recognizedWords.get(i))) {
                            // 找到了匹配，之前的单词都是插入
                            for (int j = currentIdx; j < i; j++) {
                                Word w = pronWords.get(j);
                                w.errorType = "Insertion";
                                finalWords.add(w);
                            }
                            finalWords.add(pronWords.get(i));
                            currentIdx = i + 1;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // 没找到匹配，标记为遗漏
                        finalWords.add(new Word(refWord, "Omission"));
                    }
                }
            } else {
                // 识别文本结束，剩余的参考文本单词都是遗漏
                finalWords.add(new Word(refWord, "Omission"));
            }
        }

        // 处理剩余的识别文本（都是插入）
        while (currentIdx < recognizedWords.size()) {
            Word w = pronWords.get(currentIdx);
            w.errorType = "Insertion";
            finalWords.add(w);
            currentIdx++;
        }

        // 计算各项分数
        double accuracyScore = finalWords.stream()
                .filter(w -> !"Insertion".equals(w.errorType))
                .mapToDouble(w -> w.accuracyScore)
                .average()
                .orElse(0.0);

        // 计算完整度分数
        long validWords = finalWords.stream()
                .filter(w -> "None".equals(w.errorType))
                .count();
        double completenessScore = Math.min(100.0, (double) validWords / referenceWords.length * 100);

        // 计算流利度分数
        double fluencyScore = 0.0;
        if (!durations.isEmpty()) {
            double weightedSum = 0.0;
            double totalDuration = 0.0;
            for (int i = 0; i < durations.size(); i++) {
                weightedSum += fluencyScores.get(i) * durations.get(i);
                totalDuration += durations.get(i);
            }
            fluencyScore = weightedSum / totalDuration;
        }

        // 计算韵律分数
        double prosodyScore = prosodyScores.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        // 更新响应
        List<Map<String, Object>> updatedWordDetails = finalWords.stream()
                .map(w -> {
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("word", w.word);
                    detail.put("errorType", w.errorType);
                    detail.put("accuracyScore", w.accuracyScore);
                    return detail;
                })
                .collect(Collectors.toList());

        response.put("wordDetails", updatedWordDetails);
        response.put("accuracyScore", accuracyScore);
        response.put("completenessScore", completenessScore);
        response.put("fluencyScore", fluencyScore);
        response.put("prosodyScore", prosodyScore);
        response.put("recognizedText", recognizedText);
        response.put("referenceText", referenceText);
        response.put("errorStats", Map.of(
                "insertions", finalWords.stream().filter(w -> "Insertion".equals(w.errorType)).count(),
                "omissions", finalWords.stream().filter(w -> "Omission".equals(w.errorType)).count()));
    }
}