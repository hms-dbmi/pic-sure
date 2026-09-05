package edu.harvard.hms.dbmi.avillach.operations.banner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "banner_priority_allocator")
@Table(name = "banner_priority_allocator")
public class BannerPriorityAllocator {

    static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(name = "next_priority", nullable = false)
    private int nextPriority;

    public Integer getId() {
        return id;
    }

    public BannerPriorityAllocator setId(Integer id) {
        this.id = id;
        return this;
    }

    public int getNextPriority() {
        return nextPriority;
    }

    public BannerPriorityAllocator setNextPriority(int nextPriority) {
        this.nextPriority = nextPriority;
        return this;
    }
}
