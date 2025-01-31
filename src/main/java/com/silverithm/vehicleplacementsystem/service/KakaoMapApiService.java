package com.silverithm.vehicleplacementsystem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silverithm.vehicleplacementsystem.dto.KakaoMapApiResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.Location;
import com.silverithm.vehicleplacementsystem.dto.OsrmApiResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@EnableCaching
public class KakaoMapApiService {

    private final OsrmService osrmService;
    @Value("${kakao.key}")
    private String kakaoKey;

    public KakaoMapApiService(OsrmService osrmService) {
        this.osrmService = osrmService;
    }

    @Cacheable(
            value = "kakaomap",
            key = "#startAddress.latitude + ':' + #startAddress.longitude + ':' + #destAddress.latitude + ':' + #destAddress.longitude",
            unless = "#result == null"
    )
    public KakaoMapApiResponseDTO getDistanceTotalTimeWithKakaoMapApi(Location startAddress,
                                                                      Location destAddress)
            throws NullPointerException {

        int distance = 0;
        int duration = 0;

        try {
            RestTemplate restTemplate = new RestTemplate();

            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoKey);

            // HTTP 엔터티 생성 (헤더 포함)
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 파라미터 설정
            String origin = startAddress.getLongitude() + "," + startAddress.getLatitude();
            String destination = destAddress.getLongitude() + "," + destAddress.getLatitude();
            String departureTime = "202501311800"; // 2025년 1월 31일 오후 6시
            boolean alternatives = true; // 대안경로 요청

            // URL에 파라미터 추가
            String url = String.format(
                    "https://apis-navi.kakaomobility.com/v1/future/directions?origin=%s&destination=%s&departure_time=%s&alternatives=%s",
                    origin, destination, departureTime, alternatives
            );

            // GET 요청 보내기
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            // JSON 파싱을 위한 ObjectMapper
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            // result_code가 104이면 0 반환
            if (root.has("result_code") && root.get("result_code").asInt() == 104) {
                log.info("Kakao Map API: 출발지와 도착지가 너무 가까움");
                return new KakaoMapApiResponseDTO(0, 0);
            }

            // routes[0].summary에서 duration과 distance 추출 및 안전한 파싱
            JsonNode summary = root.path("routes").path(0).path("summary");

            // duration 파싱
            String durationStr = summary.path("duration").asText("");
            if (!durationStr.isEmpty()) {
                try {
                    duration = Integer.parseInt(durationStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid duration value from API: {}", durationStr);
                }
            }

            // distance 파싱
            String distanceStr = summary.path("distance").asText("");
            if (!distanceStr.isEmpty()) {
                try {
                    distance = Integer.parseInt(distanceStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid distance value from API: {}", distanceStr);
                }
            }

        } catch (Exception e) {
            log.error("KAKAOMAP API 요청 실패", e);

            try {
                OsrmApiResponseDTO osrmApiResponseDTO = osrmService.getDistanceTotalTimeWithOsrmApi(startAddress,
                        destAddress);
                distance = osrmApiResponseDTO.distance();
                duration = osrmApiResponseDTO.duration();

            } catch (Exception e2) {
                throw new NullPointerException("[ERROR] 길찾기 API 요청에 실패하였습니다.");
            }
        }

        return new KakaoMapApiResponseDTO(distance, duration);
    }
}
