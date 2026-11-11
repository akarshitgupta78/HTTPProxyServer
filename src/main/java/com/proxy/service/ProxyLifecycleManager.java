package com.proxy.service;

import com.proxy.core.ProxyServer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

@Service
public class ProxyLifecycleManager implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final ProxyServer proxyServer;
    private Thread workerThread;

    public ProxyLifecycleManager(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        workerThread = Thread.ofVirtual().name("proxy-server-main").start(proxyServer);
        System.out.println("[ProxyLifecycleManager] Started ProxyServer engine on port " + proxyServer.getPort());
    }

    @Override
    public void destroy() {
        if (proxyServer != null) {
            System.out.println("[ProxyLifecycleManager] Shutting down ProxyServer engine...");
            proxyServer.stop();
        }
    }
}

