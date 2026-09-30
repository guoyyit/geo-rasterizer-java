package com.github.guoyyit.georasterizerjava.model;

import java.io.Serializable;
import java.util.List;

/**
 * One exterior ring plus zero or more hole rings. The active fill rule
 * decides which regions are interior.
 */
public record Polygon(Ring exterior, List<Ring> holes) implements Serializable {
    public Polygon {
        holes = List.copyOf(holes);
    }
}
