package com.example.certs;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.ServiceFactories;
import com.hazelcast.jet.pipeline.ServiceFactory;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.map.IMap;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// Maybe move this to a unit test
public class SampleJetClient {

    private Pipeline buildPipeline() {

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
