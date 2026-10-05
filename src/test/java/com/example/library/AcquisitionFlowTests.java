package com.example.library;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.service.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.AcquireResultView;
import com.example.library.web.dto.AssignmentView;
import com.example.library.web.dto.CollectionEventView;
import com.example.library.web.dto.admin.DemandAdviceView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热门书预约队列调整端到端测试：
 * 采购/暂停续借建议、新增复本到馆按预约时间+分馆等待均衡分配、
 * 通知含预计取书窗口、馆员人工改配原因入馆藏流转记录。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class AcquisitionFlowTests {

    @Autowired AcquisitionService acquisitions;
    @Autowired DemandAdvisor demand;
    @Autowired HoldService holdService;
    @Autowired TransferService transferService;
    @Autowired LoanService loanService;
    @Autowired PolicyService policy;
    @Autowired AppClock clock;
    @Autowired ItemRepository items;
    @Autowired HoldRepository holds;
    @Autowired TransferTaskRepository tasks;
    @Autowired LoanRepository loans;
    @Autowired ReaderRepository readers;
    @Autowired NotificationRepository notifications;
    @Autowired CollectionEventRepository events;

    private Instant t0;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-10-03T08:00:00Z");
        clock.setFixed(t0);
        readers.save(newReader("R004", "赵六"));
        readers.save(newReader("R005", "钱七"));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private Reader newReader(String card, String name) {
        Reader r = new Reader();
        r.setCardNo(card);
        r.setName(name);
        r.setEmail(card.toLowerCase() + "@example.com");
        r.setStatus("ACTIVE");
        return r;
    }

    // ---------- 1. 采购建议：等待人数 / 复本 / 续借 / 调拨成本 ----------

    @Test
    void demandAdvice_flagsHot_recommendsPurchase_toWaitingBranch_andSuspendRenewal() {
        // R002 先借走中心馆一册三体（在借复本，稍后用于暂停续借建议）
        loanService.checkout("R002", "C-0001", "CENTRAL");

        // R001 第一个预约：城东可调出 1 册 → 锁定跨馆；之后城东受保护仅剩 2 册不可再调
        Hold h1 = holdService.placeHold(1L, "R001", "WEST");
        assertNotNull(h1.getItem());
        // 后续 3 位城西读者无复本可分 → 未满足等待达阈值 3
        Hold h2 = holdService.placeHold(1L, "R003", "WEST");
        Hold h3 = holdService.placeHold(1L, "R004", "WEST");
        Hold h4 = holdService.placeHold(1L, "R005", "WEST");
        assertNull(h2.getItem());
        assertNull(h3.getItem());
        assertNull(h4.getItem());

        DemandAdviceView advice = demand.advise(1L);
        assertTrue(advice.hot());
        assertEquals(3, advice.unassignedReaders());
        assertTrue(advice.recommendPurchase(), "在架复本全部受保护不可调出 → 建议采购");
        assertEquals(3, advice.recommendedCopies());
        assertTrue(advice.recommendSuspendRenewal(), "热门且有在借复本 → 建议暂停续借");
        assertTrue(advice.renewalBlockedLoans() >= 1, "在借复本因排队被禁止续借");

        // 建议新增复本全部摊分到等待分馆 WEST，而不是总馆 CENTRAL
        DemandAdviceView.LibraryTarget west = advice.targetLibraries().stream()
                .filter(t -> t.libraryCode().equals("WEST")).findFirst().orElseThrow();
        assertEquals(3, west.recommendedNewCopies());
        assertEquals(3, west.waitingReaders());
        assertFalse(advice.reasons().isEmpty());

        // 热门列表包含该书
        assertTrue(demand.adviseHot().stream().anyMatch(v -> v.bibId().equals(1L)));

        // 在借复本确实不可续借（排队冲突）
        Loan activeLoan = loans.findByReaderCardNoAndStatusOrderByCheckedOutAtDesc(
                "R002", LoanStatus.ACTIVE).get(0);
        assertFalse(loanService.renewEligibility(activeLoan).renewable());
    }

    // ---------- 2. 新增复本平衡分配：考虑各分馆等待时间，避免全部流向总馆 ----------

    @Test
    void newCopies_balanceByBranchWait_notAllToCentral_windowInNotification() {
        // 平凡的世界仅城北 1 册：R001 立即取走
        holdService.placeHold(4L, "R001", "NORTH");

        // 随后外馆读者陆续排队（城南最早、城西次之），很久之后总馆才有人排队
        Hold south = holdService.placeHold(4L, "R002", "SOUTH");        // t0
        clock.setFixed(t0.plus(10, ChronoUnit.HOURS));
        Hold west = holdService.placeHold(4L, "R003", "WEST");
        clock.setFixed(t0.plus(70, ChronoUnit.HOURS));
        Hold central = holdService.placeHold(4L, "R004", "CENTRAL");

        // 两册新复本统一到馆总馆 CENTRAL
        AcquireResultView result = acquisitions.acquireCopies(4L, "CENTRAL", 2, "采购馆员小陈",
                "热门书加采");
        assertEquals(2, result.assignments().size());

        AssignmentView a1 = result.assignments().get(0);
        AssignmentView a2 = result.assignments().get(1);
        // 外馆队首比总馆队首早等 >48h 宽限 → 两册都跨馆调拨给久等分馆，而非留在总馆
        assertEquals("TRANSFER", a1.mode());
        assertEquals("R002", a1.readerCardNo());
        assertEquals("CENTRAL", a1.fromLibraryCode());
        assertEquals("SOUTH", a1.toLibraryCode());
        assertEquals("TRANSFER", a2.mode());
        assertEquals("R003", a2.readerCardNo());
        assertEquals("WEST", a2.toLibraryCode());

        // 总馆读者虽在复本到馆馆，仍在排队（新复本没有全部流向总馆）
        assertEquals(HoldStatus.QUEUED, holds.findById(central.getId()).orElseThrow().getStatus());
        assertNull(holds.findById(central.getId()).orElseThrow().getItem());

        // 跨片区 ETA 72h；取书窗口 = 预计到馆 ~ 预计到馆+3 天
        Instant readyAt = t0.plus(70, ChronoUnit.HOURS).plus(72, ChronoUnit.HOURS);
        assertEquals(readyAt, a1.estimatedReadyAt());
        assertEquals(readyAt.plus(3, ChronoUnit.DAYS), a1.pickupWindowEndsAt());

        // 城南读者收到含“预计取书窗口”的调拨预告
        boolean hasWindow = notifications.findAll().stream()
                .filter(n -> n.getReader().getCardNo().equals("R002"))
                .anyMatch(n -> n.getMessage().contains("取书窗口") && n.getMessage().contains("城南分馆"));
        assertTrue(hasWindow);

        // 两个跨馆调拨任务已生成
        assertEquals(2, transferService.pendingPickup().size());
        // 到馆 ACQUIRE + 自动 ALLOCATE 留痕
        List<CollectionEventView> bibEvents = acquisitions.eventsForBib(4L);
        assertTrue(bibEvents.stream().anyMatch(e -> e.eventType().equals("ACQUIRE")));
        assertEquals(2, bibEvents.stream().filter(e -> e.eventType().equals("ALLOCATE")).count());
    }

    // ---------- 3. 本馆新增复本立即上架：READY + 本馆取书窗口 ----------

    @Test
    void newCopies_localBranch_readyImmediatelyWithLocalWindow() {
        loanService.checkout("R002", "C-0001", "CENTRAL");
        holdService.placeHold(1L, "R001", "WEST"); // 城东锁定
        Hold q1 = holdService.placeHold(1L, "R003", "WEST");
        Hold q2 = holdService.placeHold(1L, "R004", "WEST");
        Hold q3 = holdService.placeHold(1L, "R005", "WEST");

        AcquireResultView result = acquisitions.acquireCopies(1L, "WEST", 3, "采购馆员小陈", null);
        for (AssignmentView a : result.assignments()) {
            assertEquals("LOCAL", a.mode());
            assertEquals("WEST", a.toLibraryCode());
            assertEquals(t0, a.estimatedReadyAt());
            assertEquals(t0.plus(3, ChronoUnit.DAYS), a.pickupWindowEndsAt());
        }
        for (Hold q : List.of(q1, q2, q3)) {
            assertEquals(HoldStatus.READY, holds.findById(q.getId()).orElseThrow().getStatus());
        }
    }

    // ---------- 4. 无人排队的新复本直接上架 ----------

    @Test
    void newCopies_withoutQueue_areShelved() {
        AcquireResultView result = acquisitions.acquireCopies(2L, "NORTH", 1, "采购馆员小陈", null);
        AssignmentView a = result.assignments().get(0);
        assertEquals("SHELVED", a.mode());
        assertEquals(ItemStatus.AVAILABLE,
                items.findByBarcode(a.itemBarcode()).orElseThrow().getStatus());
    }

    // ---------- 5. 馆员人工改配：原因必填并入馆藏流转记录，双方通知，在途不可改 ----------

    @Test
    void manualAdjust_recordedInCollectionEvents_andInTransitBlocked() {
        holdService.placeHold(4L, "R001", "NORTH");
        Hold south = holdService.placeHold(4L, "R002", "SOUTH");
        clock.setFixed(t0.plus(10, ChronoUnit.HOURS));
        Hold west = holdService.placeHold(4L, "R003", "WEST");
        clock.setFixed(t0.plus(70, ChronoUnit.HOURS));
        Hold central = holdService.placeHold(4L, "R004", "CENTRAL");

        AcquireResultView result = acquisitions.acquireCopies(4L, "CENTRAL", 2, "采购馆员小陈", null);
        String southBarcode = result.assignments().get(0).itemBarcode(); // CENTRAL→SOUTH
        String westBarcode = result.assignments().get(1).itemBarcode();  // CENTRAL→WEST

        // 无原因 / 无馆员必须拒绝
        assertThrows(ApiException.class,
                () -> acquisitions.manualAdjust(southBarcode, central.getId(), "馆员老李", "  "));
        assertThrows(ApiException.class,
                () -> acquisitions.manualAdjust(southBarcode, central.getId(), " ", "优先总馆"));

        // 把配给城南、尚未出库的复本改配给总馆读者
        AssignmentView adjusted = acquisitions.manualAdjust(
                southBarcode, central.getId(), "馆员老李", "城南读者来电申请改期，优先照顾到馆现场读者");
        assertEquals("LOCAL", adjusted.mode());
        assertEquals(HoldStatus.READY, holds.findById(central.getId()).orElseThrow().getStatus());
        assertEquals(southBarcode,
                holds.findById(central.getId()).orElseThrow().getItem().getBarcode());

        // 城南读者复位为未分配排队，并收到调配变更通知
        Hold southRef = holds.findById(south.getId()).orElseThrow();
        assertEquals(HoldStatus.QUEUED, southRef.getStatus());
        assertNull(southRef.getItem());
        assertTrue(notifications.findAll().stream()
                .anyMatch(n -> n.getReader().getCardNo().equals("R002")
                        && n.getMessage().contains("复本分配做了调整")));

        // 原 CENTRAL→SOUTH 调拨任务已取消
        assertTrue(tasks.findActiveForItem(items.findByBarcode(southBarcode).orElseThrow().getId(),
                        List.of(TaskStatus.CREATED, TaskStatus.PICKED_UP)).isEmpty()
                || tasks.findAll().stream()
                        .filter(t -> t.getItem().getBarcode().equals(southBarcode))
                        .allMatch(t -> t.getStatus() == TaskStatus.CANCELLED
                                || t.getStatus() == TaskStatus.ARRIVED));

        // 流转记录包含 MANUAL_ADJUST 且原因/操作人留痕
        CollectionEventView ev = acquisitions.eventsForItem(southBarcode).stream()
                .filter(e -> e.eventType().equals("MANUAL_ADJUST")).findFirst().orElseThrow();
        assertEquals("馆员老李", ev.staff());
        assertTrue(ev.reason().contains("城南读者来电"));
        assertEquals("R004", ev.readerCardNo());

        // 已出库在途的复本不可改配
        Long westTaskId = tasks.findActiveForItem(items.findByBarcode(westBarcode).orElseThrow().getId(),
                List.of(TaskStatus.CREATED)).get(0).getId();
        transferService.pickup(List.of(westTaskId), "物流员小冯");
        assertEquals(ItemStatus.IN_TRANSIT, items.findByBarcode(westBarcode).orElseThrow().getStatus());
        ApiException ex = assertThrows(ApiException.class,
                () -> acquisitions.manualAdjust(westBarcode, south.getId(), "馆员老李", "尝试截留"));
        assertEquals(409, ex.getStatus());
    }
}
