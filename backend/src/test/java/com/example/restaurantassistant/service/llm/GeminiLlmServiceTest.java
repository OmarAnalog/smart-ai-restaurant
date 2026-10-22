package com.example.restaurantassistant.service.llm;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.exception.LlmException;
import com.example.restaurantassistant.exception.LlmUnavailableException;
import com.example.restaurantassistant.model.*;
import java.math.BigDecimal;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiLlmServiceTest {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient client = mock(HttpClient.class);
  private final List<Long> delays = new ArrayList<>();

  private GeminiLlmService service(String key) {
    return new GeminiLlmService(mapper, key, "test-model", "https://api.example.test/v1beta/", client, delays::add);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> response(int status, String body) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    when(response.body()).thenReturn(body);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    return response;
  }

  private HttpResponse<String> content(String json, String finish) throws Exception {
    // Supply the required intent contract in legacy transport fixtures.
    if (json.startsWith("{\"updates\":")) {
      var tree = mapper.readTree(json);
      if (tree.isObject() && !tree.has("intent")) {
        ((tools.jackson.databind.node.ObjectNode) tree).set("intent", mapper.valueToTree(Intent.empty()));
        json=tree.toString();
      }
    }
    var candidate = mapper.createObjectNode().put("finishReason", finish);
    candidate.putObject("content").putArray("parts").addObject().put("text", json);
    var result = mapper.createObjectNode();
    result.putArray("candidates").add(candidate);
    return response(200, result.toString());
  }

  private HttpRequest capturedRequest() throws Exception {
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    return request.getValue();
  }

  private String requestBody(HttpRequest request) throws Exception {
    var result = new CompletableFuture<String>();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      private final StringBuilder text = new StringBuilder();
      public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(ByteBuffer buffer) { text.append(StandardCharsets.UTF_8.decode(buffer)); }
      public void onError(Throwable error) { result.completeExceptionally(error); }
      public void onComplete() { result.complete(text.toString()); }
    });
    return result.get(2, TimeUnit.SECONDS);
  }

  @Test void parsesExtractionAndUsesNativeGeminiSchemaAndHeader() throws Exception {
    content("{\"updates\":[{\"operation\":\"UPSERT\",\"type\":\"ALLERGY\",\"value\":\"peanut\",\"confidence\":0.99}]}", "STOP");
    var result = service("test-key").extractPreferences("Peanut allergy", List.of());
    assertThat(result.updates()).containsExactly(new Update(Operation.UPSERT, PreferenceType.ALLERGY, "peanut", .99));
    var request = capturedRequest();
    assertThat(request.uri().toString()).isEqualTo("https://api.example.test/v1beta/models/test-model:generateContent");
    assertThat(request.headers().firstValue("x-goog-api-key")).contains("test-key");
    assertThat(request.headers().firstValue("Authorization")).isEmpty();
    var body = mapper.readTree(requestBody(request));
    assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText()).contains("Extract only explicit food preferences");
    assertThat(body.at("/generationConfig/responseFormat/text/mimeType").asText()).isEqualTo("APPLICATION_JSON");
    assertThat(body.at("/generationConfig/responseFormat/text/schema/properties/updates/items/properties/operation/enum").toString()).isEqualTo("[\"UPSERT\",\"REMOVE\"]");
    assertThat(body.toString()).doesNotContain("test-key");
  }

  @Test void recommendationSchemaContainsOnlySuppliedCandidateIds() throws Exception {
    var item = new MenuItem("garden-salad", "Garden Salad", "Salads", "Fresh", new BigDecimal("9"),
        List.of("lettuce"), List.of(), true, true, 0);
    content("{\"message\":\"Try this option.\",\"recommendations\":[{\"itemId\":\"garden-salad\",\"reason\":\"Within your budget\"}]}", "STOP");
    var result = service("test-key").generateRecommendation(new LlmService.Context(
        "What do you recommend?", List.of(), List.of(), List.of(item)));
    assertThat(result.recommendations()).containsExactly(new Pick("garden-salad", "Within your budget"));
    var body = mapper.readTree(requestBody(capturedRequest()));
    assertThat(body.at("/generationConfig/responseFormat/text/schema/properties/recommendations/items/properties/itemId/enum").toString()).isEqualTo("[\"garden-salad\"]");
    assertThat(body.at("/systemInstruction/parts/0/text").asText()).contains("Only recommend supplied candidate item IDs");
  }

  @Test void missingKeyDoesNotMakeNetworkRequest() {
    assertThatThrownBy(() -> service("").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmException.class).hasMessageContaining("GEMINI_API_KEY");
    verifyNoInteractions(client);
  }

  @Test void rejectsMalformedJsonUnknownFieldsAndMissingFields() throws Exception {
    for (var json : List.of("not json", "{\"updates\":[],\"extra\":true}", "{}")) {
      content(json, "STOP");
      assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of()))
          .isInstanceOf(LlmException.class).isNotInstanceOf(LlmUnavailableException.class);
    }
  }

  @Test void rejectsTruncatedBlockedEmptyAndMissingCandidates() throws Exception {
    for (var finish : List.of("MAX_TOKENS", "SAFETY", "RECITATION")) {
      content("{\"updates\":[]}", finish);
      assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of())).isInstanceOf(LlmException.class).isNotInstanceOf(LlmUnavailableException.class);
    }
    for (var body : List.of("{}", "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
        "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}]}")) {
      response(200, body);
      assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of())).isInstanceOf(LlmException.class).isNotInstanceOf(LlmUnavailableException.class);
    }
  }

  @Test void combinesTextPartsAndIgnoresThoughtParts() throws Exception {
    response(200, "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
        + "{\"thought\":true,\"text\":\"internal reasoning\"},{\"text\":\"{\\\"updates\\\":\"},{\"text\":\"[],\\\"intent\\\":{\\\"foods\\\":[],\\\"ingredients\\\":[],\\\"excludedFoods\\\":[],\\\"excludedIngredients\\\":[],\\\"allergies\\\":[],\\\"diets\\\":[],\\\"budgetMax\\\":null,\\\"spice\\\":null}}\"}]}}]}");
    assertThat(service("test-key").extractPreferences("hi", List.of()).updates()).isEmpty();
  }

  @Test void reportsQuotaLimitsWithoutLeakingRawProviderMessageOrRetrying() throws Exception {
    response(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"secret-test-key\"}}");
    assertThatThrownBy(() -> service("secret-test-key").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmException.class).hasMessageContaining("quota or rate limit")
        .hasMessageNotContaining("secret-test-key");
    capturedRequest(); // Quota errors do not trigger automatic retries.
  }

  @Test void authenticationAndModelErrorsAreActionable() throws Exception {
    for (var status : List.of(401, 403, 404, 400)) {
      response(status, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"secret-test-key\"}}");
      assertThatThrownBy(() -> service("secret-test-key").extractPreferences("hi", List.of()))
          .isInstanceOf(LlmException.class).hasMessageContaining("Gemini")
          .hasMessageNotContaining("secret-test-key");
    }
  }

  @Test void malformedUpstreamErrorsKeepHttpClassification() throws Exception {
    response(429, "not JSON secret-test-key");
    assertThatThrownBy(() -> service("secret-test-key").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmException.class).hasMessageContaining("quota or rate limit")
        .hasMessageNotContaining("secret-test-key");
    response(503, "not JSON secret-test-key");
    assertThatThrownBy(() -> service("secret-test-key").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmException.class).hasMessageContaining("unavailable")
        .hasMessageNotContaining("secret-test-key");
  }

  @Test void interruptionPreservesThreadFlag() throws Exception {
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException());
    try {
      assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of()))
          .isInstanceOf(LlmException.class).hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @SuppressWarnings("unchecked")
  @Test void retriesTransientFailureThenReturnsSuccessWithoutRebuildingThePrompt() throws Exception {
    var success = content("{\"updates\":[]}", "STOP");
    reset(client);
    HttpResponse<String> unavailable = mock(HttpResponse.class);
    when(unavailable.statusCode()).thenReturn(503);
    when(unavailable.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(unavailable, unavailable, success);
    assertThat(service("test-key").extractPreferences("hi", List.of()).updates()).isEmpty();
    var requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(delays).hasSize(2);
    assertThat(delays.get(0)).isBetween(1000L, 1250L);
    assertThat(delays.get(1)).isBetween(2000L, 2250L);
    String original = requestBody(requests.getAllValues().getFirst());
    for (var request : requests.getAllValues()) {
      assertThat(requestBody(request)).isEqualTo(original);
      assertThat(request.timeout().orElseThrow()).isLessThanOrEqualTo(java.time.Duration.ofSeconds(45));
    }
  }

  @Test void stopsAfterThreeAttemptsOnPersistentServiceFailure() throws Exception {
    response(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
    assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmException.class).hasMessageContaining("bounded recovery");
    verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertThat(delays).hasSize(2);
  }

  @Test void clientErrorsDoNotRetry() throws Exception {
    for (int status : List.of(400, 401, 402, 403, 404, 429)) {
      reset(client);
      delays.clear();
      response(status, "{}");
      assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of()))
          .isInstanceOf(LlmException.class);
      verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
      assertThat(delays).isEmpty();
    }
  }

  @SuppressWarnings("unchecked")
  @Test void retryAfterBeyondBudgetStopsWithoutSleepingOrRetryingEarly() throws Exception {
    String futureDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2)
        .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
    for (String header : List.of("120", futureDate)) {
      reset(client);
      HttpResponse<String> unavailable = mock(HttpResponse.class);
      when(unavailable.statusCode()).thenReturn(503);
      when(unavailable.body()).thenReturn("{}");
      when(unavailable.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of(header)), (name, value) -> true));
      when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(unavailable);
      assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of())).isInstanceOf(LlmException.class);
      verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
      assertThat(delays).isEmpty();
    }
  }

  @SuppressWarnings("unchecked")
  @Test void respectsRetryAfterSecondsWithinBudget() throws Exception {
    HttpResponse<String> unavailable = mock(HttpResponse.class);
    when(unavailable.statusCode()).thenReturn(503);
    when(unavailable.body()).thenReturn("{}");
    when(unavailable.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("3")), (name, value) -> true));
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(unavailable);
    assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of())).isInstanceOf(LlmException.class);
    assertThat(delays).containsExactly(3000L, 3000L);
    verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test void interruptedBackoffStopsFurtherAttempts() throws Exception {
    response(503, "{}");
    var interrupted = new GeminiLlmService(mapper, "test-key", "test-model", "https://test.invalid",
        client, milliseconds -> { throw new InterruptedException(); });
    try {
      assertThatThrownBy(() -> interrupted.extractPreferences("hi", List.of()))
          .isInstanceOf(LlmException.class).hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    } finally {
      Thread.interrupted();
    }
  }

  @Test void timeoutIsAvailabilityFailureRatherThanMalformedOutput() throws Exception {
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new HttpTimeoutException("timeout"));
    assertThatThrownBy(() -> service("test-key").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("timed out")
        .hasCauseInstanceOf(HttpTimeoutException.class);
    verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertThat(delays).isEmpty();
  }

  @Test void connectionFailureIsAvailabilityFailure() throws Exception {
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new java.io.IOException("secret-test-key"));
    assertThatThrownBy(() -> service("secret-test-key").extractPreferences("hi", List.of()))
        .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("could not be reached")
        .hasMessageNotContaining("secret-test-key");
    verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }


  @Test void parsesTemporaryIntentSeparatelyAndSendsRecentHistory() throws Exception {
    content("{\"updates\":[],\"intent\":{\"foods\":[\"bowl\"],\"ingredients\":[\"chicken\"],\"excludedFoods\":[],\"excludedIngredients\":[\"mushroom\"],\"allergies\":[],\"diets\":[],\"budgetMax\":\"15\",\"spice\":\"spicy\"}}", "STOP");
    var recent=List.of(new Turn("USER","A chicken meal please"));
    var extracted=service("test-key").extractPreferences("Make it a spicy bowl under $15 today",List.of(),recent);
    assertThat(extracted.updates()).isEmpty();
    assertThat(extracted.intent().ingredients()).containsExactly("chicken");
    assertThat(extracted.intent().budgetMax()).isEqualTo("15");
    var request=mapper.readTree(requestBody(capturedRequest()));
    var payload=mapper.readTree(request.at("/contents/0/parts/0/text").asText());
    assertThat(payload.at("/recentMessages/0/content").asText()).isEqualTo("A chicken meal please");
    assertThat(request.at("/generationConfig/responseFormat/text/schema/properties/intent/properties/ingredients/type").asText()).isEqualTo("array");
    assertThat(request.at("/systemInstruction/parts/0/text").asText()).contains("temporary intent, not updates");
  }

  @Test void rejectsMissingIntentInStructuredResponse() throws Exception {
    response(200,"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{\\\"updates\\\":[]}\"}]}}]}");
    assertThatThrownBy(()->service("test-key").extractPreferences("hi",List.of())).isInstanceOf(LlmException.class);
  }

}
