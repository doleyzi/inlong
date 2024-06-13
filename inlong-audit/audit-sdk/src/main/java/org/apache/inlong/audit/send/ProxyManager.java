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

package org.apache.inlong.audit.send;

import org.apache.inlong.audit.entity.AuditComponent;
import org.apache.inlong.audit.entity.AuditProxy;
import org.apache.inlong.audit.entity.AuthConfig;
import org.apache.inlong.audit.entity.CommonResponse;
import org.apache.inlong.audit.utils.HttpUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProxyManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyManager.class);
    private static final ProxyManager instance = new ProxyManager();
    private final List<String> currentIpPorts = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final static String GET_AUDIT_PROXY_API_PATH = "/inlong/manager/openapi/audit/getAuditProxy";
    private int timeoutMs = 10000;
    private boolean autoUpdateAuditProxy = false;
    private int updateInterval = 60000;
    private AuthConfig authConfig;
    private String auditProxyApiUrl;
    private AuditComponent component;
    private volatile boolean timerStarted = false;

    private ProxyManager() {
    }

    public static ProxyManager getInstance() {
        return instance;
    }

    /**
     * update config
     */
    public synchronized void setAuditProxy(HashSet<String> ipPortList) {
        if (!ipPortList.equals(new HashSet<>(currentIpPorts))) {
            currentIpPorts.clear();
            currentIpPorts.addAll(ipPortList);
        }
    }

    public synchronized void setManagerConfig(AuditComponent component, String managerHost, AuthConfig authConfig) {
        if (!managerHost.endsWith("/")) {
            managerHost = managerHost + "/";
        }
        if (!(managerHost.startsWith("http://") || managerHost.startsWith("https://"))) {
            managerHost = "http://" + managerHost;
        }
        auditProxyApiUrl = String.format("%s%s", managerHost, GET_AUDIT_PROXY_API_PATH);
        LOGGER.info("Audit Proxy API URL: {}", auditProxyApiUrl);

        this.authConfig = authConfig;
        this.component = component;

        updateAuditProxy();

        if (autoUpdateAuditProxy) {
            startTimer();
            LOGGER.info("Auto Update from manager");
        }
    }

    private void updateAuditProxy() {
        String response = HttpUtils.httpGet(component.getComponent(), auditProxyApiUrl, authConfig, timeoutMs);
        if (response == null) {
            LOGGER.error("Response is null: {} {} {} ", component.getComponent(), auditProxyApiUrl, authConfig);
            return;
        }
        CommonResponse<AuditProxy> commonResponse =
                CommonResponse.fromJson(response, AuditProxy.class);
        if (commonResponse == null) {
            LOGGER.error("No data in the response: {} {} {} ", component.getComponent(), auditProxyApiUrl, authConfig);
            return;
        }
        HashSet<String> proxyList = new HashSet<>();
        for (AuditProxy auditProxy : commonResponse.getData()) {
            proxyList.add(auditProxy.toString());
        }
        setAuditProxy(proxyList);
        LOGGER.info("Get audit proxy from manager: {}", proxyList);
    }

    private synchronized void startTimer() {
        if (timerStarted) {
            return;
        }
        timer.scheduleWithFixedDelay(this::updateAuditProxy,
                0,
                updateInterval,
                TimeUnit.MILLISECONDS);
        timerStarted = true;
    }

    public void setManagerTimeout(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setAutoUpdateAuditProxy(boolean autoUpdateAuditProxy) {
        this.autoUpdateAuditProxy = autoUpdateAuditProxy;
    }

    public void setUpdateInterval(int updateInterval) {
        this.updateInterval = updateInterval;
    }

    public InetSocketAddress getInetSocketAddress() {
        if (currentIpPorts.isEmpty()) {
            return null;
        }
        Random rand = new Random();
        String randomElement = currentIpPorts.get(rand.nextInt(currentIpPorts.size()));
        String[] ipPort = randomElement.split(":");
        if (ipPort.length != 2) {
            LOGGER.error("Invalid IP:Port format: {}", randomElement);
            return null;
        }
        return new InetSocketAddress(ipPort[0], Integer.parseInt(ipPort[1]));
    }
}
