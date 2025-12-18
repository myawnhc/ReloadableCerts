High-level overview:

- Cert info is stored in Hazelcast IMap 'tls-material', which can be updated dynamically
- ReloadingTrustManager, an X509TrustManager implementation that can be refreshed as needed by triggering its reload() method
- ReloadableSslContext, with rebuild() method to reinitialize when trust manager changes
- Multiple Client services for each service that requires authentication
  - SchemaRegistryClientService is implemented as a prototype, others will follow same pattern
- Multiple pipelines as needed
  - A pipeline can access one or more client services via Jet shared ServiceFactory mechanism

Advantages of this approach:
- IMap is designed to shared data across nodes, so all members have access to the Cert info
- No cluster restart needed when certs are updated
- No job restart needed when certs are updated
- Separation of concerns - TLS certs used by Hazelcast separate from those used by services
- Works with Kafka, Avro, others 

What happens when new cert written to map:
- EntryListener fires
- TrustManager reloads
- SSLContext rebuilt
- Service Client reinitialized 
- Next outbound call from pipeline (via Service Client) uses new cert

No need to restart cluster or Jet job, but initial version does require intervention to put
the new cert into the map.  Next phase will automate this as well. 