package com.example.ragassitant.infrastructure.config;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Pong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class InfluxDBInfraConfig {

    private static final Logger log = LoggerFactory.getLogger(InfluxDBInfraConfig.class);

    @Value("${influxdb.url}")
    private String url;

    @Value("${influxdb.username}")
    private String username;

    @Value("${influxdb.password}")
    private String password;

    @Value("${influxdb.database}")
    private String database;

    @Value("${influxdb.retention-policy:autogen}")
    private String retentionPolicy;

    @Value("${influxdb.timeout-seconds:30}")
    private int timeoutSeconds;

    @Bean
    public InfluxDB influxDB() {
        log.info("🔌 正在连接 InfluxDB: {}", url);
        try {
            InfluxDB influxDB = InfluxDBFactory.connect(url, username, password);
            influxDB.setDatabase(database);
            influxDB.setRetentionPolicy(retentionPolicy);
            influxDB.enableBatch(2000, 100, TimeUnit.MILLISECONDS);
            influxDB.enableGzip();

            Pong pong = influxDB.ping();
            log.info("✅ InfluxDB 连接成功 | 响应时间: {}ms | 版本: {}",
                    pong.getResponseTime(), pong.getVersion());

            return influxDB;
        } catch (Exception e) {
            log.warn("⚠️ InfluxDB 连接失败: {} - 请检查配置是否正确", e.getMessage());
            return null;
        }
    }
}