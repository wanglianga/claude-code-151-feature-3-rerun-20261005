package com.example.library.web.dto;

import java.time.Instant;

/** 馆藏流转记录视图（新增到馆 / 自动分配 / 馆员人工改配的审计留痕） */
public record CollectionEventView(
        Long id,
        Long bibId,
        String bibTitle,
        String itemBarcode,
        Long holdId,
        String readerCardNo,
        String fromLibraryCode,
        String toLibraryCode,
        String eventType,
        String allocMode,
        String staff,
        String reason,
        String detail,
        Instant createdAt
) {}
