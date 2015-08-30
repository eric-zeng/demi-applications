package akka.dispatch.verification

import akka.actor.Props
import scalax.collection.mutable.Graph,
       scalax.collection.GraphEdge.DiEdge,
       scalax.collection.edge.LDiEdge

// Used in conjuction with DDMin for external events.
class FungibleClockTestOracle(
  schedulerConfig: SchedulerConfig,
  originalTrace: EventTrace,
  actorNameProps: Seq[Tuple2[Props, String]],
  resolutionStrategy: AmbiguityResolutionStrategy=new BackTrackStrategy,
  testScheduler:TestScheduler.TestScheduler=TestScheduler.STSSched,
  depGraph: Option[Graph[Unique,DiEdge]]=None,
  preTest: Option[STSScheduler.PreTestCallback]=None,
  postTest: Option[STSScheduler.PostTestCallback]=None) extends TestOracle {

  def getName = "FungibleClocks"

  // Should already be specific in schedulerConfig
  def setInvariant(invariant: Invariant) {}

  // TODO(cs): possible optimization: if we ever find a smaller trace that
  // triggers the bug, pass in that smaller trace from then on, rather than
  // originalTrace.

  def test(events: Seq[ExternalEvent],
           violation_fingerprint: ViolationFingerprint,
           stats: MinimizationStats,
           initializationRoutine:Option[()=>Any]=None) : Option[EventTrace] = {
    val minimizer = new FungibleClockMinimizer(
      schedulerConfig,
      events,
      originalTrace,
      actorNameProps,
      violation_fingerprint,
      skipClockClusters=true,
      stats=Some(stats),
      resolutionStrategy=resolutionStrategy,
      testScheduler=testScheduler,
      depGraph=depGraph,
      initializationRoutine=initializationRoutine,
      preTest=preTest,
      postTest=postTest)

    val (_, trace) = minimizer.minimize()
    if (trace != originalTrace) {
      return Some(trace)
    }
    return None
  }
}
