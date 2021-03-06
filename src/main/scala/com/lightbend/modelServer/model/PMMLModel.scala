package com.lightbend.modelServer.model

/**
  * Created by boris on 5/9/17.
  *
  * Class for PMML model
  */

import org.jpmml.evaluator.{FieldValue, ModelEvaluatorFactory, TargetField}
import org.jpmml.evaluator.visitors._
import org.jpmml.model.PMMLUtil
import java.io.InputStream

import org.jpmml.evaluator.Computable
import com.lightbend.model.winerecord.WineRecord
import org.dmg.pmml.{PMML, FieldName}

import scala.collection._
import scala.collection.JavaConversions._


class PMMLModel(inputStream: InputStream) extends Model {

  var arguments = mutable.Map[FieldName, FieldValue]()

  // Marshall PMML
  val pmml = PMMLUtil.unmarshal(inputStream)

  // Optimize model// Optimize model
  PMMLModel.optimize(pmml)

  // Create and verify evaluator
  val modelEvaluatorFactory = ModelEvaluatorFactory.newInstance
  val evaluator = modelEvaluatorFactory.newModelEvaluator(pmml)
  evaluator.verify()

  // Get input/target fields
  val inputFields = evaluator.getInputFields
  val target: TargetField = evaluator.getTargetFields.get(0)
  val tname = target.getName

  override def score(input: AnyVal): AnyVal = {
    val inputs = input.asInstanceOf[WineRecord]
    arguments.clear()
    inputFields.foreach(field => {
      arguments.put(field.getName, field.prepare(getValueByName(inputs, field.getName.getValue)))
    })

    // Calculate Output// Calculate Output
    val result = evaluator.evaluate(arguments)

    // Prepare output
    result.get(tname) match {
      case c : Computable => c.getResult.toString.toDouble
      case v : Any => v.asInstanceOf[Double]
    }
  }

  override def cleanup(): Unit = {}

  private def getValueByName(inputs : WineRecord, name: String) : Double =
  PMMLModel.names.get(name) match {
    case Some(index) => {
     val v = inputs.getFieldByNumber(index + 1)
      v.asInstanceOf[Float].toDouble
    }
    case _ => .0
  }

}

object PMMLModel{
  private val optimizers = Array(new ExpressionOptimizer, new FieldOptimizer, new PredicateOptimizer, new GeneralRegressionModelOptimizer, new NaiveBayesModelOptimizer, new RegressionModelOptimizer)
  def optimize(pmml : PMML) = this.synchronized {
    optimizers.foreach(opt =>
      try {
        opt.applyTo(pmml)
      } catch {
        case t: Throwable => {
          println(s"Error optimizing model for optimizer $opt")
          t.printStackTrace()
        }
      }
    )
  }
  private val names = Map("fixed acidity" -> 0,
    "volatile acidity" -> 1,"citric acid" ->2,"residual sugar" -> 3,
    "chlorides" -> 4,"free sulfur dioxide" -> 5,"total sulfur dioxide" -> 6,
    "density" -> 7,"pH" -> 8,"sulphates" ->9,"alcohol" -> 10)
}