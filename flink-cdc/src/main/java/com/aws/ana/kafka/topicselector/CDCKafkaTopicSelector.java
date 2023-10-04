package com.aws.ana.kafka.topicselector;

import com.aws.ana.model.CDCKafkaModel;
import org.apache.flink.connector.kafka.sink.TopicSelector;

public class CDCKafkaTopicSelector implements TopicSelector<CDCKafkaModel> {

    private String topicPrefix;
    public CDCKafkaTopicSelector(String topicPrefix) {
        if (topicPrefix == null || topicPrefix.equals("")) {
            this.topicPrefix = "cdc-";
        } else {
            this.topicPrefix = topicPrefix;
        }
    }

    @Override
    public String apply(CDCKafkaModel record) {
        return this.topicPrefix + record.getDb().toLowerCase().replace("_", "-");
    }
}
