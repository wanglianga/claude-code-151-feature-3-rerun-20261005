package com.example.library.web.dto;

import java.time.Instant;
import java.util.List;

/** 一次新增复本到馆登记 + 自动平衡分配的结果 */
public record AcquireResultView(
        Long bibId,
        String title,
        String libraryCode,
        int count,
        List<String> barcodes,
        List<AssignmentView> assignments,
        Instant processedAt
) {}
