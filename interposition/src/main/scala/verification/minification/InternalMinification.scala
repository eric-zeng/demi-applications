package akka.dispatch.verification

import scala.collection.mutable.SynchronizedQueue
import scala.collection.mutable.Queue
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ArrayBuffer
import akka.actor.Props

import org.slf4j.LoggerFactory,
       ch.qos.logback.classic.Level,
       ch.qos.logback.classic.Logger

// Minimizes internal events. One-time-use -- you shouldn't invoke minimize() more
// than once.
// TODO(cs): ultimately, we should try supporting DPOR removal of
// internals.
trait InternalEventMinimizer {
  def minimize(): Tuple2[MinimizationStats, EventTrace]
}

class STSSchedMinimizer(
  mcs: Seq[ExternalEvent],
  verified_mcs: EventTrace,
  violation: ViolationFingerprint,
  removalStrategy: RemovalStrategy,
  schedulerConfig: SchedulerConfig,
  actorNameProps: Seq[Tuple2[Props, String]],
  initializationRoutine: Option[() => Any]=None,
  preTest: Option[STSScheduler.PreTestCallback]=None,
  postTest: Option[STSScheduler.PostTestCallback]=None,
  stats: Option[MinimizationStats]=None)
  extends InternalEventMinimizer {

  val logger = LoggerFactory.getLogger("IntMin")

  def minimize(): Tuple2[MinimizationStats, EventTrace] = {
    val _stats = stats match {
      case Some(s) => s
      case None => new MinimizationStats
    }
    _stats.updateStrategy("InternalMin", "STSSched")

    val origTrace = verified_mcs.filterCheckpointMessages.filterFailureDetectorMessages
    var lastFailingTrace = origTrace
    var lastFailingSize = RunnerUtils.countMsgEvents(lastFailingTrace.filterCheckpointMessages.filterFailureDetectorMessages)
    // { (snd,rcv,fingerprint) }
    val prunedOverall = new MultiSet[(String,String,MessageFingerprint)]
    // TODO(cs): make this more efficient? Currently O(n^2) overall.
    var violationTriggered = false
    var nextTrace = removalStrategy.getNextTrace(lastFailingTrace,
      prunedOverall, violationTriggered)

    _stats.record_prune_start
    while (!nextTrace.isEmpty) {
      RunnerUtils.testWithStsSched(schedulerConfig, mcs, nextTrace.get, actorNameProps,
                       violation, _stats, initializationRoutine=initializationRoutine,
                       preTest=preTest, postTest=postTest) match {
        case Some(trace) =>
          // Some other events may have been pruned by virtue of being absent. So
          // we reassign lastFailingTrace, then pick then next trace based on
          // it.
          violationTriggered = true
          val filteredTrace = trace.filterCheckpointMessages.filterFailureDetectorMessages
          val origSize = RunnerUtils.countMsgEvents(lastFailingTrace.filterCheckpointMessages.filterFailureDetectorMessages)
          val newSize = RunnerUtils.countMsgEvents(filteredTrace)
          val diff = origSize - newSize
          logger.info("Ignoring worked! Pruned " + diff + "/" + origSize + " deliveries")

          val priorDeliveries = new MultiSet[(String,String,MessageFingerprint)]
          priorDeliveries ++= RunnerUtils.getFingerprintedDeliveries(
            lastFailingTrace.filterCheckpointMessages.filterFailureDetectorMessages,
            schedulerConfig.messageFingerprinter
          )

          val newDeliveries = new MultiSet[(String,String,MessageFingerprint)]
          newDeliveries ++= RunnerUtils.getFingerprintedDeliveries(
            filteredTrace,
            schedulerConfig.messageFingerprinter
          )

          val prunedThisRun = priorDeliveries.setDifference(newDeliveries)
          logger.debug("Pruned: [ignore TimerDeliveries] ")
          prunedThisRun.foreach { case e => logger.debug("" + e) }
          logger.debug("---")

          prunedOverall ++= prunedThisRun

          lastFailingTrace = filteredTrace
          lastFailingTrace.setOriginalExternalEvents(mcs)
          lastFailingSize = newSize
          _stats.record_internal_size(lastFailingSize)
        case None =>
          // We didn't trigger the violation.
          violationTriggered = false
          _stats.record_internal_size(lastFailingSize)
          logger.info("Ignoring didn't work.")
          None
      }
      nextTrace = removalStrategy.getNextTrace(lastFailingTrace,
        prunedOverall, violationTriggered)
    }
    _stats.record_prune_end
    val origSize = RunnerUtils.countMsgEvents(origTrace)
    val newSize = RunnerUtils.countMsgEvents(lastFailingTrace.filterCheckpointMessages.filterFailureDetectorMessages)
    val diff = origSize - newSize
    logger.info("Pruned " + diff + "/" + origSize + " deliveries (" + removalStrategy.unignorable + " unignorable)" +
            " in " + _stats.inner().total_replays + " replays")
    return (_stats, lastFailingTrace.filterCheckpointMessages.filterFailureDetectorMessages)
  }
}

// An "Iterator" for deciding which subsequence of events we should try next.
// Inheritors must implement the "choiceFilter" method.
abstract class RemovalStrategy(verified_mcs: EventTrace, messageFingerprinter: FingerprintFactory) {
  // MsgEvents we've tried ignoring so far. MultiSet to account for duplicate MsgEvent's
  val triedIgnoring = new MultiSet[(String, String, MessageFingerprint)]
  var _unignorable = 0
  val logger = LoggerFactory.getLogger("RemovalStrategy")

  // Populate triedIgnoring with all events that lie between a
  // UnignorableEvents block. Also, external messages.
  private def init() {
    var inUnignorableBlock = false

    verified_mcs.events.foreach {
      case BeginUnignorableEvents =>
        inUnignorableBlock = true
      case EndUnignorableEvents =>
        inUnignorableBlock = false
      case m @ UniqueMsgEvent(MsgEvent(snd, rcv, msg), id) =>
        if (EventTypes.isExternal(m) || inUnignorableBlock) {
          triedIgnoring += ((snd, rcv, messageFingerprinter.fingerprint(msg)))
        }
      case t @ UniqueTimerDelivery(TimerDelivery(snd, rcv, msg), id) =>
        if (inUnignorableBlock) {
          triedIgnoring += ((snd, rcv, messageFingerprinter.fingerprint(msg)))
        }
      case _ =>
    }

    _unignorable = triedIgnoring.size
  }

  init()

  def unignorable: Int = _unignorable

  // Filter out the next MsgEvent, and return the resulting EventTrace.
  // If we've tried filtering out all MsgEvents, return None.
  def getNextTrace(trace: EventTrace,
                   alreadyRemoved: MultiSet[(String,String,MessageFingerprint)],
                   violationTriggered: Boolean)
                 : Option[EventTrace] = {
    // Track what events we've kept so far because we
    // already tried ignoring them previously. MultiSet to account for
    // duplicate MsgEvent's. TODO(cs): this may lead to some ambiguous cases.
    val keysThisIteration = new MultiSet[(String, String, MessageFingerprint)]
    // We already tried 'keeping' prior events that were successfully ignored
    // but no longer show up in this trace.
    keysThisIteration ++= alreadyRemoved
    // Whether we've found the event we're going to try ignoring next.
    var foundIgnoredEvent = false

    // Return whether we should keep this event
    def checkDelivery(snd: String, rcv: String, msg: Any): Boolean = {
      val key = (snd, rcv, messageFingerprinter.fingerprint(msg))
      keysThisIteration += key
      if (foundIgnoredEvent) {
        // We already chose our event to ignore. Keep all other events.
        return true
      } else {
        // Check if we should ignore or keep this one.
        if (keysThisIteration.count(key) > triedIgnoring.count(key) &&
            choiceFilter(key._1, key._2, key._3)) {
          // We found something to ignore
          logger.info("Ignoring next: " + key)
          foundIgnoredEvent = true
          triedIgnoring += key
          return false
        } else {
          // Keep this one; we already tried ignoring it, but it was
          // not prunable.
          return true
        }
      }
    }

    // We accomplish two tasks as we iterate through trace:
    //   - Finding the next event we want to ignore
    //   - Filtering (keeping) everything that we don't want to ignore
    val modified = trace.events.flatMap {
      case m @ UniqueMsgEvent(MsgEvent(snd, rcv, msg), id) =>
        if (checkDelivery(snd, rcv, msg)) {
          Some(m)
        } else {
          None
        }
      case t @ UniqueTimerDelivery(TimerDelivery(snd, rcv, msg), id) =>
        if (checkDelivery(snd, rcv, msg)) {
          Some(t)
        } else {
          None
        }
      case _: MsgEvent =>
        throw new IllegalArgumentException("Must be UniqueMsgEvent")
      case e =>
        Some(e)
    }
    if (foundIgnoredEvent) {
      val queue = new SynchronizedQueue[Event]
      queue ++= modified
      return Some(new EventTrace(queue,
                                 verified_mcs.original_externals))
    }
    // We didn't find anything else to ignore, so we're done
    return None
  }

  // choiceFilter: if the given (snd,rcv,fingerprint,UniqueMsgEvent.id) is eligible to be
  // picked next, return true or false if it should be picked.
  // Guarenteed to be invoked in left-to-right order
  def choiceFilter(snd: String, rcv: String,
    fingerprint: MessageFingerprint) : Boolean
}

class LeftToRightOneAtATime(
  verified_mcs: EventTrace, messageFingerprinter:  FingerprintFactory)
  extends RemovalStrategy(verified_mcs, messageFingerprinter) {

  override def choiceFilter(s: String, r: String, f: MessageFingerprint) = true
}

// For any <src, dst> pair, maintains FIFO delivery (i.e. assumes TCP as the
// underlying transport medium), and only tries removing the last message
// (iteratively) from each FIFO queue.
//
// Assumes that the original trace was generated using a src,dst FIFO delivery
// discipline.
class SrcDstFIFORemoval(
  verified_mcs: EventTrace, messageFingerprinter:  FingerprintFactory)
  extends RemovalStrategy(verified_mcs, messageFingerprinter) {

  // N.B. doesn't actually contain TimerDeliveries; only real messages. Timers
  // are harder to reason about, and this is just an optimization anyway, so
  // just try removing them in random order.
  // Queue is: ids of UniqueMsgEvents.
  val srcDstToMessages = new HashMap[(String, String), Vector[MessageFingerprint]]

  verified_mcs.events.foreach {
    case UniqueMsgEvent(MsgEvent("deadLetters", rcv, msg), id) =>
    case m @ UniqueMsgEvent(MsgEvent(snd, rcv, msg), id) =>
      val vec = srcDstToMessages.getOrElse((snd,rcv), Vector[MessageFingerprint]())
      val newVec = vec :+ messageFingerprinter.fingerprint(msg)
      srcDstToMessages((snd,rcv)) = newVec
    case _ =>
  }

  // The src,dst pair we chose last time getNextTrace was invoked.
  var previouslyChosenSrcDst : Option[(String,String)] = None
  // To deal with the possibility of multiple indistinguishable pending
  // messages at different points in the execution:
  // As choiceFilter is invoked, mark off where we are in the ArrayList for a
  // given src,dst pair. Only return true if we're at the end of the
  // ArrayList.
  val srcDstToCurrentIdx = new HashMap[(String, String), Int]
  def resetSrcDstToCurrentIdx() {
    srcDstToMessages.keys.foreach {
      case (src,dst) =>
        srcDstToCurrentIdx((src,dst)) = -1
    }
  }
  resetSrcDstToCurrentIdx()

  def choiceFilter(snd: String, rcv: String,
                   fingerprint: MessageFingerprint) : Boolean = {
    if (srcDstToMessages contains ((snd,rcv))) {
      srcDstToCurrentIdx((snd,rcv)) += 1
      val idx = srcDstToCurrentIdx((snd,rcv))
      val lst = srcDstToMessages((snd,rcv))
      if (idx == lst.length - 1) {
        // assert(lst(idx) == fingerprint)
        if (lst(idx) != fingerprint) {
          logger.error(s"lst(idx) ${lst(idx)} != fingerprint ${fingerprint}")
        }
        srcDstToMessages((snd,rcv)) = srcDstToMessages((snd,rcv)).dropRight(1)
        if (srcDstToMessages((snd,rcv)).isEmpty) {
          logger.info("src,dst is done!: " + ((snd,rcv)))
          srcDstToMessages -= ((snd,rcv))
        }
        previouslyChosenSrcDst = Some((snd,rcv))
        return true
      }
    }

    previouslyChosenSrcDst = None

    if (snd == "deadLetters") { // Timer
      return true
    }

    return false
  }

  // Filter out the next MsgEvent, and return the resulting EventTrace.
  // If we've tried filtering out all MsgEvents, return None.
  // TODO(cs): account for Kills & HardKills, which would reset the FIFO queues
  // involving that node.
  override def getNextTrace(trace: EventTrace,
                   alreadyRemoved: MultiSet[(String,String,MessageFingerprint)],
                   violationTriggeredLastRun: Boolean): Option[EventTrace] = {
    if (!violationTriggeredLastRun && !previouslyChosenSrcDst.isEmpty) {
      // Ignoring didn't work, so this src,dst is done.
      logger.info("src,dst is done: " + previouslyChosenSrcDst.get)
      srcDstToMessages -= previouslyChosenSrcDst.get
    }

    if (violationTriggeredLastRun) {
      // Some of the messages in srcDstToMessages may have been pruned as
      // "freebies" -- i.e. they may have been absent --  in the last run.
      // -> Recompute srcDstToMessages (in reverse order, in case there are
      // multiple indistinguishable events)
      srcDstToMessages.clear
      val alreadyRemovedCopy = new MultiSet[(String,String,MessageFingerprint)]
      alreadyRemovedCopy ++= alreadyRemoved
      verified_mcs.events.reverse.foreach {
        case UniqueMsgEvent(MsgEvent("deadLetters", rcv, msg), id) =>
        case m @ UniqueMsgEvent(MsgEvent(snd, rcv, msg), id) =>
          val tuple = ((snd,rcv,messageFingerprinter.fingerprint(msg)))
          if (alreadyRemovedCopy contains tuple) {
            alreadyRemovedCopy -= tuple
          } else {
            val vec = srcDstToMessages.getOrElse((snd,rcv), Vector[MessageFingerprint]())
            // N.B. prepend, not append, so that it comes out in the same order
            // as the original trace
            val newVec = vec.+:(messageFingerprinter.fingerprint(msg))
            srcDstToMessages((snd,rcv)) = newVec
          }
        case _ =>
      }
    }
    resetSrcDstToCurrentIdx()

    return super.getNextTrace(trace, alreadyRemoved, violationTriggeredLastRun)
  }
}
