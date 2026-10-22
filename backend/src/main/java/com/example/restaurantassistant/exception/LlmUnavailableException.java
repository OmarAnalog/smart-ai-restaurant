package com.example.restaurantassistant.exception;

/** A transient provider outage; distinct from invalid or unsafe model output. */
public class LlmUnavailableException extends LlmException {
  public LlmUnavailableException(String message) {
    super(message);
  }

  public LlmUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
