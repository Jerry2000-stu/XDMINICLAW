package com.xd.xdminiclaw.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户每日用量限制器。
 * 每用户每天最多调用 max-daily-calls 次，超出后拒绝服务。
 * 计数在每天第一次请求时自动重置。
 */
@Slf4j
@Component
public class UsageLimiter {

    @Value("${xdclaw.security.max-daily-calls:100}")
    private int maxDailyCalls;

    /** 管理员 openId 列表，不受限制 */
    @Value("${xdclaw.security.admin-ids:}")
    private java.util.List<String> adminIds;

    private final Map<String, AtomicInteger> dailyCount = new ConcurrentHashMap<>();
    private final Map<String, LocalDate>     lastDate   = new ConcurrentHashMap<>();

    /**
     * 检查用户是否还有配额，有则消耗一次并返回 true，超出返回 false。
     */
    public boolean checkAndConsume(String userId) {
        if (adminIds != null && adminIds.contains(userId)) return true;

        LocalDate today = LocalDate.now();
        // 新的一天则重置计数
        lastDate.compute(userId, (k, v) -> {
            if (v == null || !v.equals(today)) {
                dailyCount.put(k, new AtomicInteger(0));
                return today;
            }
            return v;
        });

        int count = dailyCount.computeIfAbsent(userId, k -> new AtomicInteger(0))
                              .incrementAndGet();
        if (count > maxDailyCalls) {
            log.warn("[UsageLimiter] user={} 超出每日限额 {}", userId, maxDailyCalls);
            return false;
        }
        return true;
    }

    /** 查询用户今日剩余次数 */
    public int getRemaining(String userId) {
        if (adminIds != null && adminIds.contains(userId)) return Integer.MAX_VALUE;
        LocalDate today = LocalDate.now();
        LocalDate last  = lastDate.getOrDefault(userId, null);
        if (last == null || !last.equals(today)) return maxDailyCalls;
        int used = dailyCount.getOrDefault(userId, new AtomicInteger(0)).get();
        return Math.max(0, maxDailyCalls - used);
    }
}
