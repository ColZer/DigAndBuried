HBase Bulk Loading实践与实现深究
===

## Bulk Loading实践
下面代码的功能启动一个mapreduce过程,将hdfs中的文件转化为符合指定table的分区的HFile,并调用LoadIncrementalHFiles将它导入到HBase已有的表中

        public static class ToHFileMapper 
            extends Mapper<Object, Text, ImmutableBytesWritable, KeyValue>{
                Random random = new Random();
                ImmutableBytesWritable oKey = new ImmutableBytesWritable();
                public void map(Object key, Text value, Context context) 
                    throws IOException, InterruptedException {
                        KeyValueBuilder builder = new FileMetaBuilder();
                        Iterator<KeyValue> keyValues = builder.getKeyValueFromRow(value.toString());
                        oKey.set(builder.getKey(value.toString()));
                        while(keyValues.hasNext()) {
                            KeyValue tmp = keyValues.next();
                            context.write(oKey, tmp);
                        }
                }
         }
         
        public static void run(String fileMetaPath, String table) throws Exception{
                 String tmpPath = fileMetaPath.trim() + "_" + System.currentTimeMillis();
                 Configuration conf = new Configuration();
                 conf.set("hbase.zookeeper.quorum", ToHFile.zkQuorum);
                 Job job = new Job(conf);
                 job.setJobName(ToHFile.class.getName());
                 job.setJarByClass(ToHFile.class);
                 job.setMapperClass(ToHFileMapper.class);
                 //关键步骤
                 HFileOutputFormat.configureIncrementalLoad(job, new HTable(conf, table));
                 FileInputFormat.addInputPath(job, new Path(fileMetaPath));
                 FileOutputFormat.setOutputPath(job,  new Path(tmpPath));
                 job.waitForCompletion(true) ;
         
                 conf.set("fs.default.name",ToHFile.hdfsV1Name);
                 LoadIncrementalHFiles load = new LoadIncrementalHFiles(conf);
                 load.run(new String[]{tmpPath,table});
         
                 FileSystem hdfs = FileSystem.get(conf);
                 hdfs.delete(new Path(tmpPath),true);
         }

细节描述:

+   ToHFileMapper是一个Map过程,它读取一个HDFS文件,并输出key=ImmutableBytesWritable,Value=KeyValue类型的kv数据.
KeyValue类型为HBase中最小数据单位,即为一个cell,它由rowKey,family,qualifiers,timestamp,value大小,value值组成,参考下列的可视化输出:   
        K: 59129_3471712620_1374007953/f:status/1413288274401/Put/vlen=1/ts=0 V: 0   
我们都知道HBase中数据是按照KV的格式进行组织和存储,在HBase层面它的key是rowKey,但是HFile层面,这里的key不仅仅是rowKey,参考上面的输出中K,
它由rowKey/family:qualifier/timestamp/类型/vlen=Value的大小/ts组成. 而Value就为对应的值.  
我们可以通过KeyValue的API进行设置其中的每个字段的值,从而输出一条cell.注意mysql中一条记录中的每个字段对应HBase中一个cell,所以一条记录会输出多个cell.

+   ToHFileMapper输出的Key的类型ImmutableBytesWritable,我们必须设置它的值为该cell的rowKey, 具体原因呢:  
我们知道HBase中数据按照rowKey进行划分为多个region,每个region维护一组HFile文件,因此region之间的数据是严格有序,单个region中单个HFile的内部cell也是严格有序,
但单个region中多个HFile之间不要求有序.  
这种有序性的要求也是为什么我们可以把一个HFile直接加载到HBase中的原因.对于原始数据,在map阶段将key设置为rowKey,采用特殊的分区的函数,
从而可以实现将属于同一个region的数据发送到同一个reduce,在reduce里面我们按照cell的有序,写入单个HFile中,这样我们就保证了region之间的有序,单个HFile有序性.

+   上面我们谈到了根据key=rowKey进行分区,将属于同一个region的数据发送到同一个reduce中进行处理.但是在我们job的配置过程中,我们没有配置reduce,没有配置分区函数
而是通过调用HFileOutputFormat的configureIncrementalLoad函数进行操作,该函数接受一个HBase的Table对象,利于该Table的性质设置job相应的属性;参考下面的源码
       
        public static void configureIncrementalLoad(Job job, HTable table) throws IOException {
            Configuration conf = job.getConfiguration();
            //return org.apache.hadoop.mapreduce.lib.partition.TotalOrderPartitioner
            Class<? extends Partitioner> topClass = getTotalOrderPartitionerClass();
            job.setPartitionerClass(topClass);//设置分区函数
            job.setOutputKeyClass(ImmutableBytesWritable.class);
            job.setOutputValueClass(KeyValue.class);
            job.setOutputFormatClass(HFileOutputFormat.class);//设置OutPut
            //设置reduce函数
            if (KeyValue.class.equals(job.getMapOutputValueClass())) {
              job.setReducerClass(KeyValueSortReducer.class);
            } else if (Put.class.equals(job.getMapOutputValueClass())) {
              job.setReducerClass(PutSortReducer.class);
            } 
         }
    configureIncrementalLoad对Job的分区函数,reducer,output进行设置,因此对原始row数据转换为HFile,仅仅需要配置一个Map就可以了.其中reducer的实现也很简单,代码如下:

        protected void reduce(ImmutableBytesWritable row, 
           Iterable<KeyValue> kvs,Context context)  throws IOException, InterruptedException {
                TreeSet<KeyValue> map = new TreeSet<KeyValue>(KeyValue.COMPARATOR);
                for (KeyValue kv: kvs) {
                  map.add(kv.clone());
                }
                int index = 0;
                for (KeyValue kv: map) {
                  context.write(row, kv);
                }
         }
    内部维护TreeSet,保证单HFile内部的cell之间有序,进而将他们输出到HFile中.

+   HFile结果输出.上述我们描述了Table,Region,HFile之间关系,其中我们没有对family进行考虑,在每个Region中,Family为管理的最大单位,它为每个rowKey的每个Family
维护一个单独的store(menstore+HFile组成).因此HFile的输出也是按照Family+region进行分开组织的.具体的结构这里就不描述了.

+   输出HFile的目录可以直接作为LoadIncrementalHFiles的参数,再加上一个table参数,就可以直接将目录下的HFile"move"到HBase特定目录下面.代码如下:

        LoadIncrementalHFiles load = new LoadIncrementalHFiles(conf);
        load.run(new String[]{tmpPath,table});

一切就这么简单,就可以大吞吐的将数据导入到HBase中,大幅度的减少HDFS的IO压力.  
代码连接:[ToHFile.java](./ToHFile.java)

## LoadIncrementalHFiles实现深究
