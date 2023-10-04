package com.aws.ana.kafka.serialization;

import com.aws.ana.model.CDCKafkaModel;
import org.apache.flink.api.common.serialization.SerializationSchema;

public class CDCKafkaValueSerializationSchema implements SerializationSchema<CDCKafkaModel> {
    @Override
    public byte[] serialize(CDCKafkaModel record) {
        return record.getValue().getBytes();
    }
}
