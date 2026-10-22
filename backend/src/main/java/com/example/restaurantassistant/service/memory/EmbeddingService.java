package com.example.restaurantassistant.service.memory;

public interface EmbeddingService {
  String model();
  float[] embed(String text, boolean query);

  class EmbeddingFailure extends RuntimeException {
    public final boolean retryable;
    public EmbeddingFailure(boolean retryable) { super("Embedding request failed"); this.retryable=retryable; }
  }
}
