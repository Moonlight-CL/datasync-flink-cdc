## About
This repo contains **sample codes** of syncing data to Amazon MSK / KDS by using Flink CDC, 
and we run Flink CDC application in AWS Managed Service for Apache Flink.

## How To
You can package the application to a fat jar if you want to sync data from Amazon DocumentDB by doing:
```shell
# change dir to flink-cdc
mvn clean package -PdocDB2MskKds
```
or if you want to sync data from PostgreSQL, you can:
```shell
mvn clean package -Ppg2Msk
```

How to run Flink application in Amazon Managed Service for Apache Flink, please refer to [this doc](https://docs.aws.amazon.com/managed-flink/latest/java/get-started-exercise.html).