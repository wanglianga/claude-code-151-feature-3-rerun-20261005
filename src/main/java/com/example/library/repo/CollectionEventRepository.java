package com.example.library.repo;

import com.example.library.domain.CollectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionEventRepository extends JpaRepository<CollectionEvent, Long> {

    List<CollectionEvent> findByBibIdOrderByCreatedAtDesc(Long bibId);

    List<CollectionEvent> findByItemIdOrderByCreatedAtDesc(Long itemId);

    List<CollectionEvent> findAllByOrderByCreatedAtDesc();
}
