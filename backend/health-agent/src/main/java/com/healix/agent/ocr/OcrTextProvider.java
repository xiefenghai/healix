package com.healix.agent.ocr;

public interface OcrTextProvider {

    String extractText(byte[] imageBytes, String mimeType);
}
