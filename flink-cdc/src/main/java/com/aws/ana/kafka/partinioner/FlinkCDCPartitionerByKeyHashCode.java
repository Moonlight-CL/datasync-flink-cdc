package com.aws.ana.kafka.partinioner;

import com.aws.ana.model.CDCKafkaModel;
import org.apache.flink.streaming.connectors.kafka.partitioner.FlinkKafkaPartitioner;

public class FlinkCDCPartitionerByKeyHashCode extends FlinkKafkaPartitioner<CDCKafkaModel> {
    @Override
    public int partition(CDCKafkaModel record, byte[] bytes, byte[] bytes1, String targetTopic, int[] partitions) {
        int idx = Math.abs(record.getPartitionKey().hashCode() % partitions.length);
        return idx;
    }
}
