package ltsa.updatingControllers;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

/**
 * 評価実験用のピークメモリ計測クラス
 */
public class EvaluationProfiler {

    /**
     * 現在記録されているピークメモリの値をリセットする。
     * 計測区間の直前に呼び出すことで、過去のゴミ記録を消去します。
     */
    public static void resetPeakMemory() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            pool.resetPeakUsage();
        }
    }

    /**
     * リセットした時点から現在までに到達したヒープメモリの最大使用量（バイト）を取得する。
     * 計測区間の直後に呼び出します。
     */
    public static long getPeakMemoryUsage() {
        long peakMemory = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peakMemory += pool.getPeakUsage().getUsed();
            }
        }
        return peakMemory;
    }

	// ▼▼▼ 追加: 「現在の」ヒープ使用量を取得するメソッド ▼▼▼
    public static long getCurrentMemoryUsage() {
        long currentMemory = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                currentMemory += pool.getUsage().getUsed(); // Peakではなく現在のUsageを取得
            }
        }
        return currentMemory;
    }
}