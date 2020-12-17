package org.apache.iotdb.cluster.server;

import org.apache.iotdb.cluster.config.ClusterDescriptor;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This is a port of
 * https://github.com/akka/akka/blob/master/akka-remote/src/main/scala/akka/remote/PhiAccrualFailureDetector.scala
 *
 * Implementation of 'The Phi Accrual Failure Detector' by Hayashibara et al. as defined in their paper:
 * [http://ddg.jaist.ac.jp/pub/HDY+04.pdf]
 *
 * The suspicion level of failure is given by a value called φ (phi). The basic idea of the φ failure detector is to
 * express the value of φ on a scale that is dynamically adjusted to reflect current network conditions. A configurable
 * threshold is used to decide if φ is considered to be a failure.
 *
 * The value of φ is calculated as:
 *
 * {{{ φ = -log10(1 - F(timeSinceLastHeartbeat) }}} where F is the cumulative distribution function of a normal
 * distribution with mean and standard deviation estimated from historical heartbeat inter-arrival times.
 */

public class PhiAccrualFailureDetector {
    private final double threshold;
    private final double minStdDeviationMillis;
    private final long acceptableHeartbeatPauseMillis;

    private final HeartbeatHistory heartbeatHistory;
    private final AtomicReference<Long> lastTimestampMillis = new AtomicReference<Long>();

    /**
     * @param threshold
     *            A low threshold is prone to generate many wrong suspicions but ensures a quick detection in the event
     *            of a real crash. Conversely, a high threshold generates fewer mistakes but needs more time to detect
     *            actual crashes
     *
     * @param maxSampleSize
     *            Number of samples to use for calculation of mean and standard deviation of inter-arrival times.
     *
     * @param minStdDeviationMillis
     *            Minimum standard deviation to use for the normal distribution used when calculating phi. Too low
     *            standard deviation might result in too much sensitivity for sudden, but normal, deviations in
     *            heartbeat inter arrival times.
     *
     * @param acceptableHeartbeatPauseMillis
     *            Duration corresponding to number of potentially lost/delayed heartbeats that will be accepted before
     *            considering it to be an anomaly. This margin is important to be able to survive sudden, occasional,
     *            pauses in heartbeat arrivals, due to for example garbage collect or network drop.
     *
     * @param firstHeartbeatEstimateMillis
     *            Bootstrap the stats with heartbeats that corresponds to to this duration, with a with rather high
     *            standard deviation (since environment is unknown in the beginning)
     */
    private PhiAccrualFailureDetector(double threshold, int maxSampleSize, double minStdDeviationMillis,
                                      long acceptableHeartbeatPauseMillis, long firstHeartbeatEstimateMillis) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be > 0: " + threshold);
        }
        if (maxSampleSize <= 0) {
            throw new IllegalArgumentException("Sample size must be > 0: " + maxSampleSize);
        }
        if (minStdDeviationMillis <= 0) {
            throw new IllegalArgumentException("Minimum standard deviation must be > 0: " + minStdDeviationMillis);
        }
        if (acceptableHeartbeatPauseMillis < 0) {
            throw new IllegalArgumentException(
                    "Acceptable heartbeat pause millis must be >= 0: " + acceptableHeartbeatPauseMillis);
        }
        if (firstHeartbeatEstimateMillis <= 0) {
            throw new IllegalArgumentException("First heartbeat value must be > 0: " + firstHeartbeatEstimateMillis);
        }

        this.threshold = threshold;
        this.minStdDeviationMillis = minStdDeviationMillis;
        this.acceptableHeartbeatPauseMillis = acceptableHeartbeatPauseMillis;

        long stdDeviationMillis = firstHeartbeatEstimateMillis / 4;
        heartbeatHistory = new HeartbeatHistory(maxSampleSize);
        heartbeatHistory.add(firstHeartbeatEstimateMillis - stdDeviationMillis)
                .add(firstHeartbeatEstimateMillis + stdDeviationMillis);
    }

    private double ensureValidStdDeviation(double stdDeviationMillis) {
        return Math.max(stdDeviationMillis, minStdDeviationMillis);
    }

    public synchronized double phi(long timestampMillis) {
        Long lastTimestampMillis = this.lastTimestampMillis.get();
        if (lastTimestampMillis == null) {
            return 0.0;
        }

        long timeDiffMillis = timestampMillis - lastTimestampMillis;
        double meanMillis = heartbeatHistory.mean() + acceptableHeartbeatPauseMillis;
        double stdDeviationMillis = ensureValidStdDeviation(heartbeatHistory.stdDeviation());

        double y = (timeDiffMillis - meanMillis) / stdDeviationMillis;
        double e = Math.exp(-y * (1.5976 + 0.070566 * y * y));
        if (timeDiffMillis > meanMillis) {
            return -Math.log10(e / (1.0 + e));
        } else {
            return -Math.log10(1.0 - 1.0 / (1.0 + e));
        }
    }

    public synchronized double phi() {
        return phi(System.currentTimeMillis());
    }

    public boolean isAvailable(long timestampMillis) {
        return phi(timestampMillis) < threshold;
    }

    public boolean isAvailable() {
        return phi(System.currentTimeMillis()) < threshold;
    }

    public LinkedList<Long> getHeartbeatHistory() {
        return heartbeatHistory.intervals;
    }

    public synchronized void heartbeat(long timestampMillis, boolean append) {
        Long lastTimestampMillis = this.lastTimestampMillis.getAndSet(timestampMillis);
        if (lastTimestampMillis != null && append) {
            long interval = timestampMillis - lastTimestampMillis;
            // if (isAvailable(timestampMillis)) {
            heartbeatHistory.add(interval);
            // }
        }
    }

    // public synchronized void appendTimeMill(long interval)
    // {
    // if (isAvailable(timestampMillis)) {
    // heartbeatHistory.add(interval);
    // }
    // }

    // public void heartbeat()
    // {
    // heartbeat(System.currentTimeMillis());
    // }

    public static class Builder {
        private double threshold = ClusterDescriptor.getInstance().getConfig().getThreshold();
        private int maxSampleSize = ClusterDescriptor.getInstance().getConfig().getMaxSampleSize();
        private double minStdDeviationMillis = ClusterDescriptor.getInstance().getConfig().getMinStdDeviationMillis();
        private long acceptableHeartbeatPauseMillis = ClusterDescriptor.getInstance().getConfig()
                .getAcceptableHeartbeatPauseMillis();
        private long firstHeartbeatEstimateMillis = ClusterDescriptor.getInstance().getConfig()
                .getFirstHeartbeatEstimateMillis();

        public Builder setThreshold(double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder setMaxSampleSize(int maxSampleSize) {
            this.maxSampleSize = maxSampleSize;
            return this;
        }

        public Builder setMinStdDeviationMillis(double minStdDeviationMillis) {
            this.minStdDeviationMillis = minStdDeviationMillis;
            return this;
        }

        public Builder setAcceptableHeartbeatPauseMillis(long acceptableHeartbeatPauseMillis) {
            this.acceptableHeartbeatPauseMillis = acceptableHeartbeatPauseMillis;
            return this;
        }

        public Builder setFirstHeartbeatEstimateMillis(long firstHeartbeatEstimateMillis) {
            this.firstHeartbeatEstimateMillis = firstHeartbeatEstimateMillis;
            return this;
        }

        public PhiAccrualFailureDetector build() {
            return new PhiAccrualFailureDetector(threshold, maxSampleSize, minStdDeviationMillis,
                    acceptableHeartbeatPauseMillis, firstHeartbeatEstimateMillis);
        }
    }

    private static class HeartbeatHistory {
        private final int maxSampleSize;
        private final LinkedList<Long> intervals = new LinkedList<Long>();
        private final AtomicLong intervalSum = new AtomicLong();
        private final AtomicLong squaredIntervalSum = new AtomicLong();

        public HeartbeatHistory(int maxSampleSize) {
            if (maxSampleSize < 1) {
                throw new IllegalArgumentException("maxSampleSize must be >= 1, got " + maxSampleSize);
            }
            this.maxSampleSize = maxSampleSize;
        }

        public double mean() {
            return (double) intervalSum.get() / intervals.size();
        }

        public double variance() {
            return ((double) squaredIntervalSum.get() / intervals.size()) - (mean() * mean());
        }

        public double stdDeviation() {
            return Math.sqrt(variance());
        }

        public HeartbeatHistory add(long interval) {
            if (intervals.size() >= maxSampleSize) {
                Long dropped = intervals.pollFirst();
                intervalSum.addAndGet(-dropped);
                squaredIntervalSum.addAndGet(-pow2(dropped));
            }
            intervals.add(interval);
            intervalSum.addAndGet(interval);
            squaredIntervalSum.addAndGet(pow2(interval));
            return this;
        }

        private long pow2(long x) {
            return x * x;
        }
    }
}