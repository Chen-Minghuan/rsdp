package com.rsdp.floorplan.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rsdp.exception.BusinessException;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CadFloorPlanParser} 单元测试，使用 WireMock 模拟 rsdp-cad-parser 服务
 * （POST /parse）：成功解析 / 解析失败（success=false，HTTP 422 透传中文错误）/ 服务不可达。
 */
class CadFloorPlanParserTest {

    private WireMockServer wireMockServer;
    private CadFloorPlanParser parser;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        configureFor(wireMockServer.port());

        RestClient restClient = RestClient.builder()
            .baseUrl(wireMockServer.baseUrl())
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();
        parser = new CadFloorPlanParser(restClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void supports_shouldOnlyAcceptCad() {
        assertThat(parser.supports(FloorPlanFileType.CAD)).isTrue();
        assertThat(parser.supports(FloorPlanFileType.IMAGE)).isFalse();
        assertThat(parser.supports(FloorPlanFileType.PDF)).isFalse();
    }

    @Test
    void parse_success_shouldMapCadParseResult() {
        stubFor(post(urlEqualTo("/parse"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "success": true,
                      "units": "mm",
                      "drawingBounds": {"minX": 0.0, "minY": 0.0, "maxX": 10000.0, "maxY": 8000.0},
                      "preview": {
                        "format": "png", "width": 1000, "height": 800,
                        "bounds": {"minX": 0.0, "minY": 0.0, "maxX": 10000.0, "maxY": 8000.0},
                        "pngBase64": "iVBORw0KGgo="
                      },
                      "rooms": [{
                        "label": "主卧",
                        "roomType": "BEDROOM",
                        "polygon": [[1000.0, 1000.0], [5200.0, 1000.0], [5200.0, 4800.0], [1000.0, 4800.0]],
                        "widthMm": 4200.0,
                        "depthMm": 3800.0,
                        "areaM2": 15.96,
                        "dimensionSource": "cad_geometry",
                        "dimensionCheck": {"annotated": "4200×3800", "consistent": true},
                        "confidence": "high"
                      }],
                      "unnamedRegions": [{
                        "polygon": [[6000.0, 1000.0], [7000.0, 1000.0], [7000.0, 2000.0]],
                        "areaM2": 1.0,
                        "hint": "距 主卧 1.2m"
                      }],
                      "qualityIssues": [{"level": "warn", "code": "UNIT_ASSUMED", "message": "图纸未声明单位，按毫米处理"}]
                    }
                    """)));

        FloorPlanParseResult result = parser.parse(
            new FloorPlanParseRequest("fake-dwg".getBytes(), "户型图.dwg", null));

        assertThat(result.isCad()).isTrue();
        CadParseResult cad = result.cadResult();
        assertThat(cad.isSuccess()).isTrue();
        assertThat(cad.getDrawingBounds().getMaxX()).isEqualTo(10000.0);
        assertThat(cad.getRooms()).hasSize(1);
        CadParseResult.Room room = cad.getRooms().get(0);
        assertThat(room.getLabel()).isEqualTo("主卧");
        assertThat(room.getRoomType()).isEqualTo("BEDROOM");
        assertThat(room.getPolygon()).hasSize(4);
        assertThat(room.getPolygon().get(1)).containsExactly(5200.0, 1000.0);
        assertThat(room.getWidthMm()).isEqualTo(4200.0);
        assertThat(room.getDimensionCheck().getAnnotated()).isEqualTo("4200×3800");
        assertThat(room.getConfidence()).isEqualTo("high");
        assertThat(cad.getUnnamedRegions()).hasSize(1);
        assertThat(cad.getUnnamedRegions().get(0).getHint()).isEqualTo("距 主卧 1.2m");
        assertThat(cad.getQualityIssues()).hasSize(1);
        assertThat(cad.getQualityIssues().get(0).getCode()).isEqualTo("UNIT_ASSUMED");
        assertThat(result.previewBytes()).containsExactly(
            (byte) 137, (byte) 80, (byte) 78, (byte) 71,
            (byte) 13, (byte) 10, (byte) 26, (byte) 10);
        assertThat(cad.getPreview().getPngBase64()).isNull();
        assertThat(cad.getPreview().getBounds().getMaxX()).isEqualTo(10000.0);

        // 请求为 multipart 且携带文件内容
        String requestBody = wireMockServer.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(requestBody).contains("fake-dwg").contains("name=\"file\"");
    }

    @Test
    void parse_success_shouldMapDrawingBounds() {
        stubFor(post(urlEqualTo("/parse"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "success": true,
                      "units": "mm",
                      "drawingBounds": {"minX": -500.0, "minY": -200.0, "maxX": 12345.5, "maxY": 8765.5},
                      "rooms": [],
                      "unnamedRegions": [],
                      "qualityIssues": []
                    }
                    """)));

        FloorPlanParseResult result = parser.parse(
            new FloorPlanParseRequest("fake-dwg".getBytes(), "户型图.dwg", null));

        // drawingBounds（图纸毫米坐标系范围）完整映射，供响应透出（前端底图叠加归一化参照）
        CadParseResult.Bounds bounds = result.cadResult().getDrawingBounds();
        assertThat(bounds).isNotNull();
        assertThat(bounds.getMinX()).isEqualTo(-500.0);
        assertThat(bounds.getMinY()).isEqualTo(-200.0);
        assertThat(bounds.getMaxX()).isEqualTo(12345.5);
        assertThat(bounds.getMaxY()).isEqualTo(8765.5);
    }

    @Test
    void parse_parseFailed_shouldThrowChineseMessageFromServer() {
        stubFor(post(urlEqualTo("/parse"))
            .willReturn(aResponse()
                .withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"errorCode\":\"PARSE_FAILED\","
                    + "\"errorMessage\":\"未识别到任何房间标签，请确认图纸包含房间名标注\"}")));

        assertThatThrownBy(() -> parser.parse(new FloorPlanParseRequest("bad-dwg".getBytes(), "bad.dwg", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CAD 图纸解析失败")
            .hasMessageContaining("未识别到任何房间标签");
    }

    @Test
    void parse_serviceUnreachable_shouldThrowChineseMessage() {
        wireMockServer.stop();

        assertThatThrownBy(() -> parser.parse(new FloorPlanParseRequest("fake".getBytes(), "a.dwg", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CAD 解析服务连接失败");
    }
}
