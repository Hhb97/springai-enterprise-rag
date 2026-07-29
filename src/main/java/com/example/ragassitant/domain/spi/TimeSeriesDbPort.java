package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface TimeSeriesDbPort {

    List<Device> listBuiltinDevices(String keyword);

    Device findDeviceBySn(String sn);

    List<EnergyRecord> queryEnergyByDays(String sn, int days);

    List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate startDate, LocalDate endDate);

    Map<String, Double> aggregate(List<EnergyRecord> records, String granularity);
}