package akka.dispatch.verification

import java.io.{File, PrintWriter}
import synoptic.main.SynopticMain
import scala.collection.mutable.ListBuffer

// A RemovalStrategy that maintains a model of the program's state
// machine, and uses the model to decide which schedules to explore next.
// We currently use Synoptic
// (http://homes.cs.washington.edu/~mernst/pubs/synoptic-fse2011.pdf)
// to build the model from console output of each execution we've tried so far.
class StateMachineRemoval(originalTrace: EventTrace, messageFingerprinter: FingerprintFactory) extends RemovalStrategy {
  // Return how many events we were unwilling to ignore, e.g. because they've
  // been marked by the application as unignorable.
  def unignorable: Int = 0

  var timesRun: Int = 0

  // Return the next schedule to explore.
  // If there aren't any more schedules to check, return None.
  // Args:
  //   - lastFailingTrace: the most recent schedule we've explored that has successfuly
  //   resulted in the invariant violation.
  //   - alreadyRemoved: any (src,dst,message fingerprint) pairs from the
  //   original schedule that we've already successfully decided aren't
  //   necessary
  //   - violationTriggered: whether the last schedule we returned
  //   successfully triggered the invariant violation, i.e. whether
  //   lastFailingTrace == the most recent trace we returned from getNextTrace.
  override def getNextTrace(lastFailingTrace: EventTrace,
                   alreadyRemoved: MultiSet[(String,String,MessageFingerprint)],
                   violationTriggered: Boolean): Option[EventTrace] = {
    timesRun = timesRun + 1
    val metaTrace = HistoricalEventTraces.current

    val file = new File("temp/trace_log_" + timesRun + ".tmp")
    file.getParentFile.mkdirs

    val writer = new PrintWriter(file)
    metaTrace.getOrderedLogOutput foreach { log =>
      writer.println(log)
    }
    writer.close

    val regexes = Array(
      "^.*there\\sis\\sno\\sleader.*(?<TYPE=>no_leader)$",
      "^.*Initializing\\selection.*(?<TYPE=>starting_election)$"
      // "^\\[(?<dispatcher>.+)\]\\s\\[(?<member>.+1)\\]\\s.*there\\sis\\sno\\sleader.*(?<TYPE=>no_leader)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Tried\\sto\\sinitialize\\selection\\swith\\sno\\smembers.*(?<TYPE=>failed_election_no_members)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Initializing\\selection.*(?<TYPE=>starting_election)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Rejecting\\sRequestVote\\smsg\\sby.*Received\\sstale.*(?<TYPE=>rejected_vote_request_stale)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Revert\\sto\\sfollower\\sstate.*(?<TYPE=>revert_to_follower_state)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Voting\\sfor.*(?<TYPE=>voting)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Rejecting\\svote\\sfor.*already\\svoted\\sfor.*(?<TYPE=>rejected_vote_request_already_voted)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Rejecting\\sVoteCandidate\\smsg\\sby.*Received\\sstale.*(?<TYPE=>rejected_vote_candidate_stale)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Received\\svote\\sby.*Won\\selection\\swith.*of.*votes.*(?<TYPE=>won_election)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Received\\svote\\sby.*Have.*of.*votes.*(?<TYPE=>recieved_vote)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Candidate\\sis\\sdeclined\\sby.*in\\sterm.*(?<TYPE=>candidate_declined)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Reverting\\sto\\sFollower,\\sbecause\\sgot\\sAppendEntries\\sfrom\\sLeader\\sin.*,\\sbut\\sam\\sin.*(?<TYPE=>reverting_to_follower_state_AppendEntries)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Voting\\stimeout,\\sstarting\\sa\\snew\\selection.*(?<TYPE=>voting_timeout_starting_new_election)$" 
      // "^\\[(?<dispatcher>.+)\\]\\s\\[(?<member>.+1)\\]\\s.*Voting\\stimeout,\\sunable\\sto\\sstart\\selection,\\sdon't\\sknow\\senough\\snodes.*(?<TYPE=>voting_timeout_too_few_nodes)$" 
    )

    val regexArgs = regexes flatMap { regex =>
      Array("-r", regex)
    }

    val args = Array("temp/trace_log_" + timesRun + ".tmp") ++ regexArgs ++ Array("-o", "temp/output_" + timesRun, "-i")
    System.out.println("Running synoptic ")
    args foreach { arg => System.out.print(arg) }
    System.out.println()
    val main = SynopticMain.processArgs(args)
    val pGraph = main.createInitialPartitionGraph()
    if (pGraph != null) {
      main.runSynoptic(pGraph)
      None
    } else {
      None
    }
  }
}

// Stores all (Meta)EventTraces that have been executed in the past
object HistoricalEventTraces {
  def current: MetaEventTrace = traces.last

  // In order of least recent to most recent
  val traces = new ListBuffer[MetaEventTrace]

  // If you want fast lookup of EventTraces, you could populate a HashMap here:
  // { EventTrace -> MetaEventTrace }
}
