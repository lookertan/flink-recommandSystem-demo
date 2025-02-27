package com.demo.task;

import com.demo.agg.CountAgg;
import com.demo.domain.LogEntity;
import com.demo.domain.TopProductEntity;
import com.demo.map.TopProductMapFunction;
import com.demo.sink.TopNRedisSink;
import com.demo.top.TopNHotItems;
import com.demo.util.Property;
import com.demo.window.WindowResultFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;
import org.apache.flink.util.Collector;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * 热门商品 -> redis
 *
 * @author XINZE
 */
public class TopProductTask {

    private static final int topSize = 5;

    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 开启EventTime
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		FlinkJedisPoolConfig conf = new FlinkJedisPoolConfig.Builder()
				.setHost(Property.getStrValue("redis.host"))
				.setPort(Property.getIntValue("redis.port"))
				.setDatabase(Property.getIntValue("redis.db"))
				.build();

        Properties properties = Property.getKafkaProperties("topProuct");
        DataStreamSource<String> dataStream = env.addSource(new FlinkKafkaConsumer<String>("con", new SimpleStringSchema(), properties));
        List<String> top = new ArrayList<>();
        DataStream<TopProductEntity> topProduct = dataStream.map(new TopProductMapFunction()).
                assignTimestampsAndWatermarks(new AscendingTimestampExtractor<LogEntity>() {
                    @Override
                    public long extractAscendingTimestamp(LogEntity logEntity) {
                        return logEntity.getTime();
                    }
                })
                .keyBy("productId").timeWindow(Time.seconds(60),Time.seconds(5))
                .aggregate(new CountAgg(), new WindowResultFunction())
                .keyBy("windowEnd").process(new TopNHotItems(topSize)).flatMap(new FlatMapFunction<List<String>, TopProductEntity>() {
                    @Override
                    public void flatMap(List<String> strings, Collector<TopProductEntity> collector) throws Exception {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmm");
                        String time = sdf.format(new Date());
                        for (int i = 0; i < strings.size(); i++) {
                            TopProductEntity top = new TopProductEntity();
                            top.setRankName(String.valueOf(i));
                            top.setWindowEnd(new Long(time));
                            top.setProductId(Integer.parseInt(strings.get(i)));
                        }
                    }
                });
        topProduct.addSink(new RedisSink<>(conf,new TopNRedisSink()));

        env.execute("Top N ");
    }
}
