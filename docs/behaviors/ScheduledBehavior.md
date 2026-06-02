# ScheduledBehavior - Cron-like Task Scheduling

## Overview

**ScheduledBehavior** provides cron-like scheduling capabilities for Jentic agents, enabling time-based task execution with full timezone support, missed execution handling, and comprehensive metrics.

**Since**: v0.2.0 | **Type**: `BehaviorType.SCHEDULED` | **Package**: `dev.agenor.runtime.behavior.advanced`

---

## Key Features

- ✅ **Full Cron Expression Support** - Standard 6-field cron format
- ✅ **Timezone-Aware Scheduling** - Execute tasks in any timezone
- ✅ **Missed Execution Policies** - SKIP or EXECUTE_ONCE
- ✅ **Execution Metrics** - Track success/failure rates and timing
- ✅ **Callbacks** - Success, failure, and missed execution callbacks
- ✅ **Graceful Shutdown** - Proper cleanup of scheduled tasks
- ✅ **Factory Methods** - Easy creation for common patterns
- ✅ **Thread-Safe** - Concurrent execution protection

---

## Cron Expression Format

ScheduledBehavior uses a **6-field cron format**:

```
┌───────────── second (0-59)
│ ┌─────────── minute (0-59)
│ │ ┌───────── hour (0-23)
│ │ │ ┌─────── day of month (1-31)
│ │ │ │ ┌───── month (1-12 or JAN-DEC)
│ │ │ │ │ ┌─── day of week (0-6 or SUN-SAT, 0=Sunday)
│ │ │ │ │ │
* * * * * *
```

### Supported Special Characters

| Character | Meaning | Example |
|-----------|---------|---------|
| `*` | Any value | `* * * * * *` = every second |
| `,` | Value list separator | `0,15,30,45 * * * * *` = 0, 15, 30, 45 seconds |
| `-` | Range of values | `0 0 9-17 * * *` = 9 AM to 5 PM |
| `/` | Step values | `*/15 * * * * *` = every 15 seconds |

### Common Cron Patterns

```java
// Every second (testing only!)
"* * * * * *"

// Every minute
"0 * * * * *"

// Every 5 minutes
"0 */5 * * * *"

// Every hour at minute 0
"0 0 * * * *"

// Every day at 9:30 AM
"0 30 9 * * *"

// Weekdays at 8:00 AM
"0 0 8 * * MON-FRI"

// First day of month at midnight
"0 0 0 1 * *"

// Every Sunday at midnight
"0 0 0 * * SUN"
```

---

## Basic Usage

### Simple Scheduled Task

```java
ScheduledBehavior dailyReport = new ScheduledBehavior(
    "daily-report",
    "0 0 9 * * *"  // 9 AM daily
) {
    @Override
    protected void scheduledAction() {
        log.info("Generating daily report...");
        generateReport();
    }
};

agent.addBehavior(dailyReport);
```

### With Timezone

```java
ScheduledBehavior tokyoTask = new ScheduledBehavior(
    "tokyo-task",
    "0 0 9 * * *",
    ZoneId.of("Asia/Tokyo")
) {
    @Override
    protected void scheduledAction() {
        log.info("Running at 9 AM Tokyo time");
    }
};
```

---

## Factory Methods

### Every Hour

```java
ScheduledBehavior hourly = ScheduledBehavior.everyHour(
    "hourly-sync",
    () -> syncData()
);
```

### Daily at Specific Time

```java
ScheduledBehavior backup = ScheduledBehavior.daily(
    "daily-backup",
    2,  // Hour (2 AM)
    0,  // Minute
    () -> performBackup()
);
```

### Weekdays Only

```java
ScheduledBehavior businessHours = ScheduledBehavior.weekdays(
    "business-check",
    9,  // 9 AM
    0,
    () -> checkMarkets()
);
```

---

## Advanced Features

### 1. Execution Callbacks

```java
behavior.onSuccess(b -> {
    log.info("Task completed in {}ms", b.getAverageExecutionTimeMs());
    notifySuccess();
});

behavior.onFailure(e -> {
    log.error("Task failed", e);
    alertAdmin(e);
});

behavior.onMissed(() -> {
    log.warn("Execution was missed!");
    logMissedExecution();
});
```

### 2. Missed Execution Policies

```java
// Skip missed executions (default)
behavior.setMissedExecutionPolicy(
    ScheduledBehavior.MissedExecutionPolicy.SKIP
);

// Execute once if missed
behavior.setMissedExecutionPolicy(
    ScheduledBehavior.MissedExecutionPolicy.EXECUTE_ONCE
);
```

### 3. Execution Timeout

```java
// Set max execution time
behavior.setExecutionTimeout(Duration.ofMinutes(5));
```

### 4. Chaining Configuration

```java
ScheduledBehavior task = new ScheduledBehavior("task", "0 0 * * * *") {
    @Override
    protected void scheduledAction() {
        // Task logic
    }
}
.setMissedExecutionPolicy(MissedExecutionPolicy.EXECUTE_ONCE)
.setExecutionTimeout(Duration.ofMinutes(10))
.onSuccess(b -> log.info("Success!"))
.onFailure(e -> log.error("Failed!", e));
```

---

## Metrics and Monitoring

### Available Metrics

```java
int total = behavior.getTotalExecutions();
int successful = behavior.getSuccessfulExecutions();
int failed = behavior.getFailedExecutions();
int missed = behavior.getMissedExecutions();

double successRate = behavior.getSuccessRate(); // 0.0 to 1.0
double avgTimeMs = behavior.getAverageExecutionTimeMs();
```

### Next Execution Info

```java
ZonedDateTime next = behavior.getNextExecutionTime();
ZonedDateTime last = behavior.getLastExecutionTime();
Duration timeUntil = behavior.getTimeUntilNextExecution();

log.info("Next execution: {}", next);
log.info("Time until next: {} seconds", timeUntil.getSeconds());
```

### Metrics Summary

```java
String summary = behavior.getMetricsSummary();
log.info(summary);

// Output:
// ScheduledBehavior[daily-report] Metrics: total=10, success=9, 
// failed=1, missed=0, successRate=90.0%, avgTime=150.5ms, 
// nextExec=2025-10-31T09:00:00
```

---

## Real-World Examples

### 1. Database Backup (Daily at 2 AM)

```java
ScheduledBehavior backup = new ScheduledBehavior(
    "database-backup",
    "0 0 2 * * *"  // 2 AM daily
) {
    @Override
    protected void scheduledAction() throws Exception {
        log.info("Starting database backup...");
        
        String backupFile = databaseService.createBackup();
        cloudStorage.upload(backupFile);
        
        log.info("Backup completed: {}", backupFile);
    }
};

backup.setExecutionTimeout(Duration.ofHours(2));
backup.onSuccess(b -> sendBackupNotification("success"));
backup.onFailure(e -> sendBackupNotification("failure", e));
```

### 2. Stock Market Monitor (Weekdays, Business Hours)

```java
ScheduledBehavior marketMonitor = ScheduledBehavior.weekdays(
    "market-monitor",
    9, 30,  // 9:30 AM
    () -> {
        log.info("Market opened - starting monitoring");
        marketService.startMonitoring();
    }
);

ScheduledBehavior marketClose = ScheduledBehavior.weekdays(
    "market-close",
    16, 0,  // 4:00 PM
    () -> {
        log.info("Market closed - stopping monitoring");
        marketService.stopMonitoring();
        generateDayReport();
    }
);
```

### 3. Log Cleanup (Weekly, Sunday Midnight)

```java
ScheduledBehavior cleanup = new ScheduledBehavior(
    "log-cleanup",
    "0 0 0 * * SUN"  // Sunday midnight
) {
    @Override
    protected void scheduledAction() {
        log.info("Starting weekly log cleanup...");
        
        int deleted = fileService.deleteOldLogs(Duration.ofDays(30));
        
        log.info("Cleanup completed: {} files deleted", deleted);
    }
};

cleanup.onSuccess(b -> 
    metricsService.record("logs.cleanup.files", b.getTotalExecutions())
);
```

### 4. Health Check Monitor (Every 5 Minutes)

```java
ScheduledBehavior healthCheck = new ScheduledBehavior(
    "health-check",
    "0 */5 * * * *"  // Every 5 minutes
) {
    @Override
    protected void scheduledAction() throws Exception {
        HealthStatus status = healthService.checkAll();
        
        if (!status.isHealthy()) {
            throw new RuntimeException("Health check failed: " + status);
        }
    }
};

healthCheck.setExecutionTimeout(Duration.ofSeconds(30));
healthCheck.onFailure(e -> alertService.sendAlert("Health check failed", e));
```

### 5. Report Generation (First Day of Month)

```java
ScheduledBehavior monthlyReport = new ScheduledBehavior(
    "monthly-report",
    "0 0 9 1 * *"  // 9 AM on 1st of month
) {
    @Override
    protected void scheduledAction() throws Exception {
        log.info("Generating monthly report...");
        
        Report report = reportService.generateMonthlyReport();
        emailService.sendToManagement(report);
        
        log.info("Monthly report sent to {} recipients", 
                report.getRecipientCount());
    }
};

monthlyReport.setMissedExecutionPolicy(
    MissedExecutionPolicy.EXECUTE_ONCE  // Don't skip if server was down
);
```

---

## Thread Safety

ScheduledBehavior is **thread-safe** and ensures:

1. ✅ **No Concurrent Execution** - Only one execution at a time
2. ✅ **Atomic State Updates** - Thread-safe metrics
3. ✅ **Safe Shutdown** - Graceful cleanup of resources

```java
// Safe to call from multiple threads
behavior.execute();
behavior.execute();  // Will not run concurrently

// Safe metrics access
int executions = behavior.getTotalExecutions();  // Atomic read
```

---

## Performance Considerations

### Memory Usage
- ~200 bytes per behavior (minimal overhead)
- Shared scheduler reduces thread usage
- Efficient cron expression caching

### CPU Usage
- Minimal - only active during execution
- Smart scheduling reduces unnecessary checks
- Virtual thread support for high concurrency

### Best Practices

```java
// ✅ GOOD: Use shared scheduler
ScheduledExecutorService sharedScheduler = 
    Executors.newScheduledThreadPool(4);

ScheduledBehavior b1 = new ScheduledBehavior("t1", "...", null, sharedScheduler);
ScheduledBehavior b2 = new ScheduledBehavior("t2", "...", null, sharedScheduler);

// ❌ BAD: Each behavior creates own scheduler (resource intensive)
ScheduledBehavior b1 = new ScheduledBehavior("t1", "...");  // New thread pool
ScheduledBehavior b2 = new ScheduledBehavior("t2", "...");  // Another pool
```

---

## Testing

### Unit Testing Scheduled Behaviors

```java
@Test
void testScheduledExecution() throws Exception {
    AtomicInteger executions = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(1);
    
    ScheduledBehavior behavior = new ScheduledBehavior(
        "test",
        "* * * * * *"  // Every second
    ) {
        @Override
        protected void scheduledAction() {
            executions.incrementAndGet();
            latch.countDown();
        }
    };
    
    behavior.execute().join();
    
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertTrue(executions.get() > 0);
}
```

### Testing Cron Expressions

```java
@Test
void testCronParsing() {
    CronExpression cron = CronExpression.parse("0 30 9 * * MON-FRI");
    
    ZonedDateTime monday = ZonedDateTime.of(
        2025, 11, 3, 9, 30, 0, 0, ZoneId.systemDefault()
    );
    
    assertTrue(cron.matches(monday));
}
```

---

## Troubleshooting

### Issue: Behavior Not Executing

**Check:**
1. Is the behavior active? `behavior.isActive()`
2. Is next execution in the future? `behavior.getNextExecutionTime()`
3. Is the cron expression valid? Try parsing it directly
4. Is the behavior added to an agent?

### Issue: Missed Executions

**Solutions:**
1. Use `EXECUTE_ONCE` policy for critical tasks
2. Increase execution timeout if tasks are slow
3. Monitor system load - may be missing due to overload
4. Check timezone configuration

### Issue: High Memory Usage

**Solutions:**
1. Use shared scheduler for multiple behaviors
2. Ensure behaviors are properly stopped when done
3. Monitor execution times - slow tasks accumulate

---

## Comparison with Alternatives

| Feature | ScheduledBehavior | Java Timer | Quartz Scheduler |
|---------|-------------------|------------|------------------|
| Cron Support | ✅ Full | ❌ No | ✅ Full |
| Timezone-Aware | ✅ Yes | ❌ No | ✅ Yes |
| Metrics | ✅ Built-in | ❌ No | ⚠️ Requires plugin |
| Thread-Safe | ✅ Yes | ⚠️ Partial | ✅ Yes |
| Lightweight | ✅ Yes | ✅ Yes | ❌ Heavy |
| Dependencies | ✅ None | ✅ None | ❌ Many |

---

## Migration Guide

### From Java Timer

```java
// BEFORE (Java Timer)
Timer timer = new Timer();
timer.scheduleAtFixedRate(new TimerTask() {
    @Override
    public void run() {
        doTask();
    }
}, 0, 60000);  // Every minute

// AFTER (ScheduledBehavior)
ScheduledBehavior behavior = new ScheduledBehavior(
    "task",
    "0 * * * * *"  // Every minute
) {
    @Override
    protected void scheduledAction() {
        doTask();
    }
};
```

### From Quartz

```java
// BEFORE (Quartz)
JobDetail job = JobBuilder.newJob(MyJob.class)
    .withIdentity("myJob")
    .build();

Trigger trigger = TriggerBuilder.newTrigger()
    .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0))
    .build();

scheduler.scheduleJob(job, trigger);

// AFTER (ScheduledBehavior)
ScheduledBehavior behavior = ScheduledBehavior.daily(
    "myJob",
    9, 0,
    () -> new MyJob().execute()
);
```

---

## Future Enhancements

Planned for future versions:

- [ ] **Persistent Schedules** - Save/load schedules to database
- [ ] **Dynamic Reconfiguration** - Change cron expression at runtime
- [ ] **Execution History** - Detailed execution audit log
- [ ] **Distributed Scheduling** - Cluster-aware task execution
- [ ] **Conditional Scheduling** - Execute only if condition met

---

## See Also

- [RetryBehavior](RetryBehavior.md) - For unreliable operations
- [BatchBehavior](BatchBehavior.md) - For bulk processing
- [CircuitBreakerBehavior](CircuitBreakerBehavior.md) - For fault tolerance

---

**Version**: 0.2.0  
**Last Updated**: October 30, 2025  
**Status**: ✅ Production Ready
