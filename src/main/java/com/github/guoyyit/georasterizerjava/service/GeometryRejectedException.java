package com.github.guoyyit.georasterizerjava.service;

import com.github.guoyyit.georasterizerjava.model.Feature;

/**
 * REJECT 策略下要素超出有效输出范围，作业被显式拒绝。
 */
public class GeometryRejectedException extends RasterizationException {

    private final String featureId;

    public GeometryRejectedException(Feature feature) {
        super("feature " + feature.id() + " is outside the configured valid output envelope; "
                + "policy is REJECT");
        this.featureId = feature.id();
    }

    public String featureId() {
        return featureId;
    }
}
