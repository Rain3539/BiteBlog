package com.biteblog.location.service;

import com.biteblog.common.result.ErrorCode;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.location.dto.NearbyMarkerVO;
import com.biteblog.location.dto.PoiItemVO;
import com.biteblog.location.entity.Note;
import com.biteblog.location.mapper.NoteMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {
    private final NoteMapper noteMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${amap.api-key}")
    private String amapApiKey;

    @Value("${amap.base-url}")
    private String amapBaseUrl;

    private static final String GEO_KEY = "location:notes";
    private static final String POI_CACHE_PREFIX = "location:poi:";

    public List<NearbyMarkerVO> nearbyMarkers(double longitude, double latitude, int radius) {
        if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new BusinessException(ErrorCode.COORDINATE_INVALID);
        }

        Circle circle = new Circle(new Point(longitude, latitude),
                new Distance(radius, Metrics.KILOMETERS));
        GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()
                .includeCoordinates()
                .sortAscending()
                .limit(50);

        GeoResults<GeoLocation<Object>> results = redisTemplate.opsForGeo()
                .radius(GEO_KEY, circle, args);

        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }

        List<Long> ids = results.getContent().stream()
                .map(r -> {
                    Object member = r.getContent().getName();
                    return Long.valueOf(String.valueOf(member));
                })
                .collect(Collectors.toList());

        Map<Long, Note> noteMap = noteMapper.selectBatchIds(ids).stream()
                .filter(n -> Objects.equals(n.getStatus(), 1))
                .collect(Collectors.toMap(Note::getId, n -> n));

        List<NearbyMarkerVO> markers = new ArrayList<>();
        for (GeoResult<GeoLocation<Object>> result : results.getContent()) {
            Long noteId = Long.valueOf(String.valueOf(result.getContent().getName()));
            Note note = noteMap.get(noteId);
            if (note == null) {
                continue;
            }
            NearbyMarkerVO vo = new NearbyMarkerVO();
            vo.setNoteId(note.getId());
            vo.setAuthorId(note.getAuthorId());
            vo.setTitle(note.getTitle());
            vo.setShopName(note.getShopName());
            vo.setLongitude(note.getLongitude().doubleValue());
            vo.setLatitude(note.getLatitude().doubleValue());
            vo.setDistance(result.getDistance().getValue());
            markers.add(vo);
        }
        return markers;
    }

    public List<PoiItemVO> searchPoi(String keyword, String city) {
        String cacheKey = POI_CACHE_PREFIX + keyword + ":" + (city != null ? city : "");

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached instanceof List<?> list) {
                    List<PoiItemVO> result = new ArrayList<>();
                    for (Object item : list) {
                        result.add(objectMapper.convertValue(item, PoiItemVO.class));
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read POI cache: {}", e.getMessage());
        }

        WebClient webClient = WebClient.create(amapBaseUrl);
        String response;
        try {
            response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v3/place/text")
                            .queryParam("key", amapApiKey)
                            .queryParam("keywords", keyword)
                            .queryParam("city", city != null ? city : "")
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to call Amap API: {}", e.getMessage());
            throw new BusinessException(ErrorCode.POI_SEARCH_FAIL);
        }

        List<PoiItemVO> pois = parsePoiResponse(response);
        redisTemplate.opsForValue().set(cacheKey, pois, 1, TimeUnit.HOURS);
        return pois;
    }

    public void addNoteLocation(Long noteId) {
        if (noteId == null) {
            return;
        }
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            log.warn("Note not found for location add: noteId={}", noteId);
            return;
        }
        if (note.getLongitude() == null || note.getLatitude() == null) {
            log.info("Note has no coordinates, skipping GEO add: noteId={}", noteId);
            return;
        }
        Point point = new Point(note.getLongitude().doubleValue(), note.getLatitude().doubleValue());
        redisTemplate.opsForGeo().add(GEO_KEY, point, noteId.toString());
        log.info("Added note location to GEO: noteId={}, lng={}, lat={}",
                noteId, note.getLongitude(), note.getLatitude());
    }

    private List<PoiItemVO> parsePoiResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText();
            if (!"1".equals(status)) {
                log.error("Amap API returned status {}", status);
                throw new BusinessException(ErrorCode.POI_SEARCH_FAIL);
            }
            JsonNode poisNode = root.path("pois");
            List<PoiItemVO> pois = new ArrayList<>();
            for (JsonNode poi : poisNode) {
                PoiItemVO vo = new PoiItemVO();
                vo.setId(poi.path("id").asText());
                vo.setName(poi.path("name").asText());
                vo.setAddress(poi.path("address").asText());
                vo.setType(poi.path("type").asText());

                String location = poi.path("location").asText();
                if (location != null && location.contains(",")) {
                    String[] parts = location.split(",");
                    if (parts.length == 2) {
                        vo.setLongitude(Double.valueOf(parts[0]));
                        vo.setLatitude(Double.valueOf(parts[1]));
                    }
                }
                pois.add(vo);
            }
            return pois;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse POI response: {}", e.getMessage());
            throw new BusinessException(ErrorCode.POI_SEARCH_FAIL);
        }
    }
}
