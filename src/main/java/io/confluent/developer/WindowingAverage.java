package io.confluent.developer;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Time;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import io.confluent.demo.CountAndSum;
import io.confluent.demo.Averagesw;
import io.confluent.demo.Inputdatat;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

import static io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static java.lang.Integer.parseInt;
import static java.lang.Short.parseShort;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static org.apache.kafka.common.serialization.Serdes.Double;
import static org.apache.kafka.common.serialization.Serdes.Long;
import static org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.REPLICATION_FACTOR_CONFIG;
import static org.apache.kafka.streams.kstream.Grouped.with;
import static org.apache.kafka.streams.kstream.WindowedSerdes.timeWindowedSerdeFrom;

public class WindowingAverage {

    //region buildStreamsProperties
    protected Properties buildStreamsProperties(Properties envProps) {
        Properties config = new Properties();
        config.putAll(envProps);

        config.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, EventTimeExtractor.class);
        config.put(APPLICATION_ID_CONFIG, envProps.getProperty("application.id"));
        config.put(BOOTSTRAP_SERVERS_CONFIG, envProps.getProperty("bootstrap.servers"));
        //config.put(DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        config.put(DEFAULT_VALUE_SERDE_CLASS_CONFIG, Double().getClass());
        config.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, envProps.getProperty("schema.registry.url"));

        config.put(REPLICATION_FACTOR_CONFIG, envProps.getProperty("default.topic.replication.factor"));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, envProps.getProperty("offset.reset.policy"));

        config.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);

        return config;
    }
    //endregion

    //region createTopics

    /**
     * Create topics using AdminClient API
     */
    private void createTopics(Properties envProps) {
        Map<String, Object> config = new HashMap<>();

        config.put("bootstrap.servers", envProps.getProperty("bootstrap.servers"));
        AdminClient client = AdminClient.create(config);

        List<NewTopic> topics = new ArrayList<>();

        topics.add(new NewTopic(
                envProps.getProperty("input.measures.topic.name"),
                parseInt(envProps.getProperty("input.measures.topic.partitions")),
                parseShort(envProps.getProperty("input.measures.topic.replication.factor"))));

        topics.add(new NewTopic(
                envProps.getProperty("output.measure-averages.topic.name"),
                parseInt(envProps.getProperty("output.measure-averages.topic.partitions")),
                parseShort(envProps.getProperty("output.measure-averages.topic.replication.factor"))));

        topics.add(new NewTopic(
               "prova",
                parseInt(envProps.getProperty("output.measure-averages.topic.partitions")),
                parseShort(envProps.getProperty("output.measure-averages.topic.replication.factor"))));

        client.createTopics(topics);
        client.close();

    }
    //endregion

    private void run(String confFile) throws Exception {

        Properties envProps = this.loadEnvProperties(confFile);
        Properties streamProps = this.buildStreamsProperties(envProps);
        Topology topology = this.buildTopology(new StreamsBuilder(), envProps);

        this.createTopics(envProps);

        final KafkaStreams streams = new KafkaStreams(topology, streamProps);
        final CountDownLatch latch = new CountDownLatch(1);

        // Attach shutdown handler to catch Control-C.
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close(Duration.ofSeconds(5));
                latch.countDown();
            }
        });

        try {
            streams.cleanUp();
            streams.start();
            latch.await();


        } catch (Throwable e) {
            System.exit(1);
        }
        System.exit(0);
    }

    protected static void getMeasureAverageTable(KStream<String, Inputdatat> measures,
                                                                   TimeWindows window,
                                                                   String avgMeasuresTopicName,
                                                                   SpecificAvroSerde<Inputdatat> inputdatatSerde,
                                                                   SpecificAvroSerde<CountAndSum> countAndSumSerde,
                                                                   SpecificAvroSerde<Averagesw> averagesSerde) {

        // Grouping Ratings
        KGroupedStream<String, Integer> measuresById = measures
                .map((key, value) -> new KeyValue<>(value.getKey(), value.getVal()))
                .groupByKey(with(Serdes.String(),Serdes.Integer()));


                measuresById.windowedBy(window)
                        .aggregate(() -> new CountAndSum(0L, 0.0),
                        (key, value, aggregate) -> {
                            aggregate.setCount(aggregate.getCount() + 1);
                            aggregate.setSum(aggregate.getSum() + value);
                            return aggregate;
                        },
                        Materialized.with(Serdes.String(), countAndSumSerde))
                        .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                        //.toStream()
                        //.to("prova",Produced.with(WindowedSerdes.timeWindowedSerdeFrom(String.class), countAndSumSerde));
                        .mapValues(value -> value.getSum() / value.getCount(),
                                Materialized.with(WindowedSerdes.timeWindowedSerdeFrom(String.class),Serdes.Double()))
                        .toStream()
                        .map((Windowed<String> key, Double value) -> new KeyValue<>(key.key(), Averagesw.newBuilder()
                                .setAverage(value)
                                .setWindowHash(key.window().hashCode())
                                .setWindowStart(convertDataFormat(key.window().startTime()))
                                .setWindowEnd(convertDataFormat(key.window().endTime()))
                                .build()))
                        .to(avgMeasuresTopicName, Produced.with(Serdes.String(),averagesSerde));

               // persist the result in topic


        //return measureCountAndSum;
    }

    //region buildTopology
    private Topology buildTopology(StreamsBuilder bldr,
                                   Properties envProps) {

        final String measureTopicName = envProps.getProperty("input.measures.topic.name");
        final String avgMeasuresTopicName = envProps.getProperty("output.measure-averages.topic.name");
        TimeWindows windows = TimeWindows.of(Duration.ofSeconds(1)).grace(Duration.ZERO);

        KStream<String, Inputdatat> measureStream = bldr.stream(measureTopicName,
                Consumed.with(Serdes.String(), getInputdatatSerde(envProps))
                        .withTimestampExtractor(new EventTimeExtractor()));

        getMeasureAverageTable(measureStream, windows, avgMeasuresTopicName, getInputdatatSerde(envProps),getCountAndSumSerde(envProps),getAveragesSerde(envProps));

        // finish the topology
        return bldr.build();
    }
    //endregion

    public static SpecificAvroSerde<CountAndSum> getCountAndSumSerde(Properties envProps) {
        SpecificAvroSerde<CountAndSum> serde = new SpecificAvroSerde<>();
        serde.configure(getSerdeConfig(envProps), false);
        return serde;
    }

    public static SpecificAvroSerde<Inputdatat> getInputdatatSerde(Properties envProps) {
        SpecificAvroSerde<Inputdatat> inputdatatSerde = new SpecificAvroSerde<>();

        final HashMap<String, String> serdeConfig = new HashMap<>();
        serdeConfig.put(SCHEMA_REGISTRY_URL_CONFIG,
                envProps.getProperty("schema.registry.url"));

        inputdatatSerde.configure(serdeConfig, false);
        return inputdatatSerde;

    }

    public static SpecificAvroSerde<Averagesw> getAveragesSerde(Properties envProps) {
        SpecificAvroSerde<Averagesw> averageAvroSerde = new SpecificAvroSerde<>();

        final HashMap<String, String> serdeConfig = new HashMap<>();
        serdeConfig.put(SCHEMA_REGISTRY_URL_CONFIG,
                envProps.getProperty("schema.registry.url"));

        averageAvroSerde.configure(serdeConfig, false);
        return averageAvroSerde;
    }

    protected static Map<String, String> getSerdeConfig(Properties config) {
        final HashMap<String, String> map = new HashMap<>();

        final String srUrlConfig = config.getProperty(SCHEMA_REGISTRY_URL_CONFIG);
        map.put(SCHEMA_REGISTRY_URL_CONFIG, ofNullable(srUrlConfig).orElse(""));
        return map;
    }

    private Properties loadEnvProperties(String fileName) throws IOException {
        Properties envProps = new Properties();
        FileInputStream input = new FileInputStream(fileName);
        envProps.load(input);
        input.close();

        return envProps;
    }

    private static String convertDataFormat(Instant date){

        LocalDateTime ldt = LocalDateTime.ofInstant(date, ZoneOffset.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        String finalDate = ldt.format(formatter);

        return finalDate;

    }

    public static void main(String[] args)throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("This program takes one argument: the path to an environment configuration file.");
        }
        new WindowingAverage().run(args[0]);
    }
}
