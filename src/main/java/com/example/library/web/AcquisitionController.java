package com.example.library.web;

import com.example.library.service.AcquisitionService;
import com.example.library.service.DemandAdvisor;
import com.example.library.web.dto.AcquireCopiesRequest;
import com.example.library.web.dto.AcquireResultView;
import com.example.library.web.dto.AssignmentView;
import com.example.library.web.dto.CollectionEventView;
import com.example.library.web.dto.ManualAdjustRequest;
import com.example.library.web.dto.admin.DemandAdviceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/acquisitions")
@Tag(name = "11. 热门书采购与复本调配",
        description = "按等待人数/复本/续借/调拨成本给出采购与暂停续借建议；新增复本到馆按预约时间与各分馆等待均衡分配；馆员可人工改配（原因入馆藏流转记录）")
public class AcquisitionController {

    private final DemandAdvisor demand;
    private final AcquisitionService acquisitions;

    public AcquisitionController(DemandAdvisor demand, AcquisitionService acquisitions) {
        this.demand = demand;
        this.acquisitions = acquisitions;
    }

    @GetMapping("/demand/hot")
    @Operation(summary = "热门书目采购/续借建议列表（未满足等待达到 acquire.queueThreshold）")
    public List<DemandAdviceView> hot() {
        return demand.adviseHot();
    }

    @GetMapping("/demand/bibs/{bibId}")
    @Operation(summary = "单部书目的采购/续借建议与新增复本到馆分馆摊分")
    public DemandAdviceView advice(@PathVariable Long bibId) {
        return demand.advise(bibId);
    }

    @PostMapping("/copies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新增采购复本到馆登记：立即按预约时间与各分馆等待均衡分配，通知含预计取书窗口")
    public AcquireResultView acquire(@Valid @RequestBody AcquireCopiesRequest req) {
        return acquisitions.acquireCopies(req.bibId(), req.libraryCode(), req.count(),
                req.staff(), req.note());
    }

    @PostMapping("/manual-adjust")
    @Operation(summary = "馆员人工改配某复本给指定排队预约；调整原因必填并写入馆藏流转记录")
    public AssignmentView manualAdjust(@Valid @RequestBody ManualAdjustRequest req) {
        return acquisitions.manualAdjust(req.barcode(), req.targetHoldId(), req.staff(), req.reason());
    }

    @GetMapping("/events")
    @Operation(summary = "馆藏流转记录（新增到馆/自动分配/人工改配审计留痕）")
    public List<CollectionEventView> events() {
        return acquisitions.allEvents();
    }

    @GetMapping("/events/bibs/{bibId}")
    @Operation(summary = "按书目查看馆藏流转记录")
    public List<CollectionEventView> bibEvents(@PathVariable Long bibId) {
        return acquisitions.eventsForBib(bibId);
    }

    @GetMapping("/events/items/{barcode}")
    @Operation(summary = "按复本条码查看馆藏流转记录")
    public List<CollectionEventView> itemEvents(@PathVariable String barcode) {
        return acquisitions.eventsForItem(barcode);
    }
}
