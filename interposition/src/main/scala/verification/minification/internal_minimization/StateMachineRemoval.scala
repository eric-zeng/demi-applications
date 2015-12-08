package akka.dispatch.verification

import java.io.{File, PrintWriter}
import java.util.Scanner
import org.slf4j.LoggerFactory
import synoptic.main.SynopticMain
import scala.collection.mutable.{Queue, ListBuffer, Stack, SynchronizedQueue}

// A RemovalStrategy that maintains a model of the program's state
// machine, and uses the model to decide which schedules to explore next.
// We currently use Synoptic
// (http://homes.cs.washington.edu/~mernst/pubs/synoptic-fse2011.pdf)
// to build the model from console output of each execution we've tried so far.
class StateMachineRemoval(originalTrace: EventTrace, messageFingerprinter: FingerprintFactory) extends RemovalStrategy {
  val logger = LoggerFactory.getLogger("StateMachineRemoval")

  // Return how many events we were unwilling to ignore, e.g. because they've
  // been marked by the application as unignorable.
  def unignorable: Int = 0

  var timesRun: Int = 0

  val states = Array(
    State(".*there\\\\sis\\\\sno\\\\sleader.*", "no_leader"),
    State(".*Initializing\\\\selection.*","initializing_election"),
    State(".*Tried\\sto\\sinitialize\\selection\\swith\\sno\\smembers.*", "initializing_election_no_members"),
    State(".*Rejecting\\sRequestVote\\smsg\\sby.*Received\\sstale.*", "rejected_vote_request_stale"),
    State(".*Revert\\sto\\sfollower\\sstate.*", "revert_to_follower_state"),
    State(".*Voting\\sfor.*", "voting"),
    State(".*Rejecting\\svote\\sfor.*already\\svoted\\sfor.*", "rejected_vote_request_already_voted"),
    State(".*Rejecting\\sVoteCandidate\\smsg\\sby.*Received\\sstale.*", "rejected_vote_candidate_stale"),
    State(".*Received\\svote\\sby.*Won\\selection\\swith.*of.*votes.*", "won_election"),
    State(".*Received\\svote\\sby.*Have.*of.*votes.*", "recieved_vote"),
    State(".*Candidate\\sis\\sdeclined\\sby.*in\\sterm.*", "candidate_declined"),
    State(".*Reverting\\sto\\sFollower,\\sbecause\\sgot\\sAppendEntries\\sfrom\\sLeader\\sin.*,\\sbut\\sam\\sin.*", "reverting_to_follower_state_AppendEntries"),
    State(".*Voting\\stimeout,\\sstarting\\sa\\snew\\selection.*", "voting_timeout_starting_new_election"),
    State(".*Voting\\stimeout,\\sunable\\sto\\sstart\\selection,\\sdon't\\sknow\\senough\\snodes.*", "voting_timeout_too_few_nodes")
  )

  def labelForLogMessage(msg: String): String = {
    val matching = states.filter { state =>
      msg.matches(state.regex)
    }
    if (matching.length != 1) {
      System.out.println("Found " + matching.length + " synoptic labels matching message " + msg)
      matching{0}.label
    } else {
      matching{0}.label
    }
  }

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
    writer.close()

    // Collect each regex, formatted for Synoptic
    val regexes = states.map(state => state.synopticRegex)
    // Insert -r before each regex
    val regexArgs = regexes flatMap { regex =>
      Array("-r", regex)
    }

    // The command passed to Synoptic
    val args = Array("temp/trace_log_" + timesRun + ".tmp") ++ regexArgs ++ Array("-o", "temp/output_" + timesRun, "-i")
    logger.info("Running Synoptic...")
    val main = SynopticMain.processArgs(args)
    val pGraph = main.createInitialPartitionGraph()
    if (pGraph != null) {
      main.runSynoptic(pGraph)
      logger.info("Synoptic completed, parsing dot graph output...")
      val stateGraph = new StateMachineGraph("temp/output_" + timesRun + ".dot")
      removeFirstCycle(stateGraph, metaTrace)
      None
    } else {
      None
    }
  }
  
  def removeFirstCycle(stateGraph: StateMachineGraph, metaTrace: MetaEventTrace): Option[EventTrace] = {
    logger.info("Attempting to remove a cycle")
    // Some quick pseudocode for finding a cycle in the state machine and
    // creating an EventTrace that would remove it.

    // Need some way to skip ahead so we can try to remove other cycles if the 
    // first cycle found is actually needed to trigger the violation.

    
    // eventTrace: the ordered list of events
    // seen: a set of state nodes already seen
    // eventStack: events seen so far
    // result: a new EventTrace with a cycle removed

    // first node of event trace
    val events = metaTrace.trace.getEvents()
    var src = stateGraph.labelToNodes.get("INITIAL").get.head
    var visited = Set(src)
    val path = new Stack[(Node, Node, Event)]()

    var cycleDetected = false
    for (event <- events) {
      metaTrace.eventToLogOutput.get(event) foreach { messages =>
        var dst = src
        for (message <- messages) {
          // don't mark nodes in the middle of an event's messages as seen.
          dst = stateGraph.resolvePath(dst, labelForLogMessage(message))
        }

        if (!cycleDetected && visited.contains(dst)) {
          logger.info("Removing cycle")
          while (path.head._2 != dst) {
            val popped = path.pop()
            logger.info(popped._1 + "->" + popped._2)
          }
          cycleDetected = true
        } else {
          visited += dst
          path.push((src, dst, event))
          src = dst
        }
      }
    }

    if (cycleDetected) {
      val filteredEvents = new SynchronizedQueue[Event]()
      path map { edge =>
        filteredEvents += edge._3
      }
      Some(new EventTrace(filteredEvents, new Queue[ExternalEvent] ++ metaTrace.trace.original_externals))
    } else {
      None
    }
  }
}
// Stores all (Meta)EventTraces that have been executed in the past
object HistoricalEventTraces {
  def current: MetaEventTrace = traces.last
  def isEmpty = traces.isEmpty

  // In order of least recent to most recent
  val traces = new ListBuffer[MetaEventTrace]

  // If you want fast lookup of EventTraces, you could populate a HashMap here:
  // { EventTrace -> MetaEventTrace }
}

class StateMachineGraph(filename: String) {
  var idToNodes = Map.empty[Int, Node]
  var labelToNodes = Map.empty[String, Seq[Node]]
  var adjList = Map.empty[Node, Seq[Node]]

  parseFromFile(filename)

  def resolvePath(source: Node, destLabel: String): Node = {
    val sameLabels = labelToNodes.get(destLabel).get
    val possibleNodes = adjList.get(source).get

    val result = possibleNodes.union(sameLabels)
    if (result.length != 1) {
      System.out.println("Found " + result.length + " possible destinations for source node " + source)
      result.foreach(node => System.out.println(node))
      result.head
    }
    result.head
  }

  def parseFromFile(filename: String): Unit = {
    val f = new File(filename)
    val s = new Scanner(f)
    s.nextLine()  // Skip first line with name of graph
    while (s.hasNextLine) {
      val line = s.nextLine()
      if (line.startsWith("}")) {}  // Skip last line
      else if (line.startsWith("  ")) {
        // Handle lines that describe the nodes
        val id = Integer.parseInt(line.split(" ").filter(!_.isEmpty){0})

        val startQuote = line.indexOf('"')
        val endQuote = line.indexOf('"', startQuote + 1)
        val label = line.substring(startQuote + 1, endQuote)

        val node = new Node(id, label)
        adjList += ((node, Seq.empty[Node]))
        idToNodes += ((id, node))

        if (labelToNodes.contains(label)) {
          labelToNodes += ((label, labelToNodes.get(label).get ++ Seq(node)))
        } else {
          labelToNodes += ((label, Seq(node)))
        }
      } else {
        // Handle lines that describe the edges
        val tokens = line.split("->")
        val source = Integer.parseInt(tokens{0})
        val dest = Integer.parseInt(tokens{1}.split(" "){0})

        val sourceNode = idToNodes.get(source).get
        val destNode = idToNodes.get(dest).get
        adjList += ((sourceNode, adjList.get(sourceNode).get ++ Seq(destNode)))
      }
    }
  }
}

class Node(val id: Int, val label: String) {
  override def toString: String = "Node[id=" + id + ", label=" + label + "]"
  override def equals(other: Any): Boolean = {
    other.isInstanceOf[Node] && other.asInstanceOf[Node].id == this.id
  }
}

case class State(regex: String, label: String) {
  def synopticRegex: String = "^(?<actor>)" + regex + "(?<TYPE=>" + label + ")$"
}

