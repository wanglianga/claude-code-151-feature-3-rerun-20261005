package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.AcquireResultView;
import com.example.library.web.dto.AssignmentView;
import com.example.library.web.dto.CollectionEventView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 馆藏采购入口：登记新增复本到馆（ACQUIRE 留痕），随即按预约时间与各分馆等待时长
 * 做平衡分配（{@link HoldService#assignNewCopies}），并暴露馆员人工改配与流转记录查询。
 */
@Service
public class AcquisitionService {

    private final BibRepository bibs;
    private final ItemRepository items;
    private final LibraryRepository libraries;
    private final CollectionEventRepository events;
    private final HoldService holdService;
    private final AppClock clock;

    public AcquisitionService(BibRepository bibs, ItemRepository items, LibraryRepository libraries,
                              CollectionEventRepository events, HoldService holdService, AppClock clock) {
        this.bibs = bibs;
        this.items = items;
        this.libraries = libraries;
        this.events = events;
        this.holdService = holdService;
        this.clock = clock;
    }

    /** 登记 count 册新复本到指定分馆并立即参与队列平衡分配（同一事务） */
    @Transactional
    public AcquireResultView acquireCopies(Long bibId, String libraryCode, int count,
                                           String staff, String note) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("到馆登记必须填写经办馆员");
        }
        if (count <= 0) {
            throw ApiException.unprocessable("新增册数必须大于 0");
        }
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        Library lib = libraries.findByCode(libraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + libraryCode));
        if (!lib.isActive()) {
            throw ApiException.unprocessable("该分馆已停止服务，不能登记到馆");
        }
        Instant now = clock.now();

        List<Item> newCopies = new ArrayList<>();
        List<String> barcodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Item item = new Item();
            String barcode = "ACQ-" + bibId + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            item.setBarcode(barcode);
            item.setBib(bib);
            item.setHomeLibrary(lib);
            item.setCurrentLibrary(lib);
            item.setStatus(ItemStatus.AVAILABLE);
            item.setCondition("GOOD");
            items.save(item);
            newCopies.add(item);
            barcodes.add(barcode);

            CollectionEvent ev = new CollectionEvent();
            ev.setBib(bib);
            ev.setItem(item);
            ev.setFromLibrary(lib);
            ev.setToLibrary(lib);
            ev.setEventType("ACQUIRE");
            ev.setStaff(staff);
            ev.setReason(note == null || note.isBlank() ? "热门书新增采购到馆" : note);
            ev.setDetail("新增复本 " + barcode + " 到馆" + lib.getName());
            ev.setCreatedAt(now);
            events.save(ev);
        }

        List<AssignmentView> assignments = holdService.assignNewCopies(bibId, newCopies).stream()
                .map(AcquisitionService::toView).toList();

        return new AcquireResultView(bib.getId(), bib.getTitle(), lib.getCode(), count,
                barcodes, assignments, now);
    }

    @Transactional
    public AssignmentView manualAdjust(String barcode, Long targetHoldId, String staff, String reason) {
        return toView(holdService.manualAdjust(barcode, targetHoldId, staff, reason));
    }

    @Transactional(readOnly = true)
    public List<CollectionEventView> eventsForBib(Long bibId) {
        if (bibs.findById(bibId).isEmpty()) {
            throw ApiException.notFound("书目不存在: " + bibId);
        }
        return events.findByBibIdOrderByCreatedAtDesc(bibId).stream()
                .map(AcquisitionService::eventView).toList();
    }

    @Transactional(readOnly = true)
    public List<CollectionEventView> eventsForItem(String barcode) {
        Item item = items.findByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("馆藏条码不存在: " + barcode));
        return events.findByItemIdOrderByCreatedAtDesc(item.getId()).stream()
                .map(AcquisitionService::eventView).toList();
    }

    @Transactional(readOnly = true)
    public List<CollectionEventView> allEvents() {
        return events.findAllByOrderByCreatedAtDesc().stream()
                .map(AcquisitionService::eventView).toList();
    }

    private static AssignmentView toView(HoldService.CopyAssignment a) {
        return new AssignmentView(a.holdId(), a.readerCardNo(), a.itemBarcode(), a.mode(),
                a.fromLibraryCode(), a.toLibraryCode(), a.estimatedReadyAt(), a.pickupWindowEndsAt());
    }

    private static CollectionEventView eventView(CollectionEvent e) {
        return new CollectionEventView(e.getId(),
                e.getBib() == null ? null : e.getBib().getId(),
                e.getBib() == null ? null : e.getBib().getTitle(),
                e.getItem() == null ? null : e.getItem().getBarcode(),
                e.getHold() == null ? null : e.getHold().getId(),
                e.getHold() == null ? null : e.getHold().getReader().getCardNo(),
                e.getFromLibrary() == null ? null : e.getFromLibrary().getCode(),
                e.getToLibrary() == null ? null : e.getToLibrary().getCode(),
                e.getEventType(), e.getAllocMode(), e.getStaff(), e.getReason(), e.getDetail(),
                e.getCreatedAt());
    }
}
