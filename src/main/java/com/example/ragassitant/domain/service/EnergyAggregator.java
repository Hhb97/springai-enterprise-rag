package com.example.ragassitant.domain.service;

import com.example.ragassitant.domain.model.EnergyRecord;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EnergyAggregator {

    public Map<String, Double> aggregate(List<EnergyRecord> records, String granularity) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (records == null || records.isEmpty()) return result;

        ZoneId zone = ZoneId.systemDefault();
        String g = granularity == null ? "day" : granularity.toLowerCase(Locale.ROOT);

        for (EnergyRecord p : records) {
            if (p == null || p.getTime() == null) continue;
            try {
                LocalDate date;
                if (p.getTime().length() >= 10) {
                    date = LocalDate.parse(p.getTime().substring(0, 10));
                } else {
                    Instant instant = Instant.parse(p.getTime());
                    date = instant.atZone(zone).toLocalDate();
                }

                String key;
                switch (g) {
                    case "year":
                        key = String.valueOf(date.getYear());
                        break;
                    case "quarter":
                        int q = (date.getMonthValue() - 1) / 3 + 1;
                        key = date.getYear() + "-Q" + q;
                        break;
                    case "week":
                        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                        LocalDate sunday = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                        key = monday + " ~ " + sunday;
                        break;
                    case "month":
                        key = date.getYear() + "-" + String.format(Locale.ROOT, "%02d", date.getMonthValue());
                        break;
                    case "day":
                    default:
                        key = date.toString();
                        break;
                }

                double v = p.getValue() == null ? 0.0 : p.getValue();
                result.merge(key, v, Double::sum);
            } catch (Exception e) {
                // skip unparseable rows
            }
        }
        return result;
    }
}