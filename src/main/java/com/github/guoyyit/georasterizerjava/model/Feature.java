package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;

/**
 * A rasterizable feature. {@code weight} drives MAX aggregation;
 * {@code priority} (larger wins, ties broken by smaller feature id) drives
 * PRIORITY aggregation.
 */
public record Feature(long id, Polygon polygon, double weight, int priority) implements Serializable {
}
