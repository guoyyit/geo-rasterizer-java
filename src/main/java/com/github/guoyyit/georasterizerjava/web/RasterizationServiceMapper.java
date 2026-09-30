package com.github.guoyyit.georasterizerjava.web;

import com.github.guoyyit.georasterizerjava.model.Envelope;
import com.github.guoyyit.georasterizerjava.model.Feature;
import com.github.guoyyit.georasterizerjava.model.FillRule;
import com.github.guoyyit.georasterizerjava.model.LinearRing;
import com.github.guoyyit.georasterizerjava.model.MergeStrategy;
import com.github.guoyyit.georasterizerjava.model.OutOfRangePolicy;
import com.github.guoyyit.georasterizerjava.model.Point;
import com.github.guoyyit.georasterizerjava.model.Polygon;
import com.github.guoyyit.georasterizerjava.web.dto.FeatureDto;
import com.github.guoyyit.georasterizerjava.web.dto.PointDto;
import com.github.guoyyit.georasterizerjava.web.dto.PolygonDto;
import com.github.guoyyit.georasterizerjava.web.dto.RasterizeRequest;
import com.github.guoyyit.georasterizerjava.config.RasterConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 请求 DTO 到领域模型 / 配置的确定性映射。
 */
final class RasterizationServiceMapper {

    private RasterizationServiceMapper() {
    }

    static RasterConfig toConfig(RasterizeRequest request) {
        RasterConfig.Builder builder = RasterConfig.builder(request.cellSize());
        if (request.outputEnvelope() != null) {
            if (request.outputEnvelope().length != 4) {
                throw new IllegalArgumentException(
                        "outputEnvelope must be [minX, minY, maxX, maxY]");
            }
            double[] envelope = request.outputEnvelope();
            builder.outputEnvelope(new Envelope(envelope[0], envelope[1], envelope[2], envelope[3]));
        }
        if (request.fillRule() != null) {
            builder.fillRule(FillRule.valueOf(request.fillRule()));
        }
        if (request.mergeStrategy() != null) {
            builder.mergeStrategy(MergeStrategy.valueOf(request.mergeStrategy()));
        }
        if (request.outOfRangePolicy() != null) {
            builder.outOfRangePolicy(OutOfRangePolicy.valueOf(request.outOfRangePolicy()));
        }
        if (request.areaTolerance() != null) {
            builder.areaTolerance(request.areaTolerance());
        }
        if (request.tileCols() != null && request.tileRows() != null) {
            builder.tileSize(request.tileCols(), request.tileRows());
        }
        if (request.parallelism() != null) {
            builder.parallelism(request.parallelism());
        }
        return builder.build();
    }

    static List<Feature> toFeatures(List<FeatureDto> featureDtos) {
        if (featureDtos == null || featureDtos.isEmpty()) {
            throw new IllegalArgumentException("features must not be empty");
        }
        List<Feature> features = new ArrayList<>(featureDtos.size());
        for (FeatureDto dto : featureDtos) {
            features.add(new Feature(dto.id(), toPolygon(dto.polygon()), dto.value(),
                    dto.priority() == null ? 0 : dto.priority()));
        }
        return features;
    }

    static Polygon toPolygon(PolygonDto dto) {
        if (dto == null || dto.exterior() == null) {
            throw new IllegalArgumentException("feature polygon exterior must not be null");
        }
        List<LinearRing> holes = new ArrayList<>();
        if (dto.holes() != null) {
            for (List<PointDto> hole : dto.holes()) {
                holes.add(new LinearRing(toPoints(hole)));
            }
        }
        return new Polygon(new LinearRing(toPoints(dto.exterior())), holes);
    }

    static List<Point> toPoints(List<PointDto> pointDtos) {
        if (pointDtos == null || pointDtos.size() < 3) {
            throw new IllegalArgumentException("ring must contain at least 3 distinct points");
        }
        List<Point> points = new ArrayList<>(pointDtos.size());
        for (PointDto pointDto : pointDtos) {
            points.add(new Point(pointDto.x(), pointDto.y()));
        }
        return points;
    }

    static Path toWorkDir(String workDir) {
        return workDir == null || workDir.isBlank() ? null : Path.of(workDir);
    }
}
