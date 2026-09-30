package com.github.guoyyit.georasterizerjava.raster;

/**
 * Unrecoverable rasterization failure (invalid config or I/O error).
 */
public class RasterizationException extends RuntimeException {

    public RasterizationException(String message) {
        super(message);
    }

    public RasterizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
