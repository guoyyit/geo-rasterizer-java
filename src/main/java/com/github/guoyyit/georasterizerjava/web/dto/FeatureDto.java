package com.github.guoyyit.georasterizerjava.web.dto;

/**
 * 要素 JSON 表示。
 */
public record FeatureDto(String id, PolygonDto polygon, double value, Integer priority) {
}
