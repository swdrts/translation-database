package com.transdb.repository;

import com.transdb.domain.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SegmentRepository extends JpaRepository<Segment, Long>,
        JpaSpecificationExecutor<Segment> {
}
