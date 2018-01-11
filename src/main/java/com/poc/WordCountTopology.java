package com.poc; /**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseBasicBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.*;

/**
 * This topology demonstrates Storm's stream groupings and multilang capabilities.
 */
public class WordCountTopology {
    public static class SplitSentence extends BaseBasicBolt {
        static String LOG;
        private Jedis jedis;
        private static int oCount = 0;

        @Override
        public void prepare(Map stormConf, TopologyContext context) {
            super.prepare(stormConf, context);
            LOG = context.getThisComponentId() + context.getThisTaskId();
            this.jedis = new Jedis((String) stormConf.get("jedis.url"));
            oCount++;
            System.out.println(context.getThisComponentId() + context.getThisTaskId() + "INIT JEDIS");

            jedis.hset("SplitSentence", context.getThisComponentId()+context.getThisTaskId(), String.valueOf(oCount));
            jedis.close();
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("word"));
        }

        @Override
        public Map<String, Object> getComponentConfiguration() {
            return null;
        }

        @Override
        public void execute(Tuple tuple, BasicOutputCollector basicOutputCollector) {
            String sentence = (String) tuple.getValues().get(0);
            for(Object word: sentence.split(" ")) {
                basicOutputCollector.emit(new Values(word));
            }
        }
    }

    public static class WordCount extends BaseBasicBolt {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        private Jedis jedis;
        private static int oCount = 0;
        static String LOG;

        @Override
        public void prepare(Map stormConf, TopologyContext context) {
            super.prepare(stormConf, context);
            LOG = WordCount.class.getName() + context.getThisTaskId();

            this.jedis = new Jedis((String) stormConf.get("jedis.url"));
            oCount++;
            System.out.println(context.getThisComponentId()+context.getThisTaskId()+"INIT JEDIS");

            jedis.hset("WordCount", context.getThisComponentId() + context.getThisTaskId()
                    , String.valueOf(oCount));
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    System.out.println("Inside shutdown hook.");
                    jedis.close();
                }
            });
        }

        @Override
        public void execute(Tuple tuple, BasicOutputCollector collector) {
            String word = tuple.getString(0);
            Integer count = counts.get(word);
            if (count == null)
                count = 0;
            count++;
            counts.put(word, count);
            if(count/100 == 0){
                //collector.reportError(new Exception("Randomly"));
                throw new RuntimeException("Runtime");
            }
            collector.emit(new Values(word, count));
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("word", "count"));
        }
    }

    public static void main(String[] args) throws Exception {

        TopologyBuilder builder = new TopologyBuilder();

        builder.setSpout("spout", new RandomSentenceSpout(), 5);

        builder.setBolt("split", new SplitSentence(), 2).shuffleGrouping("spout");
        builder.setBolt("count", new WordCount(), 2).fieldsGrouping("split", new Fields("word"));

        Config conf = new Config();
        conf.setDebug(true);
        conf.put("jedis.url", "127.0.0.1");

        if (args != null && args.length > 0) {
            conf.setNumWorkers(2);

            StormSubmitter.submitTopologyWithProgressBar(args[0], conf, builder.createTopology());
        }
        else {
            conf.setMaxTaskParallelism(3);

            LocalCluster cluster = new LocalCluster();
            cluster.submitTopology("word-count", conf, builder.createTopology());

            Thread.sleep(10000);

            cluster.shutdown();
        }
    }
}