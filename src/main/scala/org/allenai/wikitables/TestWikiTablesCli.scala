package org.allenai.wikitables

import scala.collection.JavaConverters._

import org.allenai.pnp.Env
import org.allenai.pnp.PnpInferenceContext
import org.allenai.pnp.PnpModel
import org.allenai.pnp.semparse.EntityLinking
import org.allenai.pnp.semparse.SemanticParser
import org.allenai.pnp.semparse.SemanticParserLoss
import org.allenai.pnp.semparse.SemanticParserState

import com.jayantkrish.jklol.ccg.lambda.ExpressionParser
import com.jayantkrish.jklol.ccg.lambda.TypeDeclaration
import com.jayantkrish.jklol.ccg.lambda2.ExpressionComparator
import com.jayantkrish.jklol.ccg.lambda2.ExpressionSimplifier
import com.jayantkrish.jklol.ccg.lambda2.SimplificationComparator
import com.jayantkrish.jklol.cli.AbstractCli

import edu.cmu.dynet._
import joptsimple.OptionParser
import joptsimple.OptionSet
import joptsimple.OptionSpec

class TestWikiTablesCli extends AbstractCli() {

  var testDataOpt: OptionSpec[String] = null
  var derivationsPathOpt: OptionSpec[String] = null
  var modelOpt: OptionSpec[String] = null

  var beamSizeOpt: OptionSpec[Integer] = null
  var evaluateDpdOpt: OptionSpec[Void] = null
  var maxDerivationsOpt: OptionSpec[Integer] = null

  override def initializeOptions(parser: OptionParser): Unit = {
    testDataOpt = parser.accepts("testData").withRequiredArg().ofType(classOf[String]).withValuesSeparatedBy(',').required()
    modelOpt = parser.accepts("model").withRequiredArg().ofType(classOf[String]).required()

    beamSizeOpt = parser.accepts("beamSize").withRequiredArg().ofType(classOf[Integer]).defaultsTo(5)
    evaluateDpdOpt = parser.accepts("evaluateDpd")
    maxDerivationsOpt = parser.accepts("maxDerivations").withRequiredArg().ofType(classOf[Integer]).defaultsTo(-1)
  }

  override def run(options: OptionSet): Unit = {
    Initialize.initialize(Map("dynet-mem" -> "2048"))

    // Initialize expression processing for Wikitables logical forms.
    val simplifier = ExpressionSimplifier.lambdaCalculus()
    val comparator = new SimplificationComparator(simplifier)
    val logicalFormParser = ExpressionParser.expression2();
    val typeDeclaration = new WikiTablesTypeDeclaration()

    // Read in serialized semantic parser
    val loader = new ModelLoader(options.valueOf(modelOpt))
    val model = PnpModel.load(loader)
    val parser = SemanticParser.load(loader, model)
    loader.done()

    // Read test data.
    val testData = options.valuesOf(testDataOpt).asScala.flatMap(filename => {
      WikiTablesUtil.loadDataset(filename, false, null, options.valueOf(maxDerivationsOpt))
    })
    println("Read " + testData.size + " test examples")

    val entityMap = testData.map(example => (example, WikiTablesDataProcessor.getEntityLinking(example).asScala)).toMap
    testData.foreach(x => WikiTablesUtil.preprocessExample(x, parser.vocab, entityMap(x), typeDeclaration))

    /*
    println("*** Validating test set action space ***")
    val testSeparatedLfs = WikiTablesSemanticParserCli.getCcgDataset(testPreprocessed)
    SemanticParserUtils.validateActionSpace(testSeparatedLfs, parser, typeDeclaration)
    */

    val testResults = test(testData, parser, options.valueOf(beamSizeOpt),
        options.has(evaluateDpdOpt), typeDeclaration, comparator)
    println("*** Evaluation results ***")
    println(testResults)
  }

  /** Evaluate the test accuracy of parser on examples. Logical
    * forms are compared for equality using comparator.
    */
  def test(examples: Seq[WikiTablesExample], parser: SemanticParser, beamSize: Int,
      evaluateDpd: Boolean, typeDeclaration: TypeDeclaration,
      comparator: ExpressionComparator): SemanticParserLoss = {

    println("")
    var numCorrect = 0
    var numCorrectAt10 = 0
    for (e <- examples) {
      val sent = e.sentence
      println("example id: " + e.id)
      println(sent.getWords.asScala.mkString(" "))
      println(sent.getAnnotation("originalTokens").asInstanceOf[List[String]].mkString(" "))

      val entityLinking = sent.getAnnotation("entityLinking").asInstanceOf[EntityLinking]
      val dist = parser.parse(sent.getAnnotation("tokenIds").asInstanceOf[Array[Int]],
          entityLinking)

      ComputationGraph.renew()
      val context = PnpInferenceContext.init(parser.model)
      val results = dist.beamSearch(beamSize, 75, Env.init, context)

      val beam = results.executions.slice(0, 10)
      val correct = beam.map { x =>
        val expression = x.value.decodeExpression

        val isCorrect = if (evaluateDpd) {
          // Evaluate the logical forms using the output of dynamic programming on denotations.
          e.logicalForms.size > 0 && e.logicalForms.map(x => comparator.equals(x, expression)).reduce(_ || _)
        } else {
          // Evaluate the logical form by executing it.
          e.isFormulaCorrect(expression)
        }
        
        if (isCorrect) {
          println("* " + x.logProb.formatted("%02.3f") + "  " + expression)
          true
        } else {
          println("  " + x.logProb.formatted("%02.3f") + "  " + expression)
          false
        }
      }

      if (correct.length > 0 && correct(0)) {
        numCorrect += 1
      }
      if (correct.fold(false)(_ || _)) {
        numCorrectAt10 += 1
      }

      // Re-parse with a label oracle to find the highest-scoring correct parses.
      val oracle = parser.getMultiLabelScore(e.logicalForms, entityLinking, typeDeclaration)
      if (oracle.isDefined) { 
        val oracleContext = PnpInferenceContext.init(parser.model).addExecutionScore(oracle.get)
        val oracleResults = dist.beamSearch(beamSize, 75, Env.init, oracleContext)
            
        oracleResults.executions.map { x =>
        val expression = x.value.decodeExpression
        println("o " + x.logProb.formatted("%02.3f") + "  " + expression)
        }
      } else {
        println("  No correct logical forms in oracle.")
      }
      
      // Print the attentions of the best predicted derivation
      if (beam.nonEmpty) {
        printAttentions(beam(0).value, e.sentence.getWords.asScala.toArray)
      }
    }

    val loss = SemanticParserLoss(numCorrect, numCorrectAt10, examples.length)
    loss
  }

  def printAttentions(state: SemanticParserState, tokens: Array[String]): Unit = {
    val templates = state.getTemplates
    val attentions = state.getAttentions
    for (i <- 0 until templates.length) {
      val values = ComputationGraph.incrementalForward(attentions(i)).toSeq()
      val maxIndex = values.zipWithIndex.max._2

      val tokenStrings = for {
        j <- 0 until values.length
      } yield {
        val color = if (j == maxIndex) {
          Console.RED
        } else if (values(j) > 0.1) {
          Console.YELLOW
        } else {
          Console.RESET
        }
        color + tokens(j) + Console.RESET
      }

      println("  " + tokenStrings.mkString(" ") + " " + templates(i))
    }
  }
}

object TestWikiTablesCli {

  def main(args: Array[String]): Unit = {
    (new TestWikiTablesCli()).run(args)
  }
}
