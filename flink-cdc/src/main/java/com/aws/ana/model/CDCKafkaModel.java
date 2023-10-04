package com.aws.ana.model;

public class CDCKafkaModel {

    private String db;
    private String table;
    private String partitionKey;
    private String value;

    public static CDCKafkaModel of(String db, String coll, String partitionKey, String line) {
        CDCKafkaModel m = new CDCKafkaModel();
        m.setDb(db);
        m.setTable(coll);
        m.setPartitionKey(partitionKey);
        m.setValue(line);
        return m;
    }

    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    @Override
    public String toString() {
        return "MongoCDCKafkaModel{" +
                "db='" + db + '\'' +
                ", coll='" + table + '\'' +
                ", partitionKey='" + partitionKey + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}
