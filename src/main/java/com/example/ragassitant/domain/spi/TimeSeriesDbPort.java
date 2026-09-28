package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface TimeSeriesDbPort {

    List<Device> listBuiltinDevices(String keyword);

    Device findDeviceBySn(String sn);

    /**
     * @throws TimeSeriesUnavailableException 数据源不可用且未开启演示模式
     */
    List<EnergyRecord> queryEnergyByDays(String sn, int days);

    /**
     * @throws TimeSeriesUnavailableException 数据源不可用且未开启演示模式
     */
    List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate startDate, LocalDate endDate);

    Map<String, Double> aggregate(List<EnergyRecord> records, String granularity);

    /**
     * 当前返回的是否为演示数据（真实数据源不可用时的模拟值）。
     *
     * <p>工具层据此在输出里显式标注「演示数据」。改造前 InfluxDB 连不上时静默返回
     * {@code Math.random()} 生成的数字 —— 界面上和真实数据一模一样，同一个问题每次答案还不同。
     * 在一个反复强调「禁止编造数字」的系统里，这是最危险的一处。
     */
    default boolean isDemoData() {
        return false;
    }

    /** 时序数据源不可用（未连接或查询失败）。与「查询成功但确实没有数据」严格区分。 */
    class TimeSeriesUnavailableException extends RuntimeException {
        public TimeSeriesUnavailableException(String message) {
            super(message);
        }

        public TimeSeriesUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
