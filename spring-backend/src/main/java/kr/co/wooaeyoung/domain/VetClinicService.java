package kr.co.wooaeyoung.domain;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.time.Duration;

@Service
public class VetClinicService {
    private final String kakaoKey;
    private final RestClient http;

    public VetClinicService(@Value("${app.kakao-rest-api-key:}") String kakaoKey) {
        this.kakaoKey = kakaoKey == null ? "" : kakaoKey.trim();
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(requests).build();
    }
    public List<Map<String, Object>> nearest(double lat, double lon, int limit) {
        if (kakaoKey.isBlank()) throw new ExternalServiceUnavailableException("동물병원 검색 서비스가 설정되지 않았습니다.");
        try {
            JsonNode body = http.get().uri(uri -> uri.scheme("https").host("dapi.kakao.com").path("/v2/local/search/keyword.json")
                    .queryParam("query", "동물병원").queryParam("x", lon).queryParam("y", lat)
                    .queryParam("radius", 20000).queryParam("sort", "distance").queryParam("size", limit).build())
                    .header("Authorization", "KakaoAK " + kakaoKey).retrieve().body(JsonNode.class);
            List<Map<String, Object>> results = new ArrayList<>();
            if (body != null && body.has("documents")) for (JsonNode place : body.get("documents")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", place.path("id").asText()); item.put("name", place.path("place_name").asText());
                item.put("address", place.path("road_address_name").asText(place.path("address_name").asText()));
                item.put("lat", place.path("y").asDouble()); item.put("lon", place.path("x").asDouble());
                item.put("phone", place.path("phone").asText()); item.put("distanceKm", Math.round(place.path("distance").asDouble() / 10.0) / 100.0);
                item.put("mapUrl", place.path("place_url").asText("https://map.kakao.com/link/map/" + place.path("id").asText())); results.add(item);
            }
            return results;
        } catch (ExternalServiceUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new ExternalServiceUnavailableException("동물병원 검색 서비스에 연결하지 못했습니다.", error);
        }
    }
}
