package com.example.tts.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.microsoft.cognitiveservices.speech.SpeechConfig;

@Configuration
public class AzureConfig {
    @Value("${azure.speech.key}")
    private String speechKey;

    @Value("${azure.speech.region}")
    private String speechRegion;

    @Bean
    public SpeechConfig speechConfig() {
        return SpeechConfig.fromSubscription(speechKey, speechRegion);
    }
}