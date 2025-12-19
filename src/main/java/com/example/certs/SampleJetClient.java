package com.example.certs;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.ServiceFactories;
import com.hazelcast.jet.pipeline.ServiceFactory;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.map.IMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;

// Maybe move this to a unit test
public class SampleJetClient {

    private static KeyStore loadTrustStore() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream is = classLoader.getResourceAsStream("certs/tls-material/truststore-v1.p12")) {
            if (is == null) {
                throw new IllegalStateException("Unable to load trust store");
            }
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(is, "changeit".toCharArray());
            // Just for visual verification of content
            System.out.println("Truststore has " + trustStore.size() + " entries");
            return trustStore;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Pipeline buildPipeline() {

        KeyStore trustStore = loadTrustStore();
        // Kafka wants paths, not objects
        Path trustStorePath = null;
        try {
            trustStorePath = Files.createTempFile("truststore", ".p12");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (OutputStream os = Files.newOutputStream(trustStorePath)) {
            trustStore.store(os, "changeit".toCharArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Properties props = new Properties();
        props.setProperty("boostrap.servers", "localhost:9092");
        props.put("security-protocol", "SSL");
        props.put("ssl.truststore.type", "PKCS12");
        props.put("ssl.truststore.location", trustStorePath.toAbsolutePath().toString());
        props.put("ssl.truststore.password", "changeit");

        // NOTE: If we wanted to use the truststore within the pipeline, we
        // do not want to call loadTrustStore for every item in the pipeline.
        // Instead, use the service factory pattern:
        ServiceFactory<?, KeyStore> keyStoreService =
                ServiceFactories.sharedService(ctx -> loadTrustStore()
        );

        // Using in practice:
//       Pipeline p = Pipeline.create();
//       p.readFrom(TestSources.itemStream(100))
//               .withoutTimestamps()
//               .mapUsingService(keyStoreService, (truststore, item) -> {
//                   // use the truststore
//                   return item;
//       });

        // Declare the shared service; it will reload certs as neede
        ServiceFactory<?, SchemaRegistryClientService> schemaRegistryService =
                ServiceFactories.sharedService(ctx ->
                        new SchemaRegistryClientService(ctx.hazelcastInstance())
                );

        Pipeline p = Pipeline.create();
        // Just one item per second - we aren't stress testing here!
        p.readFrom(TestSources.itemStream(1) )
                .withoutTimestamps()
                .mapUsingService(
                        schemaRegistryService,
                        (service, item) -> {
                            Object client = service.getSchemaRegistryClient();
                            // use client to perform whatever operations
                            return item;
                        }
                )
                .writeTo(Sinks.logger());
        return p;

    };

    public static void main(String[] args) throws Exception {
        // Initially, at least, just do with with a single embedded node.
        Config config = new Config();
        config.getJetConfig().setEnabled(true);
        config.setLicenseKey(System.getenv("HZ_LICENSEKEY"));
        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);

        SampleJetClient client = new SampleJetClient(); // this class

        IMap<String,byte[]> tlsmap = hz.getMap("tls-material");
        // Write the initial certs to the map
        try (InputStream is =
                     client.getClass().getClassLoader()
                             .getResourceAsStream("certs/tls-material/truststore-v1.p12")) {
            if (is == null) {
                throw new RuntimeException("Unable to load v1 truststore");
            }

            byte[] bytes = is.readAllBytes();
            tlsmap.put("schema-registry-truststore", bytes);
            // This doesn't look like it would be a good practice, but in reality truststore
            // passwords are generally not considered secrets .. the trusted certificates are
            // public material and well-known passwords are commonly used.
            tlsmap.put("schema-registry-truststore-password", "changeit".getBytes(StandardCharsets.UTF_8));
        }

        Pipeline p = client.buildPipeline();
        URL resourceURL = client.getClass().getClassLoader().getResource("certs/tls-material/truststore-v1.p12");
        JobConfig jobConfig = new JobConfig();
        assert resourceURL != null;
        jobConfig.addClasspathResource(resourceURL);
        Job job = hz.getJet().newJob(p);

        // Let the job run with the initial certs for 10 seconds
        Thread.sleep(10_000);

        // Change the certs
        try (InputStream is =
                client.getClass().getClassLoader()
                        .getResourceAsStream("certs/tls-material/truststore-v2.p12")) {
            if (is == null) {
                throw new RuntimeException("Unable to load v2 truststore");
            }

            byte[] bytes = is.readAllBytes();
            tlsmap.put("schema-registry-truststore", bytes);
        }

        // Let the job run with the updated certs for 10 seconds
        Thread.sleep(10_000);
        job.cancel();
        // Need to manually check log output to see that TrustManager and SSLContext updated
        hz.shutdown();
    }
}
