package com.example.tts.controller;

import com.example.tts.service.TTSService;
import com.example.tts.model.TextRequest;
import com.example.tts.model.SpeechResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tts")
public class TTSController {
    private final TTSService ttsService;

    public TTSController(TTSService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesizeSpeech(@RequestBody TextRequest request) {
        try {
            byte[] audioData = ttsService.textToSpeech(request.getText());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(ContentDisposition.attachment().filename("speech.wav").build());

            return new ResponseEntity<>(audioData, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/recognize")
    public ResponseEntity<SpeechResponse> recognizeSpeech(@RequestParam("file") MultipartFile file) {
        try {
            byte[] audioData = file.getBytes();
            String recognizedText = ttsService.speechToText(audioData);

            SpeechResponse response = new SpeechResponse();
            response.setText(recognizedText);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}