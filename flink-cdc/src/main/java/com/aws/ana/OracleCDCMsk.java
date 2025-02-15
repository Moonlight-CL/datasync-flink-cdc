package com.aws.ana;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.aws.ana.kafka.partinioner.FlinkCDCPartitionerByKeyHashCode;
import com.aws.ana.kafka.serialization.CDCKafkaKeySerializationSchema;
import com.aws.ana.kafka.serialization.CDCKafkaValueSerializationSchema;
import com.aws.ana.kafka.topicselector.CDCKafkaTopicSelector;
import com.aws.ana.model.CDCKafkaModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.cdc.connectors.base.options.StartupOptions;
import org.apache.flink.cdc.connectors.oracle.source.OracleSourceBuilder;
import org.apache.flink.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class OracleCDCMsk {

    private static String GROUP_NAME_ORACLE_CDC = "OracleSrcCDC";
    private static String GROUP_NAME_MSK_CDC = "MSKTargetCDC";

    private static String PK_NAME = "ID";

    private static Logger log = LoggerFactory.getLogger(OracleCDCMsk.class);

    public static void main(String[] args) throws Exception {
        Map<String, Properties> appProperties = KinesisAnalyticsRuntime.getApplicationProperties();
        Properties srcProp = appProperties.get(GROUP_NAME_ORACLE_CDC);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<CDCKafkaModel> mySQLSource = createOracleSourceStream(env, srcProp);

        Properties sinkProp = appProperties.get(GROUP_NAME_MSK_CDC);
        mySQLSource.sinkTo(createKafkaSink(sinkProp));
        log.info("OracleCDC2MSK: begin to execute job");

        env.execute("Oracle Full Load & CDC MSK");
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
                    .setDeliveryGuarantee(dg)
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
                    .setDeliveryGuarantee(dg)
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

    private static DataStream<CDCKafkaModel> createOracleSourceStream(StreamExecutionEnvironment env, Properties config) {
        log.info("createOracleSourceStream srcProp: {}", config.toString());

        Properties debeziumProperties = new Properties();
        debeziumProperties.setProperty("log.mining.strategy", "online_catalog");

        String host = config.getProperty("host");
        int port = Integer.parseInt(config.getProperty("port"));
        String db = config.getProperty("db");
        String schemas = config.getProperty("schemas");
        String tables = config.getProperty("tables");
        String dbUser = config.getProperty("dbUser");
        String dbPwd = config.getProperty("dbPwd");
        String pdb = config.getProperty("pdb");
        String startupMode = config.getProperty("startupMode", "initial");
        boolean stringifyData = Boolean.parseBoolean(config.getProperty("stringifyData", "false"));

        if (pdb != null && !pdb.isEmpty()) {
            debeziumProperties.setProperty("database.pdb.name", pdb);
        }

        StartupOptions supOpt = null;

        switch (startupMode) {
            case "initial":
                supOpt = StartupOptions.initial();
                break;
            case "latest-offset":
                supOpt = StartupOptions.latest();
                break;
        }

        OracleSourceBuilder.OracleIncrementalSource<String> source =
                new OracleSourceBuilder<String>()
                        .hostname(host)
                        .port(port)
                        .databaseList(db)
                        .schemaList(schemas)
                        .tableList(tables)
                        .username(dbUser)
                        .password(dbPwd)
                        .deserializer(new JsonDebeziumDeserializationSchema())
                        .includeSchemaChanges(true) // output the schema changes as well
                        .startupOptions(supOpt)
                        .debeziumProperties(debeziumProperties)
                        .splitSize(2)
                        .build();

        DataStream<CDCKafkaModel> ds = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "Oracle Source")
                .rebalance()
                .map(line -> {
                    log.info("oracle cdc line: {}", line);
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

                    if (stringifyData) {
                        JsonElement beforeEle = rootEle.getAsJsonObject().get("before");
                        JsonElement afterEle = rootEle.getAsJsonObject().get("after");
                        if (!beforeEle.isJsonNull()) {
                            rootEle.getAsJsonObject().addProperty("before", beforeEle.toString());
                        }
                        if (!afterEle.isJsonNull()) {
                            rootEle.getAsJsonObject().addProperty("after", afterEle.toString());
                        }
                    }

                    String partitionKey = String.format("%s.%s.%s", dbName, tblName, pkValue);
                    return CDCKafkaModel.of(dbName, tblName, partitionKey, rootEle.toString());
                });

        return ds;
    }

}
