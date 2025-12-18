package com.example.certs;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.util.logging.Logger;

public final class ReloadableSslContext {

    private final ReloadingTrustManager trustManager;
    private volatile SSLContext sslContext;

    private final Logger logger = Logger.getLogger(ReloadableSslContext.class.getName());


    public ReloadableSslContext(ReloadingTrustManager trustManager) {
        this.trustManager = trustManager;
        rebuild();
    }

    public synchronized void rebuild() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trustManager}, null);
            this.sslContext = ctx;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        logger.info("SSLContext.rebuild() complete");
    }

    public SSLContext get() {
        return sslContext;
    }
}
