"""Small, provider-free nearest veterinary clinic search.

The catalog is intentionally local so the API works without exposing a map
provider key. A production deployment can replace VET_CLINICS with a synced
public-data adapter without changing the response contract.
"""

from math import asin, cos, radians, sin, sqrt

VET_CLINICS = [
    {"id": "vet-seoul-gangnam-01", "name": "강남24시동물병원", "address": "서울 강남구 역삼로 123", "lat": 37.4981, "lon": 127.0276, "phone": "02-0000-0001"},
    {"id": "vet-seoul-mapogu-01", "name": "마포동물병원", "address": "서울 마포구 월드컵북로 45", "lat": 37.5663, "lon": 126.9014, "phone": "02-0000-0002"},
    {"id": "vet-seoul-songpa-01", "name": "송파 우리동물메디컬센터", "address": "서울 송파구 올림픽로 240", "lat": 37.5133, "lon": 127.1001, "phone": "02-0000-0003"},
]


def distance_km(lat: float, lon: float, clinic_lat: float, clinic_lon: float) -> float:
    earth_radius_km = 6371.0088
    d_lat = radians(clinic_lat - lat)
    d_lon = radians(clinic_lon - lon)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat)) * cos(radians(clinic_lat)) * sin(d_lon / 2) ** 2
    return earth_radius_km * 2 * asin(sqrt(a))


def nearest_vets(lat: float, lon: float, limit: int = 5) -> list[dict]:
    results = []
    for clinic in VET_CLINICS:
        item = {**clinic, "distanceKm": round(distance_km(lat, lon, clinic["lat"], clinic["lon"]), 3)}
        item["mapUrl"] = f"https://www.google.com/maps/search/?api=1&query={clinic['lat']},{clinic['lon']}"
        results.append(item)
    return sorted(results, key=lambda item: item["distanceKm"])[:limit]
