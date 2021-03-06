package com.lightbend.modelServer

import java.util.Properties

import com.lightbend.kafka.ModelServingConfiguration
import com.lightbend.model.winerecord.WineRecord
import com.lightbend.modelServer.typeschema.ByteArraySchema
import org.apache.flink.api.scala._
import org.apache.flink.configuration.{ConfigConstants, Configuration, QueryableStateOptions}
import org.apache.flink.runtime.minicluster.LocalFlinkMiniCluster
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010


/**
  * Created by boris on 5/9/17.
  * loosely based on http://dataartisans.github.io/flink-training/exercises/eventTimeJoin.html approach
  * for queriable state
  *   https://github.com/dataArtisans/flink-queryable_state_demo/blob/master/README.md
  * Using Flink min server to enable Queryable data access
  *   see https://github.com/dataArtisans/flink-queryable_state_demo/blob/master/src/main/java/com/dataartisans/queryablestatedemo/EventCountJob.java
  *
  * This little application is based on a RichCoFlatMapFunction which works on a non keyed streams. It is
  * applicable when a single applications serves a single model(model set) for a single data type.
  * Scaling of the application is based on the parallelism of input stream and RichCoFlatMapFunction.
  * The model is broadcasted to all RichCoFlatMapFunction instances. The messages are processed by different
  * instances of RichCoFlatMapFunction in a round-robin fashion.
  */

object ModelServingFlatJob {

  def main(args: Array[String]): Unit = {
    executeLocal()
//    executeServer()
  }

  // Execute on the local Flink server - to test queariable state
  def executeServer() : Unit = {

    // We use a mini cluster here for sake of simplicity, because I don't want
    // to require a Flink installation to run this demo. Everything should be
    // contained in this JAR.

    val port = 6124
    val parallelism = 4
    val config = new Configuration()
    config.setInteger(ConfigConstants.JOB_MANAGER_IPC_PORT_KEY, port)
    config.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, 1)
    config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, parallelism)
    // In a non MiniCluster setup queryable state is enabled by default.
    config.setBoolean(QueryableStateOptions.SERVER_ENABLE, true)

    // Create a local Flink server
    val flinkCluster = new LocalFlinkMiniCluster(config, false)
    try {
      // Start server and create environment
      flinkCluster.start(true);
      val env = StreamExecutionEnvironment.createRemoteEnvironment("localhost", port, parallelism)
      // Build Graph
      buildGraph(env)
      env.execute()
      val jobGraph = env.getStreamGraph.getJobGraph
      // Submit to the server and wait for completion
      flinkCluster.submitJobAndWait(jobGraph, false)
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

  // Execute localle in the environment
  def executeLocal() : Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    buildGraph(env)
    System.out.println("[info] Job ID: " + env.getStreamGraph.getJobGraph.getJobID)
    env.execute()
  }

  // Build execution Graph
  def buildGraph(env : StreamExecutionEnvironment) : Unit = {
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.enableCheckpointing(5000)

    // configure Kafka consumer
    // Data
    val dataKafkaProps = new Properties
    dataKafkaProps.setProperty("zookeeper.connect", ModelServingConfiguration.LOCAL_ZOOKEEPER_HOST)
    dataKafkaProps.setProperty("bootstrap.servers", ModelServingConfiguration.LOCAL_KAFKA_BROKER)
    dataKafkaProps.setProperty("group.id", ModelServingConfiguration.DATA_GROUP)
    // always read the Kafka topic from the current location
    dataKafkaProps.setProperty("auto.offset.reset", "latest")

    // Model
    val modelKafkaProps = new Properties
    modelKafkaProps.setProperty("zookeeper.connect", ModelServingConfiguration.LOCAL_ZOOKEEPER_HOST)
    modelKafkaProps.setProperty("bootstrap.servers", ModelServingConfiguration.LOCAL_KAFKA_BROKER)
    modelKafkaProps.setProperty("group.id", ModelServingConfiguration.MODELS_GROUP)
    // always read the Kafka topic from the current location
    modelKafkaProps.setProperty("auto.offset.reset", "latest")

    // create a Kafka consumers
    // Data
    val dataConsumer = new FlinkKafkaConsumer010[Array[Byte]](
      ModelServingConfiguration.DATA_TOPIC,
      new ByteArraySchema,
      dataKafkaProps
    )

    // Model
    val modelConsumer = new FlinkKafkaConsumer010[Array[Byte]](
      ModelServingConfiguration.MODELS_TOPIC,
      new ByteArraySchema,
      modelKafkaProps
    )

    // Create input data streams
    val modelsStream = env.addSource(modelConsumer)
    val dataStream = env.addSource(dataConsumer)

    // Read data from streams
    val models = modelsStream.map(ModelToServe.fromByteArray(_))
      .flatMap(BadDataHandler[ModelToServe])
      .broadcast
    val data = dataStream.map(DataRecord.fromByteArray(_))
      .flatMap(BadDataHandler[WineRecord])

    // Merge streams
    data
      .connect(models)
      .flatMap(DataProcessorMap())
  }
}