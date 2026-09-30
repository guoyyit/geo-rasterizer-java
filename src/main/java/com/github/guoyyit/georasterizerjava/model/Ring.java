package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;
import java.util.List;

/**
 * A closed ring stored without an explicit duplicated closing point.
 * May self-intersect or contain repeated vertices.
 */
public record Ring(List<Point> vertices) implements Serializable {
    public Ring {
        vertices = List.copyOf(vertices);
    }
}
