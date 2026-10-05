package com.example.library.web.dto.admin;

import java.time.Instant;
import java.util.List;

/**
 * 热门书采购/续借建议（单部书目）。
 * 综合未满足等待人数、复本数量与分布、在借续借受阻、跨馆调拨成本给出：
 * 是否建议新增采购及册数、是否建议暂停续借、新增复本建议到馆的分馆分配。
 */
public record DemandAdviceView(
        Long bibId,
        String isbn,
        String title,
        String author,
        boolean ebookAvailable,
        long waitingReaders,
        long unassignedReaders,
        long copiesTotal,
        long copiesAvailable,
        long copiesInTransit,
        long activeLoans,
        long renewalBlockedLoans,
        long queueThreshold,
        boolean hot,
        boolean recommendPurchase,
        int recommendedCopies,
        boolean recommendSuspendRenewal,
        /** 建议新增复本到馆分馆分配（按等待人数/等待时长摊分，避免全部流向总馆） */
        List<LibraryTarget> targetLibraries,
        /** 建议理由（等待人数/复本/续借/调拨成本），可直接向馆员解释 */
        List<String> reasons,
        Instant generatedAt
) {
    /** 单个分馆的等待需求与建议新增册数 */
    public record LibraryTarget(
            String libraryCode,
            String libraryName,
            long waitingReaders,
            long longestWaitHours,
            long copiesAvailableNow,
            int recommendedNewCopies,
            /** 从现有在架他馆调拨到该分馆的最快在途小时；无任何可调在架复本时为 null（只能新采购） */
            Long transferEtaHours,
            String note
    ) {}
}
