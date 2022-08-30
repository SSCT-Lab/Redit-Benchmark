package io.redit.samples.kafka12257;

import io.redit.ReditRunner;
import io.redit.exceptions.RuntimeEngineException;
import io.redit.execution.CommandResults;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;


public class SampleTest {
    private static final Logger logger = LoggerFactory.getLogger(SampleTest.class);
    protected static ReditRunner runner;
    private static String ZooCfgFile = "conf/zoo.cfg";
    private static String KafkaPropFile = "server.properties";
    private static String ZooCfgConf = "";
    private static String KafkaPropConf = "zookeeper.connect=";

    private static final String topicName = "test_topic";

    @BeforeClass
    public static void before() throws RuntimeEngineException, IOException {
        runner = ReditRunner.run(ReditHelper.getDeployment());
        ReditHelper.startNodesInOrder(runner);
        for(int i = 1; i <= ReditHelper.numOfServers; i++){
            ZooCfgConf += "server." + i + "=" + runner.runtime().ip("server" + (i)) + ":2888:3888\n";
            KafkaPropConf += runner.runtime().ip("server" + (i)) + ":2181";
            if (i < ReditHelper.numOfServers){
                KafkaPropConf += ",";
            }
        }
        addZooCfgFile();
        addKafkaPropFile();
    }

    @AfterClass
    public static void after() {
        if (runner != null) {
            runner.stop();
        }
    }

    @Test
    public void sampleTest() throws InterruptedException, RuntimeEngineException {
        logger.info("wait for zookeeper...");
        startZookeepers();
        Thread.sleep(10000);
        checkZookeepersStatus();

        Thread.sleep(10000);
        logger.info("wait for kafka...");
        startKafkas();
        Thread.sleep(20000);
        checkJps();

        //1.开始一个主题（或多个主题）的消费者
        createTopic(1);
        Thread.sleep(2000);
        listTopic(3);
        createConsumer(1);
        Thread.sleep(10000);

        //2.重新启动恰好是主题分区之一的领导者的代理
        kafkaCat(1);
        restartBroker(2);
        Thread.sleep(20000);
        kafkaCat(1);

        //3.删除主题
        deleteTopic(1);
        Thread.sleep(5000);
        listTopic(3);

        //4.创建另一个同名主题
        createTopic(1);
        listTopic(3);
        Thread.sleep(2000);

        //5.发布新主题的数据
        kafkaCat(1);
        createProducer(1);
        Thread.sleep(20000);

        //6.消费者将不会收到新主题的数据，并且会有很高的元数据请求率。
        logger.info("completed !!!");
    }

    private void createTopic(int serverId) throws RuntimeEngineException {
        String command = "cd " + ReditHelper.getKafkaHomeDir() + " && bin/kafka-topics.sh --bootstrap-server " + runner.runtime().ip("server" + serverId) + ":9092 --create --topic " + topicName + " --replication-factor 3 --partitions 3";
        CommandResults commandResults = runner.runtime().runCommandInNode("server" + serverId, command);
        printResult(commandResults);
    }

    private void deleteTopic(int serverId) throws RuntimeEngineException {
        String command = "cd " + ReditHelper.getKafkaHomeDir() + " && bin/kafka-topics.sh --bootstrap-server " + runner.runtime().ip("server" + serverId) + ":9092 --delete --topic " + topicName;
        CommandResults commandResults = runner.runtime().runCommandInNode("server" + serverId, command);
        printResult(commandResults);
    }

    private void listTopic(int serverId) throws RuntimeEngineException {
        String command = "cd " + ReditHelper.getKafkaHomeDir() + " && bin/kafka-topics.sh --bootstrap-server " + runner.runtime().ip("server" + serverId) + ":9092 --list";
        CommandResults commandResults = runner.runtime().runCommandInNode("server" + serverId, command);
        printResult(commandResults);
    }

    private static void createConsumer(int serverId) {
        Properties kafkaConsumerProps = new Properties();
        String bootstrap_server = runner.runtime().ip("server" + serverId) + ":9092";
        System.out.println("createConsumer !!!! bootstrap_server: " + bootstrap_server);
        kafkaConsumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap_server);
        kafkaConsumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,  StringDeserializer.class);
        kafkaConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaConsumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "g1");

        KafkaConsumer<String,String> kafkaConsumer = new KafkaConsumer(kafkaConsumerProps);
        kafkaConsumer.subscribe(Arrays.asList(topicName));

        new Thread(() -> {
            try {
                int x = 0;
                while (x < 20){
                    ConsumerRecords<String, String> consumerRecords = kafkaConsumer.poll(100);
                    for (ConsumerRecord<String, String> consumerRecord : consumerRecords) {
                        System.out.println("server " + serverId + " kafkaConsumer: " + consumerRecord);
                    }
                    Thread.sleep(5000);
                    x++;
                    System.out.println("server " + serverId + " kafkaConsumer poll: " + x);
                }
                kafkaConsumer.close();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static void createProducer(int serverId) throws InterruptedException {
        Properties kafkaProducerProps = new Properties();
        String bootstrap_server = runner.runtime().ip("server" + serverId) + ":9092";
        System.out.println("bootstrap_server: " + bootstrap_server);
        kafkaProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap_server);
        kafkaProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,  StringSerializer.class);
        kafkaProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        kafkaProducerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        kafkaProducerProps.put(ProducerConfig.ACKS_CONFIG, "all");

        KafkaProducer<String, String> kafkaProducer = new KafkaProducer<>(kafkaProducerProps);
        ProducerRecord<String, String> record1 = new ProducerRecord<>(topicName, "key", "value");
        ProducerRecord<String, String> record2 = new ProducerRecord<>(topicName, "hello", "kafka");
        kafkaProducer.send(record1, (recordMetadata, e) -> {
            if (e == null) {
                System.out.println("KafkaProducer send data partition：" + recordMetadata.partition());//数据所在分区
                System.out.println("KafkaProducer send data topic：" + recordMetadata.topic());//数据所对应的topic
                System.out.println("KafkaProducer send data offset：" + recordMetadata.offset());//数据的offset
            }
        });
        Thread.sleep(1000);
        kafkaProducer.send(record2, (recordMetadata, e) -> {
            if (e == null) {
                System.out.println("KafkaProducer send data partition：" + recordMetadata.partition());//数据所在分区
                System.out.println("KafkaProducer send data topic：" + recordMetadata.topic());//数据所对应的topic
                System.out.println("KafkaProducer send data offset：" + recordMetadata.offset());//数据的offset
            }
        });
        kafkaProducer.close();
    }

    private static void restartBroker(int serverId) throws RuntimeEngineException, InterruptedException {
        String command = "cd " + ReditHelper.getKafkaHomeDir() + " && bin/kafka-server-stop.sh";
        CommandResults commandResults = runner.runtime().runCommandInNode("server" + serverId, command);
        printResult(commandResults);
        Thread.sleep(10000);
        kafkaCat(1);
        String command2 = "cd " + ReditHelper.getKafkaHomeDir() + " && bin/kafka-server-start.sh -daemon ./config/server.properties";
        CommandResults commandResults2 = runner.runtime().runCommandInNode("server" + serverId, command2);
        printResult(commandResults2);
    }

    private static void kafkaCat(int serverId){
        List<String> commands= new ArrayList<>();
        commands.add("kafkacat -b " + runner.runtime().ip("server" + serverId) + ":9092 -L");
        executeNewFlow(commands);
    }

    //服务器执行命令行方法
    public static int executeNewFlow(List<String> commands) {
        int brokerId = -1;
        Runtime run = Runtime.getRuntime();
        try {
            Process proc = run.exec("/bin/bash", null, null);
            BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(proc.getOutputStream())), true);
            for (String line : commands) {
                out.println(line);
            }
            out.println("exit");// 这个命令必须执行，否则in流不结束。
            String rspLine = "";
            System.out.println("============ kafkacat :" + commands + "==============");
            while ((rspLine = in.readLine()) != null) {
                System.out.println(rspLine);
            }
            proc.waitFor();
            in.close();
            out.close();
            proc.destroy();
        } catch (IOException e1) {
            e1.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return brokerId;
    }

    private void startKafkas() {
        for(int i = 1; i <= ReditHelper.numOfServers; i++){
            startKafka(i);
        }
    }

    private static void startKafka(int serverId) {
        String command = "cd " + ReditHelper.getKafkaHomeDir() + " && bin/kafka-server-start.sh -daemon ./config/server.properties";
        logger.info("server" + serverId + " startKafka...");
        new Thread(() -> {
            try {
                runner.runtime().runCommandInNode("server" + serverId, command);
            } catch (RuntimeEngineException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void startZookeepers() {
        for(int i = 1; i <= ReditHelper.numOfServers; i++){
            startZookeeper(i);
        }
    }

    private static void startZookeeper(int serverId) {
        String command = "cd " + ReditHelper.getZookeeperHomeDir() + " && bin/zkServer.sh start";
        logger.info("server" + serverId + " startZookeeper...");
        new Thread(() -> {
            try {
                runner.runtime().runCommandInNode("server" + serverId, command);
            } catch (RuntimeEngineException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void checkZookeepersStatus() throws InterruptedException, RuntimeEngineException {
        for(int i = 1; i <= ReditHelper.numOfServers; i++){
            String command = "cd " + ReditHelper.getZookeeperHomeDir() + " && bin/zkServer.sh status";
            logger.info("server" + i + " checkStatus...");
            CommandResults commandResults = runner.runtime().runCommandInNode("server" + i, command);
            printResult(commandResults);
            Thread.sleep(1000);
        }
    }

    private static void addKafkaPropFile() throws IOException {
        boolean addConf = false;
        BufferedReader in = new BufferedReader(new FileReader("conf/server1/" + KafkaPropFile));
        String str;
        while ((str = in.readLine()) != null) {
            if (str.startsWith("zookeeper.connect=")){
                addConf = true;
            }
        }
        in.close();
        if (addConf){
            logger.info("already add config in " + KafkaPropFile);
        }
        else {
            for (int i = 1; i <= ReditHelper.numOfServers; i++){
                BufferedWriter out = new BufferedWriter(new FileWriter("conf/server" + i + "/" + KafkaPropFile, true));
                String listenerConf = "listeners = PLAINTEXT://" + runner.runtime().ip("server" + i) + ":9092\n";
                out.write(listenerConf);
                out.write(KafkaPropConf);
                out.close();
                logger.info("add config to " + KafkaPropFile + ":\n" + KafkaPropConf);
            }
        }
    }

    private static void addZooCfgFile() throws IOException {
        boolean addConf = false;
        BufferedReader in = new BufferedReader(new FileReader(ZooCfgFile));
        String str;
        while ((str = in.readLine()) != null) {
            if (str.startsWith("server")){
                addConf = true;
            }
        }
        in.close();
        if (addConf){
            logger.info("already add config in " + ZooCfgFile);
        }
        else {
            BufferedWriter out = new BufferedWriter(new FileWriter(ZooCfgFile, true));
            out.write(ZooCfgConf);
            out.close();
            logger.info("add config to " + ZooCfgFile + ":\n" + ZooCfgConf);
        }
    }

    private void checkJps() throws RuntimeEngineException {
        for(int i = 1; i <= ReditHelper.numOfServers; i++){
            CommandResults commandResults = runner.runtime().runCommandInNode("server" + i, "jps");
            printResult(commandResults);
        }
    }

    private static void printResult(CommandResults commandResults){
        logger.info(commandResults.nodeName() + ": " + commandResults.command());
        if (commandResults.stdOut() != null){
            logger.info(commandResults.stdOut());
        }else {
            logger.warn(commandResults.stdErr());
        }
    }

}