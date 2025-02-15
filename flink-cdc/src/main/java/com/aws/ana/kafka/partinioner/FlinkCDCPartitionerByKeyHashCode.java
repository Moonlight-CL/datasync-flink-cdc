package com.aws.ana.kafka.partinioner;

import com.aws.ana.model.CDCKafkaModel;
import org.apache.flink.connector.kafka.sink.KafkaPartitioner;

public class FlinkCDCPartitionerByKeyHashCode implements KafkaPartitioner<CDCKafkaModel> {
    @Override
    public int partition(CDCKafkaModel record, byte[] bytes, byte[] bytes1, String targetTopic, int[] partitions) {
        return Math.abs(record.getPartitionKey().hashCode() % partitions.length);
    }
}
