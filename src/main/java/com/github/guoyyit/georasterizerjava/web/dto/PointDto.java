package com.github.guoyyit.georasterizerjava.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 坐标点 JSON 表示，同时支持 {@code [x, y]} 数组与 {@code {"x":..,"y":..}} 对象。
 */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
public record PointDto(double x, double y) {
}
