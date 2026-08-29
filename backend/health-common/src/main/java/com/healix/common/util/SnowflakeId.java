package com.healix.common.util;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 雪花 ID 工具：数值型 {@link #nextId()}，以及固定长度大写字母数字编码。
 *
 * <p>机构编码：{@link #nextOrgCode()} → {@code 01} + 30 位 [0-9A-Z]，共 32 位。
 */
public final class SnowflakeId {

    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int BASE = ALPHABET.length;

    /** 自定义纪元：2024-01-01 UTC */
    private static final long EPOCH_MS = 1704067200000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private static final long WORKER_ID;
    private static final long DATACENTER_ID;

    private static long sequence = 0L;
    private static long lastTimestamp = -1L;

    static {
        long mixed = mixHost();
        WORKER_ID = mixed & MAX_WORKER_ID;
        DATACENTER_ID = (mixed >>> 5) & MAX_DATACENTER_ID;
    }

    private SnowflakeId() {
    }

    /** 生成 64 位数值雪花 ID。 */
    public static synchronized long nextId() {
        long timestamp = currentTime();
        if (timestamp < lastTimestamp) {
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = ThreadLocalRandom.current().nextInt(16);
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH_MS) << TIMESTAMP_SHIFT)
                | (DATACENTER_ID << DATACENTER_ID_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 生成固定长度编码：{@code prefix} + 大写字母与数字。
     *
     * @param prefix 前缀，如机构用 {@code "01"}
     * @param totalLength 总长度（含前缀），如 32
     */
    public static String nextCode(String prefix, int totalLength) {
        if (prefix == null) {
            prefix = "";
        }
        if (totalLength <= prefix.length()) {
            throw new IllegalArgumentException("totalLength must be greater than prefix length");
        }
        int bodyLen = totalLength - prefix.length();
        BigInteger value = BigInteger.valueOf(nextId())
                .shiftLeft(64)
                .or(BigInteger.valueOf(nextId()))
                .shiftLeft(32)
                .or(BigInteger.valueOf(ThreadLocalRandom.current().nextInt() & 0xffffffffL));
        String body = toBase36(value);
        if (body.length() > bodyLen) {
            body = body.substring(body.length() - bodyLen);
        } else if (body.length() < bodyLen) {
            StringBuilder sb = new StringBuilder(bodyLen);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            while (sb.length() + body.length() < bodyLen) {
                sb.append(ALPHABET[random.nextInt(BASE)]);
            }
            sb.append(body);
            body = sb.toString();
        }
        return prefix + body;
    }

    /** 生成业务主键：无前缀，固定 32 位大写字母数字。 */
    public static String nextBizId() {
        return nextCode("", 32);
    }

    /** 机构编码：{@code 01} 开头，共 32 位大写字母数字。 */
    public static String nextOrgCode() {
        return nextCode("01", 32);
    }

    private static String toBase36(BigInteger value) {
        if (value.signum() < 0) {
            value = value.abs();
        }
        if (value.signum() == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        BigInteger base = BigInteger.valueOf(BASE);
        BigInteger n = value;
        while (n.signum() > 0) {
            BigInteger[] dr = n.divideAndRemainder(base);
            sb.append(ALPHABET[dr[1].intValue()]);
            n = dr[0];
        }
        return sb.reverse().toString();
    }

    private static long currentTime() {
        return System.currentTimeMillis();
    }

    private static long waitUntil(long target) {
        long ts = currentTime();
        while (ts < target) {
            ts = currentTime();
        }
        return ts;
    }

    private static long mixHost() {
        try {
            byte[] addr = InetAddress.getLocalHost().getAddress();
            long h = 0;
            for (byte b : addr) {
                h = (h << 8) ^ (b & 0xff);
            }
            h ^= ProcessHandle.current().pid();
            h ^= System.nanoTime();
            return h;
        } catch (Exception e) {
            return ThreadLocalRandom.current().nextLong();
        }
    }
}
