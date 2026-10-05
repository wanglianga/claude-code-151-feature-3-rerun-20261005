package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 馆藏流转记录：新增复本到馆、系统自动分配、馆员人工改配均落审计留痕。
 * 人工调整（MANUAL_ADJUST）的调整原因强制写入 {@link #reason}。
 */
@Entity
@Table(name = "collection_events")
@Getter
@Setter
public class CollectionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bib_id")
    private Bib bib;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private Hold hold;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_library_id")
    private Library fromLibrary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_library_id")
    private Library toLibrary;

    /** ACQUIRE 新增复本到馆 / ALLOCATE 系统平衡分配 / MANUAL_ADJUST 馆员人工改配 */
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    /** LOCAL 本馆即上架 / TRANSFER 跨馆调拨（ALLOCATE 时） */
    @Column(name = "alloc_mode", length = 16)
    private String allocMode;

    /** 操作人；系统自动操作记为 SYSTEM */
    @Column(nullable = false, length = 64)
    private String staff;

    @Column(length = 512)
    private String reason;

    @Column(length = 1024)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
