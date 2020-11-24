package io.confluent.developer;

import io.confluent.demo.Inputdatat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.text.ParseException;
import java.text.SimpleDateFormat;

public class EventTimeExtractor implements TimestampExtractor {
    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String eventTime = ((Inputdatat)record.value()).getTimestamp();

        try {
            return sdf.parse(eventTime).getTime();
        } catch(ParseException e) {
            return 0;
        }
    }
}
