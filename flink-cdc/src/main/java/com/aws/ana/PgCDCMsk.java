package com.aws.ana;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.aws.ana.kafka.partinioner.FlinkCDCPartitionerByKeyHashCode;
import com.aws.ana.kafka.serialization.CDCKafkaKeySerializationSchema;
import com.aws.ana.kafka.serialization.CDCKafkaValueSerializationSchema;
import com.aws.ana.kafka.topicselector.CDCKafkaTopicSelector;
import com.aws.ana.model.CDCKafkaModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ververica.cdc.connectors.postgres.PostgreSQLSource;
import com.ververica.cdc.debezium.DebeziumSourceFunction;
import com.ververica.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class PgCDCMsk {
    private static String GROUP_NAME_PG_CDC = "PgSrcCDC";
    private static String GROUP_NAME_MSK_CDC = "MSKTargetCDC";

    private static String PK_NAME = "id";

    private static Logger log = LoggerFactory.getLogger(PgCDCMsk.class);

    public static void main(String[] args) throws Exception {
        Map<String, Properties> appProperties = KinesisAnalyticsRuntime.getApplicationProperties();
        Properties srcProp = appProperties.get(GROUP_NAME_PG_CDC);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<CDCKafkaModel> pgSource = createPgSourceStream(env, srcProp);

        Properties sinkProp = appProperties.get(GROUP_NAME_MSK_CDC);
        pgSource.sinkTo(createKafkaSink(sinkProp));
        log.info("PgCDC2MSK: begin to execute job");

        env.execute("PG Full Load & CDC MSK");
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

    private static DataStream<CDCKafkaModel> createPgSourceStream(StreamExecutionEnvironment env, Properties config) {
        log.info("createPgSourceStream srcProp: {}", config.toString());

        String host = config.getProperty("host");
        int port = Integer.valueOf(config.getProperty("port"));
        String db = config.getProperty("db");
        String schemas = config.getProperty("schemas");
        String tables = config.getProperty("tables");
        String dbUser = config.getProperty("dbUser");
        String dbPwd = config.getProperty("dbPwd");
        String pluginName = "pgoutput";

        //Incremental Snapshot Reading is Experimental, we use source function builder
        DebeziumSourceFunction<String> source = PostgreSQLSource.<String>builder()
                .hostname(host)
                .port(port)
                .database(db)
                .schemaList(schemas)
                .tableList(tables)
                .username(dbUser)
                .password(dbPwd)
                .decodingPluginName(pluginName)
                .deserializer(new JsonDebeziumDeserializationSchema(false))
                .build();

        DataStream<CDCKafkaModel> ds = env
                .addSource(source)
                .rebalance()
                .map(line -> {
                    JsonElement rootEle = JsonParser.parseString(line);
                    JsonElement srcEle = rootEle.getAsJsonObject().get("source");
                    String dbName = srcEle.getAsJsonObject().get("db").getAsString();
                    String tblName = srcEle.getAsJsonObject().get("table").getAsString();
                    String op = rootEle.getAsJsonObject().get("op").getAsString();

                    // assume the primary id Name is: id, you can use configuration to specify the pk name
                    String pkValue = "";
                    if (op == "d") {
                        pkValue = rootEle.getAsJsonObject().get("before").getAsJsonObject().get(PK_NAME).getAsString();
                    } else {
                        pkValue = rootEle.getAsJsonObject().get("after").getAsJsonObject().get(PK_NAME).getAsString();
                    }

                    JsonElement beforeEle = rootEle.getAsJsonObject().get("before");
                    JsonElement afterEle = rootEle.getAsJsonObject().get("after");
                    if (!beforeEle.isJsonNull()) {
                        rootEle.getAsJsonObject().addProperty("before", beforeEle.toString());
                    }
                    if (!afterEle.isJsonNull()) {
                        rootEle.getAsJsonObject().addProperty("after", afterEle.toString());
                    }

                    String partitionKey = String.format("%s.%s.%s", dbName, tblName, pkValue);
                    return CDCKafkaModel.of(dbName, tblName, partitionKey, rootEle.toString());
                });

        return ds;
    }
}
