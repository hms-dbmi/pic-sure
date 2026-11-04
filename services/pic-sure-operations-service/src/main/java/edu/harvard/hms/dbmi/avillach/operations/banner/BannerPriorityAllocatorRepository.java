package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface BannerPriorityAllocatorRepository extends JpaRepository<BannerPriorityAllocator, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT allocator FROM banner_priority_allocator allocator WHERE allocator.id = 1")
    Optional<BannerPriorityAllocator> lockSingleton();
}
