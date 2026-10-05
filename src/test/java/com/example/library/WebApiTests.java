package com.example.library;

import com.example.library.service.AppClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REST 层冒烟：检索、预约全链路、参数校验、管理端、OpenAPI/Swagger。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebApiTests {

    @Autowired MockMvc mvc;
    @Autowired AppClock clock;

    @Test
    void catalogSearchAndSwaggerAreAvailable() throws Exception {
        mvc.perform(get("/api/catalog/search")
                        .param("keyword", "三体")
                        .param("pickupLibraryCode", "WEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("三体"))
                .andExpect(jsonPath("$[0].libraries[?(@.code=='EAST')].transferable")
                        .value(org.hamcrest.Matchers.hasItem(true)));

        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void holdToCheckoutFlowOverHttp_rulesAndStats() throws Exception {
        clock.setFixed(Instant.parse("2026-10-03T08:00:00Z"));

        // 预约（参数缺失 → 400）
        mvc.perform(post("/api/holds/bibs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // R001 从城东跨馆预约到城西
        mvc.perform(post("/api/holds/bibs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readerCardNo":"R001","pickupLibraryCode":"WEST"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.itemBarcode").exists());

        // 物流出库 → 到馆
        var pending = mvc.perform(get("/api/transfers/tasks/pending")).andReturn();
        Number taskIdNum = com.jayway.jsonpath.JsonPath.read(pending.getResponse().getContentAsString(), "$[0].id");
        Long taskId = taskIdNum.longValue();

        mvc.perform(post("/api/transfers/pickups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskIds\":[" + taskId + "],\"staffName\":\"物流员小赵\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").exists());

        var afterPickup = mvc.perform(get("/api/transfers/tasks/" + taskId)).andReturn();
        String packageNo = com.jayway.jsonpath.JsonPath.read(
                afterPickup.getResponse().getContentAsString(), "$.packageNo");

        mvc.perform(post("/api/transfers/arrivals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageNo\":\"" + packageNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));

        // 读者通知与罚款账单接口
        mvc.perform(get("/api/notifications/readers/R001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("READY"));

        mvc.perform(get("/api/fines/readers/R001")).andExpect(status().isOk());

        // 管理端
        mvc.perform(get("/api/admin/dashboard").param("days", "7")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/batches")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].packages").value(1));
        mvc.perform(get("/api/admin/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.['fine.daily']").value(0.5));
    }

    @Test
    void acquisitionAdviceAcquireAndEventsOverHttp() throws Exception {
        // 单部书目采购建议（未达热门阈值也可查看，字段齐全）
        mvc.perform(get("/api/admin/acquisitions/demand/bibs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bibId").value(1))
                .andExpect(jsonPath("$.queueThreshold").value(3))
                .andExpect(jsonPath("$.targetLibraries").isArray());

        // 参数缺失 → 400
        mvc.perform(post("/api/admin/acquisitions/copies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bibId\":2}"))
                .andExpect(status().isBadRequest());

        // 红楼梦无人排队：在城北新增 1 册 → 直接上架 SHELVED
        mvc.perform(post("/api/admin/acquisitions/copies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bibId":2,"libraryCode":"NORTH","count":1,"staff":"采购馆员小陈","note":"热门书加采"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.assignments[0].mode").value("SHELVED"))
                .andExpect(jsonPath("$.assignments[0].toLibraryCode").value("NORTH"));

        // 流转记录：含本次 ACQUIRE
        mvc.perform(get("/api/admin/acquisitions/events/bibs/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("ACQUIRE"))
                .andExpect(jsonPath("$[0].staff").value("采购馆员小陈"));

        // 热门建议列表可访问
        mvc.perform(get("/api/admin/acquisitions/demand/hot")).andExpect(status().isOk());
    }
}
