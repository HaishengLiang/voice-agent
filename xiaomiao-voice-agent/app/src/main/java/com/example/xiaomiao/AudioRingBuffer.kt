package com.example.xiaomiao

/**
 * 固定容量的音频帧环形缓存，用于弥补「唤醒命中」到「会话真正开始上行」之间的空档。
 *
 * 背景：命中唤醒词后要等 WebSocket 连上、session_attached 回执、问候音播完，
 * 这期间麦克风采集到的用户语音被丢弃，导致唤醒后紧接着说的第一句话丢失。
 * 本缓存持续保存最近 N 秒的帧，会话就绪后把「命中之后」的帧一次性灌入上行，
 * 让用户感觉唤醒和说话是连贯的。
 *
 * 线程安全：写发生在采集线程，读发生在主线程，所有方法 synchronized。
 * 帧序号 seq 单调递增，用于精确界定「命中之后」的范围，避免把唤醒词和旧声音灌进去。
 */
class AudioRingBuffer(
    private val capacityFrames: Int = DEFAULT_CAPACITY,
) {
    companion object {
        // 16 kHz / 640 sample per frame = 40 ms/frame → 5 秒约 125 帧。
        const val FRAME_MS = 40
        const val DEFAULT_CAPACITY = 5_000 / FRAME_MS + 8 // 留少量余量
    }

    private val frames = arrayOfNulls<ShortArray>(capacityFrames)
    private var nextSeq = 0L

    /** 写入一帧，返回该帧的全局序号。写入前会复制，避免复用采集 buffer 被覆盖。 */
    @Synchronized
    fun push(frame: ShortArray): Long {
        val seq = nextSeq++
        frames[(seq % capacityFrames).toInt()] = frame.copyOf()
        return seq
    }

    /** 当前最新帧序号（尚无帧时为 -1）。 */
    @Synchronized
    fun latestSeq(): Long = nextSeq - 1

    /**
     * 读取 (fromSeqExclusive, toSeqInclusive] 范围内的帧，按时间顺序返回。
     * 超出缓存 capacities 的旧帧已被覆盖，自动跳过。空区间返回空列表。
     */
    @Synchronized
    fun range(fromSeqExclusive: Long, toSeqInclusive: Long): List<ShortArray> {
        if (toSeqInclusive <= fromSeqExclusive) return emptyList()
        val oldest = maxOf(0L, nextSeq - capacityFrames)
        val start = maxOf(fromSeqExclusive + 1, oldest)
        val result = ArrayList<ShortArray>()
        for (seq in start..toSeqInclusive) {
            frames[(seq % capacityFrames).toInt()]?.let { result.add(it) }
        }
        return result
    }

    @Synchronized
    fun clear() {
        frames.fill(null)
        nextSeq = 0
    }
}
