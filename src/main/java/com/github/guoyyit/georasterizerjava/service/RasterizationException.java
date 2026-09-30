package com.github.guoyyit.georasterizerjava.service;

/**
 * 栅格化作业通用异常（非法配置、几何被拒绝等）。
 */
public class RasterizationException extends RuntimeException {

    public RasterizationException(String message) {
        super(message);
    }

    public RasterizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
