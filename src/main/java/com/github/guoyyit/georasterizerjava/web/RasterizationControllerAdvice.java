package com.github.guoyyit.georasterizerjava.web;

import com.github.guoyyit.georasterizerjava.service.AreaConservationException;
import com.github.guoyyit.georasterizerjava.service.GeometryRejectedException;
import com.github.guoyyit.georasterizerjava.service.RasterizationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 栅格化错误到 HTTP 响应的显式映射：
 * 面积守恒失败与越界拒绝都必须显式报告，绝不静默返回错误覆盖图。
 */
@RestControllerAdvice
public class RasterizationControllerAdvice {

    @ExceptionHandler(AreaConservationException.class)
    public ResponseEntity<Map<String, Object>> handleAreaFailure(AreaConservationException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "AREA_CONSERVATION_FAILED",
                "message", exception.getMessage(),
                "report", Map.of(
                        "theoreticalArea", exception.report().theoreticalArea(),
                        "rasterizedArea", exception.report().rasterizedArea(),
                        "absoluteDeviation", exception.report().absoluteDeviation(),
                        "relativeDeviation", exception.report().relativeDeviation(),
                        "tolerance", exception.report().tolerance())));
    }

    @ExceptionHandler(GeometryRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(GeometryRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "GEOMETRY_OUT_OF_RANGE_REJECTED",
                "featureId", exception.featureId(),
                "message", exception.getMessage()));
    }

    @ExceptionHandler(RasterizationException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(RasterizationException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "RASTERIZATION_INVALID_REQUEST",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "RASTERIZATION_INVALID_REQUEST",
                "message", exception.getMessage()));
    }
}
