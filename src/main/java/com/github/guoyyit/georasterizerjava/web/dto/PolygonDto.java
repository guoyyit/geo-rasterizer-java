package com.github.guoyyit.georasterizerjava.web.dto;

import java.util.List;

/**
 * 多边形 JSON 表示：外环 + 孔洞环；每个环为坐标点数组（首尾点无需重复）。
 */
public record PolygonDto(List<PointDto> exterior, List<List<PointDto>> holes) {
}
