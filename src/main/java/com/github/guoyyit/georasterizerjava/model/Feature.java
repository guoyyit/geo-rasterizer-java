package com.github.guoyyit.georasterizerjava.model;

/**
 * 待栅格化要素：几何 + 聚合属性值 + 优先级（PRIORITY 合并时使用）。
 */
public record Feature(String id, Polygon polygon, double value, int priority) {

    public Feature {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("feature id must not be blank");
        }
        if (polygon == null) {
            throw new IllegalArgumentException("feature polygon must not be null");
        }
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("feature value must not be NaN");
        }
    }
}
