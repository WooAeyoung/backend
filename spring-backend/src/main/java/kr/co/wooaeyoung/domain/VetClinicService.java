package kr.co.wooaeyoung.domain;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VetClinicService {
    private record Clinic(String id, String name, String address, double lat, double lon, String phone) {}

    private static final List<Clinic> CLINICS = List.of(
            new Clinic("vet-seoul-gangnam-01", "강남24시동물병원", "서울 강남구 역삼로 123", 37.4981, 127.0276, "02-0000-0001"),
            new Clinic("vet-seoul-mapogu-01", "마포동물병원", "서울 마포구 월드컵북로 45", 37.5663, 126.9014, "02-0000-0002"),
            new Clinic("vet-seoul-songpa-01", "송파 우리동물메디컬센터", "서울 송파구 올림픽로 240", 37.5133, 127.1001, "02-0000-0003")
    );

    public List<Map<String, Object>> nearest(double lat, double lon, int limit) {
        return CLINICS.stream().map(clinic -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", clinic.id()); item.put("name", clinic.name()); item.put("address", clinic.address());
            item.put("lat", clinic.lat()); item.put("lon", clinic.lon()); item.put("phone", clinic.phone());
            item.put("distanceKm", Math.round(distanceKm(lat, lon, clinic.lat(), clinic.lon()) * 1000.0) / 1000.0);
            item.put("mapUrl", "https://www.google.com/maps/search/?api=1&query=" + clinic.lat() + "," + clinic.lon());
            return item;
        }).sorted(Comparator.comparingDouble(item -> (Double) item.get("distanceKm"))).limit(limit).toList();
    }

    private double distanceKm(double lat, double lon, double targetLat, double targetLon) {
        double dLat = Math.toRadians(targetLat - lat), dLon = Math.toRadians(targetLon - lon);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(targetLat)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371.0088 * 2 * Math.asin(Math.sqrt(a));
    }
}
