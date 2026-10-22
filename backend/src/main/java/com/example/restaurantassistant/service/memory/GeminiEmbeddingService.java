package com.example.restaurantassistant.service.memory;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GeminiEmbeddingService implements EmbeddingService {
  private final ObjectMapper mapper;
  private final String key, model, base;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  public GeminiEmbeddingService(ObjectMapper mapper, @Value("${gemini.api-key}") String key,
      @Value("${gemini.embedding-model}") String model, @Value("${gemini.base-url}") String base) {
    this.mapper=mapper; this.key=key; this.model=model; this.base=base.replaceAll("/+$","");
  }
  public String model() { return model; }
  public float[] embed(String text, boolean query) {
    if(key.isBlank() || !model.matches("[a-zA-Z0-9._-]+")) throw new EmbeddingFailure(false);
    try {
      var body=Map.of("model","models/"+model,"content",Map.of("parts",List.of(Map.of("text",text))),
          "taskType",query?"RETRIEVAL_QUERY":"RETRIEVAL_DOCUMENT","outputDimensionality",768);
      var request=HttpRequest.newBuilder(URI.create(base+"/models/"+model+":embedContent"))
          .timeout(Duration.ofSeconds(query?3:20)).header("Content-Type","application/json")
          .header("x-goog-api-key",key).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
      var response=client.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()!=200) throw new EmbeddingFailure(response.statusCode()==429 || response.statusCode()>=500);
      var values=mapper.readTree(response.body()).path("embedding").path("values");
      if(!values.isArray() || values.size()!=768) throw new EmbeddingFailure(false);
      float[] vector=new float[768]; double norm=0;
      for(int i=0;i<vector.length;i++) {
        if(!values.path(i).isNumber()) throw new EmbeddingFailure(false);
        vector[i]=(float)values.path(i).asDouble();
        if(!Float.isFinite(vector[i])) throw new EmbeddingFailure(false);
        norm+=vector[i]*vector[i];
      }
      if(norm==0) throw new EmbeddingFailure(false);
      return vector;
    } catch(EmbeddingFailure e) {throw e;
    } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new EmbeddingFailure(true);
    } catch(java.io.IOException e) {throw new EmbeddingFailure(true);
    } catch(Exception e) {throw new EmbeddingFailure(false);}
  }
}
