package com.example.certs;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryUpdatedListener;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.logging.Logger;

/** Jet pipelines will use this via the ServiceFactory mechanism */
public final class SchemaRegistryClientService implements AutoCloseable {

    private final ReloadingTrustManager trustManager;
    private final ReloadableSslContext sslContext;

    // Placeholder - pretend this is a real client
    private volatile Object schemaRegistryClient;

    private final Logger logger = Logger.getLogger(SchemaRegistryClientService.class.getName());


    public SchemaRegistryClientService(HazelcastInstance hz) {
        this.trustManager = new ReloadingTrustManager();
        this.sslContext = new ReloadableSslContext(trustManager);

        reloadFromMap(hz.getMap("tls-material"));

        // Listen for updates
        hz.<String,byte[]>getMap("tls-material")
                .addEntryListener((EntryUpdatedListener<String,byte[]>) e -> {
                    if (e.getKey().startsWith("schema-registry")) {
                        reloadFromMap(hz.getMap("tls-material"));
                    }
                }, true);

        rebuildClient();
    }

    private synchronized void reloadFromMap(IMap<String, byte[]> map) {
        try {
            byte[] ksBytes = map.get("schema-registry-truststore");
            byte[] pwdBytes = map.get("schema-registry-truststore-password");

            if (ksBytes == null || pwdBytes == null) {
                throw new IllegalStateException("TLS material incomplete: truststore or password missing");
            }
            char[] pwd = new String(
                    pwdBytes, StandardCharsets.UTF_8).toCharArray();
            KeyStore keyStore = null;

            keyStore = KeyStore.getInstance("PKCS12");  // may need to support JKS also

            keyStore.load(new ByteArrayInputStream(ksBytes), pwd);

            trustManager.reload(keyStore);
            sslContext.rebuild();
            rebuildClient();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void rebuildClient() {
        this.schemaRegistryClient = new Object(); // placeholder
        logger.info("Schema Registry client rebuilt");
    }

    public Object getSchemaRegistryClient() {
        return schemaRegistryClient;
    }

    @Override
    public void close() throws Exception {
        // clean up client resources
    }
}
