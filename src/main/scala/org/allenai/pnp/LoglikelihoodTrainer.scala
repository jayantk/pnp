package org.allenai.pnp

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.google.common.base.Preconditions
import com.jayantkrish.jklol.training.LogFunction

import edu.cmu.dynet._
import edu.cmu.dynet.dynet_swig._

class LoglikelihoodTrainer(val epochs: Int, val beamSize: Int, val sumMultipleExecutions: Boolean,
    val model: PnpModel, val trainer: Trainer, val log: LogFunction) {

  Preconditions.checkArgument(model.locallyNormalized == true)
  
  import DyNetScalaHelpers._
  
  def train[A](examples: Seq[PnpExample[A]]): Unit = {
    for (i <- 0 until epochs) {
      var loss = 0.0
      var searchErrors = 0
      log.notifyIterationStart(i)
      for (example <- examples) {
        val cg = ComputationGraph.getNew
       
        val env = example.env
        val graph = model.getComputationGraph(cg)

        // Compute the distribution over correct executions.
        log.startTimer("pp_loglikelihood/forward")
        val conditional = example.conditional.beamSearch(beamSize, -1, env,
            example.conditionalExecutionScore, graph, log)
        log.stopTimer("pp_loglikelihood/forward")
        
        log.startTimer("pp_loglikelihood/build_loss")
        val exLosses = conditional.executions.map(_.env.getScore(true, cg))
        
        val lossExpr = if (exLosses.length == 0) {
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

          logsumexp(new ExpressionVector(exLosses.toList.asJava))
        }
        log.stopTimer("pp_loglikelihood/build_loss")
        
        if (lossExpr != null) {
          log.startTimer("pp_loglikelihood/eval_loss")
          loss += as_scalar(cg.incremental_forward(lossExpr))
          log.stopTimer("pp_loglikelihood/eval_loss")

          // cg.print_graphviz()
          log.startTimer("pp_loglikelihood/backward")
          cg.backward(lossExpr)
          trainer.update(1.0f)
          log.stopTimer("pp_loglikelihood/backward")
        } else {
          searchErrors += 1
        }
      }

      trainer.update_epoch()

      log.logStatistic(i, "loss", loss)
      log.logStatistic(i, "search errors", searchErrors)
      log.notifyIterationEnd(i)
    }
  }
}
