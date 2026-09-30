package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;

/**
 * Immutable planar coordinate.
 */
public record Point(double x, double y) implements Serializable {
}
