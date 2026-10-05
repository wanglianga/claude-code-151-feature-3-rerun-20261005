package com.example.library.web.dto;

import java.time.Instant;

/** 一册复本的分配结果（自动分配 / 馆员改配通用） */
public record AssignmentView(
        Long holdId,
        String readerCardNo,
        String itemBarcode,
        /** LOCAL 本馆即上架待取 / TRANSFER 跨馆调拨中 / SHELVED 无人排队已上架可借 */
        String mode,
        String fromLibraryCode,
        String toLibraryCode,
        Instant estimatedReadyAt,
        Instant pickupWindowEndsAt
) {}
