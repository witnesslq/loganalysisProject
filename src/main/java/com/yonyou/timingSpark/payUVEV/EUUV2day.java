package com.yonyou.timingSpark.payUVEV;

import com.yonyou.dao.IPayEUUVDAO;
import com.yonyou.dao.factory.DAOFactory;
import com.yonyou.entity.enterprise.EVStat;
import com.yonyou.jdbc.JDBCUtils;
import com.yonyou.utils.DateUtils;
import com.yonyou.utils.DateUtils2;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.util.Base64;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.sql.Connection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by chenxiaolei on 16/12/14.
 *
 * .payUVEV.EUUV2day;
 */
public class EUUV2day {
    public static void main(String[] args) {
        SparkConf sconf = new SparkConf()
                .setAppName("EUUV2day")
                .set("spark.default.parallelism", "150")//並行度，reparation后生效(因为集群现在的配置是8核，按照每个核心有一个vcore，就是16，三个worker节点，就是16*3，并行度设置为3倍的话：16*3*3=144，故，这里设置150)
                .set("spark.locality.wait", "100ms")
                .set("spark.shuffle.manager", "hash")//使用hash的shufflemanager
                .set("spark.shuffle.consolidateFiles", "true")//shufflemap端开启合并较小落地文件（hashshufflemanager方式一个task对应一个文件，开启合并，reduce端有几个就是固定几个文件，提前分配好省着merge了）
                .set("spark.shuffle.file.buffer", "64")//shufflemap端mini环形缓冲区bucket的大小调大一倍，默认32KB
                .set("spark.reducer.maxSizeInFlight", "24")//从shufflemap端拉取数据24，默认48M
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")//序列化
                .set("spark.shuffle.io.maxRetries", "10")//GC重试次数，默认3
                .set("spark.shuffle.io.retryWait", "30s");//GC等待时长，默认5s
        //sconf.setMaster("local[2]");
        JavaSparkContext sc = new JavaSparkContext(sconf);

        // 从mysql中查询付费企业，将其转换为一个rdd
        JDBCUtils jdbcUtils = JDBCUtils.getInstance();
        Connection conn = jdbcUtils.getConnection();
        IPayEUUVDAO payStatDAO = DAOFactory.getPayStatDAO();
        List<String> lists = payStatDAO.findAll(conn, getTimes(DateUtils.getYesterdayDate()) + "", getTimes(DateUtils.getYesterdayDate()) + "");
        Map<String,Boolean> map = new HashMap<String,Boolean>();
        for (String s :lists){
            map.put(s,true);
        }
        final Broadcast<Map<String, Boolean>> broadcast = sc.broadcast(map);

        if (conn != null) {
            jdbcUtils.closeConnection(conn);
        }
        ////////////////////////////////////////////////////////////////////

        Configuration conf = HBaseConfiguration.create();
        Scan scan = new Scan();
        scan.addFamily(Bytes.toBytes("accesslog"));
        scan.addColumn(Bytes.toBytes("accesslog"), Bytes.toBytes("info"));
        if (args.length == 2) {
            scan.setStartRow(Bytes.toBytes(args[0] + ":#"));
            scan.setStopRow(Bytes.toBytes(args[1] + "::"));
        } else {
            scan.setStartRow(Bytes.toBytes(DateUtils.getYesterdayDate() + ":#"));
            scan.setStopRow(Bytes.toBytes(DateUtils.getYesterdayDate() + "::"));
        }
        try {
            String tableName = "esn_accesslog";
            conf.set(TableInputFormat.INPUT_TABLE, tableName);
            ClientProtos.Scan proto = ProtobufUtil.toScan(scan);
            String ScanToString = Base64.encodeBytes(proto.toByteArray());
            conf.set(TableInputFormat.SCAN, ScanToString);
            JavaPairRDD<ImmutableBytesWritable, Result> myRDD =
                    sc.newAPIHadoopRDD(conf, TableInputFormat.class,
                            ImmutableBytesWritable.class, Result.class);
            //读取的每一行数据
            JavaRDD<String> filter = myRDD.map(new Function<Tuple2<ImmutableBytesWritable, Result>, String>() {
                @Override
                public String call(Tuple2<ImmutableBytesWritable, Result> v1) throws Exception {

                    byte[] value = v1._2.getValue(Bytes.toBytes("accesslog"), Bytes.toBytes("info"));
                    if (value != null) {
                        return Bytes.toString(value);
                    }
                    return null;
                }
            }).filter(new Function<String, Boolean>() {
                @Override
                public Boolean call(String v1) throws Exception {
                    return v1 != null && !"openapi".equals(v1.split("\t")[3]);
                }
            }).repartition(150);
            //二次过滤 去除 企业ID不存在的
            JavaRDD<String> filter2emppry = filter2empty(filter);
            //格式转换
            JavaRDD<String> map2line = map2line(filter2emppry);


            JavaRDD<String> map2pay = map2line.mapToPair(new PairFunction<String, String, String>() {
                @Override
                public Tuple2<String, String> call(String s) throws Exception {
                    String[] parm3 = s.split("&");
                    return new Tuple2<String, String>(parm3[1], s);
                }
            }).filter(new Function<Tuple2<String, String>, Boolean>() {
                @Override
                public Boolean call(Tuple2<String, String> v1) throws Exception {
                    Map<String, Boolean> value = broadcast.value();
                    if (value.get(v1._1) == null) {
                        return false;
                    }
                    return true;
                }
            }).map(new Function<Tuple2<String, String>, String>() {
                @Override
                public String call(Tuple2<String, String> v1) throws Exception {
                    return v1._2;
                }
            });
            map2pay = map2pay.persist(StorageLevel.MEMORY_ONLY_SER());
            //计算EV
            calculateEVSta(map2pay);
            //计算EUV
            calculateUVSta(map2pay);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //计算EUV  时间戳 企业ID memID
    private static void calculateUVSta(JavaRDD<String> map2line) {
        map2line.mapPartitionsToPair(new PairFlatMapFunction<Iterator<String>, String, String>() {
            @Override
            public Iterable<Tuple2<String, String>> call(Iterator<String> iterator) throws Exception {
                List<Tuple2<String, String>> list = new ArrayList<Tuple2<String, String>>();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String[] strings = s.split("&");
                    list.add(new Tuple2<String, String>(strings[0], strings[2]));
                }
                return list;
            }
        })
                .distinct()
                .mapPartitionsToPair(new PairFlatMapFunction<Iterator<Tuple2<String, String>>, String, Integer>() {
                    @Override
                    public Iterable<Tuple2<String, Integer>> call(Iterator<Tuple2<String, String>> iterator2) throws Exception {
                        List<Tuple2<String, Integer>> list = new ArrayList<Tuple2<String, Integer>>();
                        while (iterator2.hasNext()) {
                            Tuple2<String, String> tuple2 = iterator2.next();
                            list.add(new Tuple2<String, Integer>(tuple2._1, 1));
                        }

                        return list;
                    }
                }).reduceByKey(new Function2<Integer, Integer, Integer>() {
            @Override
            public Integer call(Integer v1, Integer v2) throws Exception {
                return v1 + v2;
            }
        }).foreachPartition(new VoidFunction<Iterator<Tuple2<String, Integer>>>() {
            @Override
            public void call(Iterator<Tuple2<String, Integer>> tuple2Iterator) throws Exception {
                List<EVStat> evStats = new ArrayList<EVStat>();
                while (tuple2Iterator.hasNext()) {
                    EVStat evStat = new EVStat();
                    Tuple2<String, Integer> tuple2 = tuple2Iterator.next();
                    evStat.setType("1day");
                    evStat.setCreated(tuple2._1);
                    evStat.setNum(tuple2._2);
                    evStats.add(evStat);
                }
                if (evStats.size() > 0) {
                    JDBCUtils jdbcUtils = JDBCUtils.getInstance();
                    Connection conn = jdbcUtils.getConnection();
                    IPayEUUVDAO payStatDAO = DAOFactory.getPayStatDAO();
                    payStatDAO.updataBatch(evStats,conn,1);
                    System.out.println("mysql 2 euvstat day==> " + evStats.size());
                    evStats.clear();
                    if (conn != null) {
                        jdbcUtils.closeConnection(conn);
                    }
                }
            }
        });
    }

    //计算ev
    private static void calculateEVSta(JavaRDD<String> map2line) {
        map2line.mapPartitionsToPair(new PairFlatMapFunction<Iterator<String>, String, String>() {
            @Override
            public Iterable<Tuple2<String, String>> call(Iterator<String> iterator) throws Exception {
                List<Tuple2<String, String>> list = new ArrayList<Tuple2<String, String>>();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String[] strings = s.split("&");
                    list.add(new Tuple2<String, String>(strings[0], strings[1]));
                }
                return list;
            }
        })
                .distinct()
                .mapPartitionsToPair(new PairFlatMapFunction<Iterator<Tuple2<String, String>>, String, Integer>() {
                    @Override
                    public Iterable<Tuple2<String, Integer>> call(Iterator<Tuple2<String, String>> iterator2) throws Exception {
                        List<Tuple2<String, Integer>> list = new ArrayList<Tuple2<String, Integer>>();
                        while (iterator2.hasNext()) {
                            Tuple2<String, String> tuple2 = iterator2.next();
                            list.add(new Tuple2<String, Integer>(tuple2._1, 1));
                        }

                        return list;
                    }
                }).reduceByKey(new Function2<Integer, Integer, Integer>() {
            @Override
            public Integer call(Integer v1, Integer v2) throws Exception {
                return v1 + v2;
            }
        }).foreachPartition(new VoidFunction<Iterator<Tuple2<String, Integer>>>() {
            @Override
            public void call(Iterator<Tuple2<String, Integer>> tuple2Iterator) throws Exception {
                List<EVStat> evStats = new ArrayList<EVStat>();
                while (tuple2Iterator.hasNext()) {
                    EVStat evStat = new EVStat();
                    Tuple2<String, Integer> tuple2 = tuple2Iterator.next();
                    evStat.setType("1day");
                    evStat.setCreated(tuple2._1);
                    evStat.setNum(tuple2._2);
                    evStats.add(evStat);
                }
                if (evStats.size() > 0) {
                    JDBCUtils jdbcUtils = JDBCUtils.getInstance();
                    Connection conn = jdbcUtils.getConnection();
                    IPayEUUVDAO payStatDAO = DAOFactory.getPayStatDAO();
                    payStatDAO.updataBatch(evStats,conn,0);
                    System.out.println("mysql 2 euvstat day==> " + evStats.size());
                    evStats.clear();
                    if (conn != null) {
                        jdbcUtils.closeConnection(conn);
                    }
                }
            }
        });
    }

    //转换格式
    private static JavaRDD<String> map2line(final JavaRDD<String> line) {
        JavaRDD<String> stringJavaRDD = line.mapPartitions(new FlatMapFunction<Iterator<String>, String>() {
            @Override
            public Iterable<String> call(Iterator<String> iterator) throws Exception {
                List<String> list = new ArrayList<String>();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String[] str = s.split("\t");
                    long timestamp = DateUtils2.getTime(str[7], 1);
                    String instance_id = str[26].split(":")[1];//获得memid
                    String mem_id = str[23].split(":")[1];//获得memid
                    StringBuffer map = new StringBuffer();
                    //-------------------------
                    map.append(timestamp + "").append("&");
                    map.append(instance_id + "").append("&");
                    map.append(mem_id + "");
                    list.add(map.toString());
                }
                return list;
            }
        }).filter(new Function<String, Boolean>() {
            @Override
            public Boolean call(String s) throws Exception {
                return s.split("&").length == 3;
            }
        });
        return stringJavaRDD;
    }

    //过滤数据
    private static JavaRDD<String> filter2empty(JavaRDD<String> filter) {
        return filter.filter(new Function<String, Boolean>() {
            @Override
            public Boolean call(String s) throws Exception {
                String[] str = s.split("\t");
                return str.length == 27 && str[23].split(":").length == 2 && str[26].split(":").length == 2;
            }
        });
    }

    /**
     * 获得当天时间戳 hbase rowkey
     *
     * @param date
     * @return
     */
    private static long getTimes(String date) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy:MM:dd");
        Date parse = null;
        long l = 0l;
        try {
            parse = day.parse(date);
            l = parse.getTime() / 1000;
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return l;
    }

}
