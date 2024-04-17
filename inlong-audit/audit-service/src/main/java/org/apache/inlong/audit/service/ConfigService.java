/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.audit.service;

import org.apache.inlong.audit.config.Configuration;
import org.apache.inlong.audit.entities.JdbcConfig;
import org.apache.inlong.audit.utils.JdbcUtils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.inlong.audit.config.ConfigConstants.CACHE_PREP_STMTS;
import static org.apache.inlong.audit.config.ConfigConstants.DEFAULT_CACHE_PREP_STMTS;
import static org.apache.inlong.audit.config.ConfigConstants.DEFAULT_CONNECTION_TIMEOUT;
import static org.apache.inlong.audit.config.ConfigConstants.DEFAULT_DATASOURCE_POOL_SIZE;
import static org.apache.inlong.audit.config.ConfigConstants.DEFAULT_PREP_STMT_CACHE_SIZE;
import static org.apache.inlong.audit.config.ConfigConstants.DEFAULT_PREP_STMT_CACHE_SQL_LIMIT;
import static org.apache.inlong.audit.config.ConfigConstants.DEFAULT_UPDATE_CONFIG_INTERVAL_SECONDS;
import static org.apache.inlong.audit.config.ConfigConstants.KEY_CACHE_PREP_STMTS;
import static org.apache.inlong.audit.config.ConfigConstants.KEY_DATASOURCE_CONNECTION_TIMEOUT;
import static org.apache.inlong.audit.config.ConfigConstants.KEY_DATASOURCE_POOL_SIZE;
import static org.apache.inlong.audit.config.ConfigConstants.KEY_PREP_STMT_CACHE_SIZE;
import static org.apache.inlong.audit.config.ConfigConstants.KEY_PREP_STMT_CACHE_SQL_LIMIT;
import static org.apache.inlong.audit.config.ConfigConstants.KEY_UPDATE_CONFIG_INTERVAL_SECONDS;
import static org.apache.inlong.audit.config.ConfigConstants.PREP_STMT_CACHE_SIZE;
import static org.apache.inlong.audit.config.ConfigConstants.PREP_STMT_CACHE_SQL_LIMIT;
import static org.apache.inlong.audit.config.SqlConstants.DEFAULT_MYSQL_QUERY_AUDIT_ID_SQL;
import static org.apache.inlong.audit.config.SqlConstants.DEFAULT_MYSQL_QUERY_AUDIT_SOURCE_SQL;
import static org.apache.inlong.audit.config.SqlConstants.KEY_MYSQL_QUERY_AUDIT_ID_SQL;
import static org.apache.inlong.audit.config.SqlConstants.KEY_MYSQL_QUERY_AUDIT_SOURCE_SQL;

/**
 * ConfigService periodically pull the configuration of audit id and audit source from DB.
 */
public class ConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);

    private static volatile ConfigService configService = null;
    private CopyOnWriteArrayList<String> auditIds = new CopyOnWriteArrayList<>();
    protected final ScheduledExecutorService updateTimer = Executors.newSingleThreadScheduledExecutor();
    private DataSource dataSource;
    private ConcurrentHashMap<String, List<JdbcConfig>> auditSources = new ConcurrentHashMap<>();
    private final String queryAuditIdSql;
    private final String queryAuditSourceSql;

    public static ConfigService getInstance() {
        if (configService == null) {
            synchronized (ConfigService.class) {
                if (configService == null) {
                    configService = new ConfigService();
                }
            }
        }
        return configService;
    }

    private ConfigService() {
        queryAuditIdSql = Configuration.getInstance().get(KEY_MYSQL_QUERY_AUDIT_ID_SQL,
                DEFAULT_MYSQL_QUERY_AUDIT_ID_SQL);
        queryAuditSourceSql = Configuration.getInstance().get(KEY_MYSQL_QUERY_AUDIT_SOURCE_SQL,
                DEFAULT_MYSQL_QUERY_AUDIT_SOURCE_SQL);
    }

    public void start() {
        createDataSource();
        updateTimer.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                updateAuditIds();
                updateAuditSource();
            }
        }, 0,
                Configuration.getInstance().get(KEY_UPDATE_CONFIG_INTERVAL_SECONDS,
                        DEFAULT_UPDATE_CONFIG_INTERVAL_SECONDS),
                TimeUnit.SECONDS);
    }

    /**
     * Create data source.
     */
    private void createDataSource() {
        JdbcConfig jdbcConfig = JdbcUtils.buildMysqlConfig();
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(jdbcConfig.getDriverClass());
        config.setJdbcUrl(jdbcConfig.getJdbcUrl());
        config.setUsername(jdbcConfig.getUserName());
        config.setPassword(jdbcConfig.getPassword());
        config.setConnectionTimeout(Configuration.getInstance().get(KEY_DATASOURCE_CONNECTION_TIMEOUT,
                DEFAULT_CONNECTION_TIMEOUT));
        config.addDataSourceProperty(CACHE_PREP_STMTS,
                Configuration.getInstance().get(KEY_CACHE_PREP_STMTS, DEFAULT_CACHE_PREP_STMTS));
        config.addDataSourceProperty(PREP_STMT_CACHE_SIZE,
                Configuration.getInstance().get(KEY_PREP_STMT_CACHE_SIZE, DEFAULT_PREP_STMT_CACHE_SIZE));
        config.addDataSourceProperty(PREP_STMT_CACHE_SQL_LIMIT,
                Configuration.getInstance().get(KEY_PREP_STMT_CACHE_SQL_LIMIT, DEFAULT_PREP_STMT_CACHE_SQL_LIMIT));
        config.setMaximumPoolSize(
                Configuration.getInstance().get(KEY_DATASOURCE_POOL_SIZE,
                        DEFAULT_DATASOURCE_POOL_SIZE));
        dataSource = new HikariDataSource(config);
    }

    /**
     * Update audit ids.
     */
    private void updateAuditIds() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement pstat = connection.prepareStatement(queryAuditIdSql)) {
            if (connection.isClosed()) {
                createDataSource();
            }

            try (ResultSet resultSet = pstat.executeQuery()) {
                CopyOnWriteArrayList<String> auditIdList = new CopyOnWriteArrayList<>();
                while (resultSet.next()) {
                    String auditId = resultSet.getString(1);
                    LOGGER.info("Update audit id {}", auditId);
                    auditIdList.add(auditId);
                }
                if (!auditIdList.isEmpty()) {
                    auditIds = auditIdList;
                }
            } catch (SQLException sqlException) {
                LOGGER.error("Query has SQL exception! ", sqlException);
            }

        } catch (Exception exception) {
            LOGGER.error("Query has exception! ", exception);
        }
    }

    /**
     * Update audit source.
     */
    private void updateAuditSource() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement pstat = connection.prepareStatement(queryAuditSourceSql)) {
            if (connection.isClosed()) {
                createDataSource();
            }
            try (ResultSet resultSet = pstat.executeQuery()) {
                ConcurrentHashMap<String, List<JdbcConfig>> auditSource = new ConcurrentHashMap<>();
                while (resultSet.next()) {
                    JdbcConfig data = new JdbcConfig(resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getString(4));
                    String serviceId = resultSet.getString(5);
                    List<JdbcConfig> config = auditSource.computeIfAbsent(serviceId, k -> new LinkedList<>());
                    config.add(data);
                    LOGGER.info("Update audit source service id = {}, jdbc config = {}", serviceId, data);
                }
                if (!auditSource.isEmpty()) {
                    auditSources = auditSource;
                }
            } catch (SQLException sqlException) {
                LOGGER.error("Query has SQL exception! ", sqlException);
            }
        } catch (Exception exception) {
            LOGGER.error("Query has exception! ", exception);
        }
    }

    /**
     * Get audit ids.
     *
     * @return
     */
    public List<String> getAuditIds() {
        return auditIds = auditIds == null ? new CopyOnWriteArrayList<>() : auditIds;
    }

    /**
     * Get all audit source.
     *
     * @return
     */
    public List<JdbcConfig> getAllAuditSource() {
        List<JdbcConfig> sourceList = new LinkedList<>();
        for (Map.Entry<String, List<JdbcConfig>> entry : auditSources.entrySet()) {
            sourceList.addAll(entry.getValue());
        }
        return sourceList;
    }

    /**
     * Get audit source by service id.
     *
     * @param serviceId
     * @return
     */
    public List<JdbcConfig> getAuditSourceByServiceId(String serviceId) {
        return auditSources.get(serviceId);
    }
}
