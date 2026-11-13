package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link edu.harvard.hms.dbmi.avillach.query.query.QueryRowLocks}, the lock a request holds while it works on one query row.
 * The bean is enabled by {@link QueryRowLockConfig}.
 */
@ConfigurationProperties(prefix = "picsure.query.row-lock")
public class QueryRowLockProperties {

    /**
     * How many seconds a request waits for the lock of its row before it is answered with 503. Zero or less means the request does not
     * wait: it is answered with 503 whenever another request holds the lock.
     */
    private int waitSec = 60;

    public int getWaitSec() {
        return waitSec;
    }

    public void setWaitSec(int waitSec) {
        this.waitSec = waitSec;
    }
}
