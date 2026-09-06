package com.transdb.repository;

import com.transdb.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {
    boolean existsByName(String name);

    java.util.Optional<Tag> findByName(String name);
}
