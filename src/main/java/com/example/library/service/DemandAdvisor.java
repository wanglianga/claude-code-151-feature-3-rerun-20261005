package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.admin.DemandAdviceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 热门书采购/续借建议。
 *
 * 输入信号：
 * - 等待人数：未分配到复本的排队预约（QUEUED 且 item 为空），按取书分馆聚合，并取各馆最长等待时长；
 * - 复本数量：在架可借 / 在途 / 在借分布，以及外馆保护下是否还能从他馆调拨；
 * - 续借限制：在借复本中已达续借上限、以及因排队被禁止续借的数量（热门书不被长期占用）；
 * - 跨馆调拨成本：最快在途 ETA（同/跨片区），ETA 越长越倾向就近新增采购而非长链路调拨。
 *
 * 产出：是否热门、建议新增采购册数与到馆分馆摊分（避免全部流向总馆）、
 * 是否建议暂停续借，以及可直接向馆员解释的理由。
 */
@Service
public class DemandAdvisor {

    private final BibRepository bibs;
    private final HoldRepository holds;
    private final ItemRepository items;
    private final LoanRepository loans;
    private final LibraryRepository libraries;
    private final PolicyService policy;
    private final CatalogService catalog;
    private final AppClock clock;

    public DemandAdvisor(BibRepository bibs, HoldRepository holds, ItemRepository items,
                         LoanRepository loans, LibraryRepository libraries, PolicyService policy,
                         CatalogService catalog, AppClock clock) {
        this.bibs = bibs;
        this.holds = holds;
        this.items = items;
        this.loans = loans;
        this.libraries = libraries;
        this.policy = policy;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DemandAdviceView advise(Long bibId) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        return build(bib);
    }

    /** 列出当前所有热门（等待人数达阈值）书目的建议，可按热度排序 */
    @Transactional(readOnly = true)
    public List<DemandAdviceView> adviseHot() {
        int threshold = policy.acquireQueueThreshold();
        List<DemandAdviceView> all = new ArrayList<>();
        for (Bib bib : bibs.findAll()) {
            DemandAdviceView v = build(bib);
            if (v.unassignedReaders() >= threshold) {
                all.add(v);
            }
        }
        all.sort(Comparator.comparingLong(DemandAdviceView::unassignedReaders)
                .thenComparingLong(DemandAdviceView::waitingReaders).reversed());
        return all;
    }

    private DemandAdviceView build(Bib bib) {
        Instant now = clock.now();
        List<Hold> active = holds.findActiveByBib(bib.getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        List<Hold> unassignedHolds = active.stream()
                .filter(h -> h.getStatus() == HoldStatus.QUEUED && h.getItem() == null)
                .toList();

        List<Item> bibItems = items.findAll().stream()
                .filter(i -> i.getBib().getId().equals(bib.getId())).toList();
        long copiesTotal = bibItems.stream()
                .filter(i -> i.getStatus() != ItemStatus.LOST && i.getStatus() != ItemStatus.WITHDRAWN)
                .count();
        long copiesAvailable = bibItems.stream().filter(i -> i.getStatus() == ItemStatus.AVAILABLE).count();
        long copiesInTransit = bibItems.stream()
                .filter(i -> i.getStatus() == ItemStatus.LOCKED || i.getStatus() == ItemStatus.IN_TRANSIT)
                .count();

        List<Loan> bibLoans = loans.findByStatusIn(List.of(LoanStatus.ACTIVE)).stream()
                .filter(l -> l.getItem().getBib().getId().equals(bib.getId())).toList();
        long activeLoans = bibLoans.size();
        long renewalBlocked = bibLoans.stream()
                .filter(l -> l.getRenewCount() >= policy.renewLimit() || !active.isEmpty())
                .count();

        int threshold = policy.acquireQueueThreshold();
        long unassigned = unassignedHolds.size();
        boolean hot = unassigned >= threshold;

        // 外馆保护下当前还能立即调出的在架复本
        boolean anyTransferableNow = false;
        for (Library src : libraries.findAll()) {
            long avail = items.countByBibIdAndCurrentLibraryIdAndStatus(
                    bib.getId(), src.getId(), ItemStatus.AVAILABLE);
            if (avail > 0 && catalog.protectionAllows(bib.getId(), src, avail)) {
                anyTransferableNow = true;
                break;
            }
        }

        // 分馆等待需求
        Map<Long, LibraryDemand> byLib = new LinkedHashMap<>();
        for (Hold h : unassignedHolds) {
            Library lib = h.getPickupLibrary();
            LibraryDemand d = byLib.computeIfAbsent(lib.getId(), k -> new LibraryDemand(lib));
            d.waiting++;
            long waited = Duration.between(h.getCreatedAt(), now).toHours();
            d.longestWaitHours = Math.max(d.longestWaitHours, waited);
        }
        List<LibraryDemand> demands = new ArrayList<>(byLib.values());
        demands.sort(Comparator.comparingLong((LibraryDemand d) -> d.longestWaitHours).reversed()
                .thenComparingLong(d -> d.waiting).reversed());

        List<String> reasons = new ArrayList<>();
        reasons.add("未满足等待 " + unassigned + " 人（热门阈值 " + threshold + " 人）");
        reasons.add("在架可借 " + copiesAvailable + " 册 / 在途 " + copiesInTransit
                + " 册 / 在借 " + activeLoans + " 册，参与流转共 " + copiesTotal + " 册");

        boolean recommendPurchase;
        int recommendedCopies = 0;
        if (hot && copiesAvailable == 0 && !anyTransferableNow) {
            recommendPurchase = true;
            recommendedCopies = (int) unassigned;
            reasons.add("各分馆在架为 0 且外馆保护下无可立即调出复本，建议新增采购 "
                    + recommendedCopies + " 册");
        } else if (hot && !anyTransferableNow) {
            recommendPurchase = true;
            recommendedCopies = (int) unassigned;
            reasons.add("仅剩在架复本受外馆保护不可调出，建议新增采购 " + recommendedCopies
                    + " 册，避免抽空分馆");
        } else if (hot) {
            recommendPurchase = false;
            reasons.add("仍有受保护可调在架复本，优先跨馆调拨；若等待继续累积再采购");
        } else {
            recommendPurchase = false;
        }

        // 暂停续借建议：热门且仍有在借复本，靠禁止续借加速回流（系统已对有排队的在借自动禁续）
        boolean recommendSuspendRenewal = hot && activeLoans > 0;
        if (recommendSuspendRenewal) {
            reasons.add("当前 " + renewalBlocked + " 册在借因排队/续借上限不可续借，"
                    + "建议持续暂停该书续借以加速回流（系统已自动禁止有排队时续借）");
        }

        // 采购册数按各分馆等待人数摊分（最大余数法），至少覆盖有等待的分馆，避免全流向总馆
        List<DemandAdviceView.LibraryTarget> targets = buildTargets(
                bib, demands, recommendedCopies, reasons);

        return new DemandAdviceView(bib.getId(), bib.getIsbn(), bib.getTitle(), bib.getAuthor(),
                bib.isEbookAvailable(), active.size(), unassigned, copiesTotal, copiesAvailable,
                copiesInTransit, activeLoans, renewalBlocked, threshold, hot,
                recommendPurchase, recommendedCopies, recommendSuspendRenewal,
                targets, reasons, now);
    }

    private List<DemandAdviceView.LibraryTarget> buildTargets(
            Bib bib, List<LibraryDemand> demands, int totalCopies, List<String> reasons) {
        long totalWaiting = demands.stream().mapToLong(d -> d.waiting).sum();

        // 最大余数法按等待人数摊分，先每馆保底 1 册（有等待即摊到），再分剩余
        Map<Long, Integer> alloc = new LinkedHashMap<>();
        if (totalCopies > 0 && totalWaiting > 0) {
            int guaranteed = 0;
            for (LibraryDemand d : demands) {
                int floor = (int) (totalCopies * d.waiting / totalWaiting);
                alloc.put(d.library.getId(), floor);
                guaranteed += floor;
            }
            int remainder = totalCopies - guaranteed;
            // 余数按等待人数的小数部分降序；同分时等待最久的分馆优先
            List<LibraryDemand> byFraction = demands.stream()
                    .sorted(Comparator.comparingDouble(
                            (LibraryDemand d) -> -(totalCopies * (double) d.waiting / totalWaiting
                                    - Math.floor(totalCopies * (double) d.waiting / totalWaiting)))
                            .thenComparingLong(d -> -d.longestWaitHours))
                    .toList();
            int idx = 0;
            while (remainder > 0 && !byFraction.isEmpty()) {
                LibraryDemand d = byFraction.get(idx % byFraction.size());
                alloc.merge(d.library.getId(), 1, Integer::sum);
                remainder--;
                idx++;
            }
        }

        List<DemandAdviceView.LibraryTarget> targets = new ArrayList<>();
        for (LibraryDemand d : demands) {
            long avail = items.countByBibIdAndCurrentLibraryIdAndStatus(
                    bib.getId(), d.library.getId(), ItemStatus.AVAILABLE);
            Long bestEta = fastestTransferEta(bib, d.library);
            int add = alloc.getOrDefault(d.library.getId(), 0);
            String note;
            if (add <= 0) {
                note = "等待人数较少，优先由他馆调拨/回流满足";
            } else if (bestEta != null && bestEta >= policy.etaHours(false)) {
                note = "跨片区调拨在途约 " + bestEta + " 小时，建议就近采购 " + add
                        + " 册直达本馆，减少长链路物流成本";
            } else {
                note = "建议新增 " + add + " 册到本馆就近满足等待读者";
            }
            targets.add(new DemandAdviceView.LibraryTarget(
                    d.library.getCode(), d.library.getName(), d.waiting, d.longestWaitHours,
                    avail, add, bestEta, note));
        }
        if (totalCopies > 0) {
            reasons.add("新增复本按各分馆等待人数/等待时长摊分到 " + targets.stream()
                    .filter(t -> t.recommendedNewCopies() > 0).count()
                    + " 个分馆，避免全部配送到总馆");
        }
        return targets;
    }

    /** 从任一受保护可调出在架馆到目标馆的最快在途小时；无则 null（只能新采购） */
    private Long fastestTransferEta(Bib bib, Library target) {
        Long best = null;
        for (Library src : libraries.findAll()) {
            if (src.getId().equals(target.getId()) || !src.isActive() || src.isTemporaryClosed()) {
                continue;
            }
            long avail = items.countByBibIdAndCurrentLibraryIdAndStatus(
                    bib.getId(), src.getId(), ItemStatus.AVAILABLE);
            if (avail <= 0 || !catalog.protectionAllows(bib.getId(), src, avail)) {
                continue;
            }
            long eta = policy.etaHours(src.getRegion().equals(target.getRegion()));
            best = best == null ? eta : Math.min(best, eta);
        }
        return best;
    }

    private static class LibraryDemand {
        final Library library;
        long waiting;
        long longestWaitHours;

        LibraryDemand(Library library) {
            this.library = library;
        }
    }
}
