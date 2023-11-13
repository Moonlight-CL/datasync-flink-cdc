package com.aws.ana;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.aws.ana.kafka.partinioner.FlinkCDCPartitionerByKeyHashCode;
import com.aws.ana.kafka.serialization.CDCKafkaKeySerializationSchema;
import com.aws.ana.kafka.serialization.CDCKafkaValueSerializationSchema;
import com.aws.ana.kafka.topicselector.CDCKafkaTopicSelector;
import com.aws.ana.model.CDCKafkaModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ververica.cdc.connectors.mongodb.MongoDBSource;
import com.ververica.cdc.debezium.DebeziumSourceFunction;
import com.ververica.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.connector.aws.config.AWSConfigConstants;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kinesis.sink.KinesisStreamsSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class MongoCDCMsk {
    private static String GROUP_NAME_MONGO_CDC = "MongoSrcCDC";
    private static String GROUP_NAME_MSK_CDC = "MSKTargetCDC";
    private static String GROUP_NAME_KDS_CDC = "KDSTargetCDC";

    private static Logger log = LoggerFactory.getLogger(MongoCDCMsk.class);

    public static void main(String[] args) throws Exception {

        Map<String, Properties> appProperties = KinesisAnalyticsRuntime.getApplicationProperties();
        Properties srcProp = appProperties.get(GROUP_NAME_MONGO_CDC);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<CDCKafkaModel> mongoSource = createMongoSource(env, srcProp);

        String sinkTarget = srcProp.getProperty("sinkTarget", "msk");

        if ("msk".equals(sinkTarget)) {

            Properties sinkProp = appProperties.get(GROUP_NAME_MSK_CDC);
            log.info("MongoCDC2MSK: sinkProp: {}", sinkProp);
            mongoSource.sinkTo(createKafkaSink(sinkProp));
            log.info("---MongoCDC2MSK: begin to execute job---");

        } else if ("kds".equals(sinkTarget)) {
            Properties sinkProp = appProperties.get(GROUP_NAME_KDS_CDC);
            log.info("MongoCDC2KDS: sinkProp: {}", sinkProp);
            mongoSource.sinkTo(createKinesisStreamSink(sinkProp));
            log.info("---MongoCDC2KDS: begin to execute job---");
        }

        env.execute("Mongo Full Load & CDC MSK/KDS");
    }

    private static DataStream<CDCKafkaModel> createMongoSource(StreamExecutionEnvironment env, Properties prop) throws IOException {
        log.info("createMongoSource srcProp: {}", prop.toString());

        String host = prop.getProperty("host");
        String userName = prop.getProperty("userName");
        String password = prop.getProperty("password");
        String databases = prop.getProperty("databases");
        String collections = prop.getProperty("collections");

        Boolean copyExisting = Boolean.valueOf(prop.getProperty("copyExisting", "true"));

        MongoDBSource.Builder<String> mongoSrcBuilder = MongoDBSource.<String>builder()
                .hosts(host)
                .username(userName)
                .password(password)
                .copyExisting(copyExisting)
                .deserializer(new JsonDebeziumDeserializationSchema());

        if (databases != null) {
            mongoSrcBuilder.databaseList(databases.split(","));
        }

        if (collections != null) {
            mongoSrcBuilder.collectionList(collections.split(","));
        }

        DebeziumSourceFunction<String> srcFunc = mongoSrcBuilder.build();

        DataStream<CDCKafkaModel> ds = env
                .addSource(srcFunc)
                .rebalance()
                .map(line -> {
                    JsonElement rootEle = JsonParser.parseString(line);
                    JsonElement nsEle = rootEle.getAsJsonObject().get("ns");

                    String db = nsEle.getAsJsonObject().get("db").getAsString();
                    String coll = nsEle.getAsJsonObject().get("coll").getAsString();
                    String pkVal = "no_pk";

                    if (rootEle.getAsJsonObject().has("documentKey")) {
                        String documentKeyStr = rootEle.getAsJsonObject().get("documentKey").getAsString();

                        JsonObject dkJson = JsonParser.parseString(documentKeyStr).getAsJsonObject();
                        pkVal = dkJson.get("_id").toString();
                    }

                    String partitionKey = String.format("%s.%s.%s", db, coll, pkVal);
                    return CDCKafkaModel.of(db, coll, partitionKey, line);
                });

        return ds;
    }

    private static KafkaSink<CDCKafkaModel> createKafkaSink(Properties properties) {

        log.info("createKafkaSink: sinkProp: {}", properties.toString());

        Properties producerProp = new Properties();
        producerProp.setProperty("acks", "all");
        producerProp.setProperty("transaction.timeout.ms", "300000");

        DeliveryGuarantee dg = DeliveryGuarantee.EXACTLY_ONCE;
        if ("at-least-once".equals(properties.getProperty("deliveryGuarantee"))) {
            dg = DeliveryGuarantee.AT_LEAST_ONCE;
        }

        String kafkaBrokers = properties.getProperty("kafkaBrokers");
        String perDBTopicKeyName = "perDBTopicPrefix";
        boolean perDBTopic = properties.containsKey(perDBTopicKeyName);

        if (!perDBTopic) {
            String topic = properties.getProperty("topic");
            return KafkaSink.<CDCKafkaModel>builder()
                    .setDeliverGuarantee(dg)
                    .setBootstrapServers(kafkaBrokers)
                    .setKafkaProducerConfig(producerProp)
                    .setRecordSerializer(KafkaRecordSerializationSchema
                            .builder()
                            .setPartitioner(new FlinkCDCPartitionerByKeyHashCode())
                            .setKeySerializationSchema(new CDCKafkaKeySerializationSchema())
                            .setValueSerializationSchema(new CDCKafkaValueSerializationSchema())
                            .setTopic(topic)
                            .build())
                    .build();
        } else {

            String topicPrefix = properties.getProperty(perDBTopicKeyName);
            return KafkaSink.<CDCKafkaModel>builder()
                    .setDeliverGuarantee(dg)
                    .setBootstrapServers(kafkaBrokers)
                    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                            .setTopicSelector(new CDCKafkaTopicSelector(topicPrefix))
                            .setPartitioner(new FlinkCDCPartitionerByKeyHashCode())
                            .setKeySerializationSchema(new CDCKafkaKeySerializationSchema())
                            .setValueSerializationSchema(new CDCKafkaValueSerializationSchema())
                            .build()
                    ).build();
        }
    }

    /**
     * create kds sink
     *
     * @param properties
     * @return
     */
    private static KinesisStreamsSink<CDCKafkaModel> createKinesisStreamSink(Properties properties) {

        String streamName = properties.getProperty("streamName");
        String region = properties.getProperty("region");

        Properties clientProps = new Properties();
        clientProps.setProperty(AWSConfigConstants.AWS_REGION, region);

        return KinesisStreamsSink.<CDCKafkaModel>builder()
                .setStreamName(streamName)
                .setKinesisClientProperties(clientProps)
                .setSerializationSchema(model -> model.getValue().getBytes())
                .setPartitionKeyGenerator(model -> model.getPartitionKey())
                .setFailOnError(false)
                .build();
    }
}
