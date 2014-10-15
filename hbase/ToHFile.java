package com.baidu.yundata;

import java.io.IOException;
import java.util.Iterator;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

public class ToHFile {
    static final Log LOG = LogFactory.getLog(ToHFile.class);
    public static String zkQuorum = "";//配置Hbase的zk地址
    public static String hdfsV1Name = "";//HDFS地址

    public static class ToHFileMapper
            extends Mapper<Object, Text, ImmutableBytesWritable, KeyValue>{
        Random random = new Random();
        ImmutableBytesWritable oKey = new ImmutableBytesWritable();
        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            KeyValueBuilder builder = new FileMetaBuilder();
            //自定义KeyValueBuilder接口,实现getKey和getKeyValueFromRow两个方法
            Iterator<KeyValue> keyValues = builder.getKeyValueFromRow(value.toString());
            oKey.set(builder.getKey(value.toString()));
            while(keyValues.hasNext()) {
                KeyValue tmp = keyValues.next();
                context.write(oKey, tmp);
            }
        }
    }

    public static void usage(){
        System.out.println("java ToHFile file_meta_path table");
        return;
    }

    public static void run(String fileMetaPath, String table) throws Exception{
        String tmpPath = fileMetaPath.trim() + "_" + System.currentTimeMillis();
        Configuration conf = new Configuration();
        conf.set("hbase.zookeeper.quorum", ToHFile.zkQuorum);
        Job job = new Job(conf);
        job.setJobName(ToHFile.class.getName());
        job.setJarByClass(ToHFile.class);
        job.setMapperClass(ToHFileMapper.class);
        HFileOutputFormat.configureIncrementalLoad(job, new HTable(conf, table));
        FileInputFormat.addInputPath(job, new Path(fileMetaPath));
        FileOutputFormat.setOutputPath(job,  new Path(tmpPath));

        System.out.println("++++++++++++++++++++++++++");
        System.out.println("fileMetaPath:"+fileMetaPath);
        System.out.println("tmpPath:"+tmpPath);
        System.out.println("table:"+table);
        System.out.println("++++++++++++++++++++++++++");
        job.waitForCompletion(true) ;

        System.out.println("++++++++++++++++++++++++++");
        conf.set("fs.default.name",ToHFile.hdfsV1Name);
        LoadIncrementalHFiles load = new LoadIncrementalHFiles(conf);
        load.run(new String[]{tmpPath,table});

        FileSystem hdfs = FileSystem.get(conf);
        hdfs.delete(new Path(tmpPath),true);
    }

    public static void main(String[] args) throws Exception {
        if(args.length != 2){
            System.err.println("Error!");
            ToHFile.usage();
            System.exit(1);
        }
        ToHFile.run(args[0],args[1]);
    }
}