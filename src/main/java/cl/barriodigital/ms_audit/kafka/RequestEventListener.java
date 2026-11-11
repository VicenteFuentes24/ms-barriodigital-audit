package cl.barriodigital.ms_audit.kafka;

import cl.barriodigital.ms_audit.service.EventProcessingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class RequestEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(RequestEventListener.class);
    private final EventCodec codec;
    private final EventProcessingService service;
    public RequestEventListener(EventCodec codec, EventProcessingService service) { this.codec = codec; this.service = service; }
    @KafkaListener(id="audit-history", topics="${audit.events-topic}", groupId="${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<byte[], byte[]> record, Acknowledgment ack) {
        var event = codec.decode(record.value(), record.key());
        boolean inserted = service.process(event, record.topic(), record.partition(), record.offset());
        // The proxied service has committed before acknowledging on the consumer thread.
        ack.acknowledge();
        LOG.info("audit_event_persisted eventId={} requestId={} requestVersion={} partition={} offset={} inserted={}",
                event.eventId(), event.requestId(), event.requestVersion(), record.partition(), record.offset(), inserted);
    }
}
