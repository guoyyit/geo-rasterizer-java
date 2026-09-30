package com.github.guoyyit.georasterizerjava.web;

import com.github.guoyyit.georasterizerjava.raster.GridDefinition;
import com.github.guoyyit.georasterizerjava.service.RasterizationResult;
import com.github.guoyyit.georasterizerjava.service.RasterizationService;
import com.github.guoyyit.georasterizerjava.web.dto.RasterizeRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 栅格化 REST 接口。
 */
@RestController
public class RasterController {

    private final RasterizationService service = new RasterizationService();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "geo-rasterizer");
    }

    /**
     * 同步栅格化：返回覆盖率矩阵 coverage[行][列]、聚合值矩阵 values、获胜要素 id 矩阵、
     * 面积报告与处理元信息。行自下而上编号，列自左而右编号，与请求 outputEnvelope 对齐。
     */
    @PostMapping("/api/rasterize")
    public Map<String, Object> rasterize(@RequestBody RasterizeRequest request) {
        var config = RasterizationServiceMapper.toConfig(request);
        var features = RasterizationServiceMapper.toFeatures(request.features());
        RasterizationResult result = service.rasterize(
                features, config, RasterizationServiceMapper.toWorkDir(request.workDir()));

        GridDefinition grid = result.grid();
        double[][] coverage = new double[grid.rows()][grid.cols()];
        double[][] values = new double[grid.rows()][grid.cols()];
        String[][] winners = new String[grid.rows()][grid.cols()];
        for (int row = 0; row < grid.rows(); row++) {
            for (int col = 0; col < grid.cols(); col++) {
                coverage[row][col] = result.coverageAt(col, row);
                values[row][col] = result.valueAt(col, row);
                winners[row][col] = result.winnerAt(col, row);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("grid", Map.of(
                "cols", grid.cols(),
                "rows", grid.rows(),
                "cellSize", grid.cellSize(),
                "origin", Map.of("x", grid.envelope().minX(), "y", grid.envelope().minY()),
                "axisConvention", "rows bottom-to-top, columns left-to-right"));
        response.put("coverage", coverage);
        response.put("values", values);
        response.put("winnerFeatureIds", winners);
        response.put("areaConservation", result.metadata().get("areaConservation"));
        response.put("metadata", result.metadata());
        return response;
    }
}
