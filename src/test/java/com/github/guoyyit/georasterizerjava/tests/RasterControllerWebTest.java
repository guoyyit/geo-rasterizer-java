package com.github.guoyyit.georasterizerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口：健康检查、栅格化成功路径、越界拒绝与面积失败的显式错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RasterControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void rasterizeReturnsCoverageGridAndMetadata() throws Exception {
        String request = """
                {
                  "cellSize": 1.0,
                  "outputEnvelope": [0, 0, 4, 4],
                  "fillRule": "WINDING",
                  "mergeStrategy": "COUNT",
                  "outOfRangePolicy": "CLIP",
                  "tileCols": 2,
                  "tileRows": 2,
                  "parallelism": 2,
                  "features": [
                    {
                      "id": "square",
                      "value": 7.0,
                      "priority": 0,
                      "polygon": {
                        "exterior": [[0,0],[4,0],[4,4],[0,4]],
                        "holes": []
                      }
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/api/rasterize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grid.cols").value(4))
                .andExpect(jsonPath("$.grid.rows").value(4))
                .andExpect(jsonPath("$.areaConservation.passed").value(true))
                .andExpect(jsonPath("$.metadata.fillRule").value("WINDING"))
                .andExpect(jsonPath("$.metadata.outOfRangePolicy").value("CLIP"));
    }

    @Test
    void rejectsOutOfRangeGeometryExplicitly() throws Exception {
        String request = """
                {
                  "cellSize": 1.0,
                  "outputEnvelope": [0, 0, 3, 3],
                  "outOfRangePolicy": "REJECT",
                  "features": [
                    {
                      "id": "big",
                      "value": 1.0,
                      "polygon": {
                        "exterior": [[0,0],[4,0],[4,4],[0,4]],
                        "holes": []
                      }
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/api/rasterize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GEOMETRY_OUT_OF_RANGE_REJECTED"))
                .andExpect(jsonPath("$.featureId").value("big"));
    }

    @Test
    void invalidEnumReturnsBadRequest() throws Exception {
        String request = """
                {
                  "cellSize": 1.0,
                  "fillRule": "NOT_A_RULE",
                  "features": [
                    {
                      "id": "f",
                      "value": 1.0,
                      "polygon": {
                        "exterior": [[0,0],[1,0],[1,1]],
                        "holes": []
                      }
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/api/rasterize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }
}
