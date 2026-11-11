package cl.barriodigital.ms_audit.service;

import cl.barriodigital.ms_audit.dto.RequestEventV1;
import cl.barriodigital.ms_audit.kafka.EventCodec;
import cl.barriodigital.ms_audit.repository.EventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventProcessingService {
    private final EventStore store;
    private final EventCodec codec;
    public EventProcessingService(EventStore store, EventCodec codec) { this.store = store; this.codec = codec; }
    @Transactional(timeout=30)
    public boolean process(RequestEventV1 event, String topic, int partition, long offset) {
        // History accepts older distinct versions; there is no "current version" projection.
        return store.append(event, codec.encode(event), topic, partition, offset);
    }
}
