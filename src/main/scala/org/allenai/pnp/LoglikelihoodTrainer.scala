package org.allenai.pnp

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.google.common.base.Preconditions
import com.jayantkrish.jklol.training.LogFunction

import edu.cmu.dynet._
import scala.util.Random

class LoglikelihoodTrainer(val epochs: Int, val beamSize: Int, val sumMultipleExecutions: Boolean,
    val model: PnpModel, val trainer: Trainer, val log: LogFunction) {

  Preconditions.checkArgument(model.locallyNormalized == true)

  def train[A](examples: Seq[PnpExample[A]]): Unit = {
    for (i <- 0 until epochs) {
      var loss = 0.0
      var searchErrors = 0
      log.notifyIterationStart(i)

      log.startTimer("loglikelihood_trainer")
      for (example <- Random.shuffle(examples)) {
        ComputationGraph.renew()

        val env = example.env
        val context = PnpInferenceContext.init(model).setLog(log)

        // Compute the distribution over correct executions.
        log.startTimer("loglikelihood_trainer/beam")
        val conditional = example.conditional.beamSearch(beamSize, -1,
          env, context.addExecutionScore(example.conditionalExecutionScore))
        log.stopTimer("loglikelihood_trainer/beam")

        log.startTimer("loglikelihood_trainer/build_loss")
        val exLosses = conditional.executions.map(_.env.getScore)

        val logProbExpr = if (exLosses.length == 0) {
          Preconditions.checkState(sumMultipleExecutions,
              "Found %s conditional executions (expected exactly 1) for example: %s",
              conditional.executions.size.asInstanceOf[AnyRef], example)

          null
        } else if (exLosses.length == 1) {
          exLosses(0)
        } else {
          // This flag is used to ensure that training with a
          // single label per example doesn't work "by accident"
          // with an execution score that permits multiple labels.
          Preconditions.checkState(sumMultipleExecutions,
              "Found %s conditional executions (expected exactly 1) for example: %s",
              conditional.executions.size.asInstanceOf[AnyRef], example)

          Expression.logSumExp(new ExpressionVector(exLosses))
        }
        log.stopTimer("loglikelihood_trainer/build_loss")

        if (logProbExpr != null) {
          val lossExpr = -1.0f * logProbExpr
          log.startTimer("loglikelihood_trainer/eval_loss")
          loss += ComputationGraph.incrementalForward(lossExpr).toFloat
          log.stopTimer("loglikelihood_trainer/eval_loss")

          // cg.print_graphviz()
          log.startTimer("loglikelihood_trainer/backward")
          ComputationGraph.backward(lossExpr)
          trainer.update(1.0f)
          log.stopTimer("loglikelihood_trainer/backward")
        } else {
          searchErrors += 1
        }
      }
      log.stopTimer("loglikelihood_trainer")

      trainer.updateEpoch()

      log.logStatistic(i, "loss", loss)
      log.logStatistic(i, "search errors", searchErrors)
      log.notifyIterationEnd(i)
    }
  }
}
