package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.realtime.properties.RealtimeProperties;

import java.time.Duration;

/**
 * 集群节点心跳度量编解码与过期裁决内部工具
 *
 * @author Ateng
 * @since 2026-11-14
 */
final class ClusterNodeHeartbeatSupport {

    static final String DEFAULT_NODES_HASH_KEY = "ateng:realtime:cluster:nodes";
    private static final long DEFAULT_METRICS_INTERVAL_MILLIS = 5_000L;
    private static final long MIN_NODE_EXPIRE_MILLIS = 15_000L;

    private ClusterNodeHeartbeatSupport() {
    }

    static String resolveNodesHashKey(RealtimeProperties properties) {
        if (properties != null && properties.getCluster() != null) {
            String key = properties.getCluster().getNodesHashKey();
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return DEFAULT_NODES_HASH_KEY;
    }

    static long resolveExpirationMillis(RealtimeProperties properties) {
        long intervalMillis = DEFAULT_METRICS_INTERVAL_MILLIS;
        if (properties != null && properties.getCluster() != null) {
            Duration interval = properties.getCluster().getMetricsInterval();
            if (interval != null && !interval.isZero() && !interval.isNegative()) {
                intervalMillis = interval.toMillis();
            }
        }
        return Math.max(intervalMillis * 3L, MIN_NODE_EXPIRE_MILLIS);
    }

    static String formatHeartbeatValue(int count, long timestampMillis) {
        return Math.max(0, count) + ":" + timestampMillis;
    }

    static HeartbeatEntry parseHeartbeatValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String trimmed = rawValue.trim();
        int sepIndex = trimmed.indexOf(':');
        if (sepIndex < 0) {
            try {
                int count = Integer.parseInt(trimmed);
                return new HeartbeatEntry(Math.max(0, count), null);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String countPart = trimmed.substring(0, sepIndex).trim();
        String tsPart = trimmed.substring(sepIndex + 1).trim();
        if (countPart.isEmpty() || tsPart.isEmpty()) {
            return null;
        }
        try {
            int count = Integer.parseInt(countPart);
            long timestampMillis = Long.parseLong(tsPart);
            return new HeartbeatEntry(Math.max(0, count), timestampMillis);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean isExpired(HeartbeatEntry entry, long nowMillis, long expireMillis) {
        if (entry == null) {
            return true;
        }
        return entry.timestampMillis() != null && (nowMillis - entry.timestampMillis()) > expireMillis;
    }

    record HeartbeatEntry(int count, Long timestampMillis) {
    }
}
