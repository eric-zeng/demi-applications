package akka.dispatch.verification

import java.io.{File, PrintWriter}
import java.util.Scanner
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


  val states = Array(
    new State(".*there\\\\sis\\\\sno\\\\sleader.*", "no_leader"),
    new State(".*Initializing\\\\selection.*","starting_election")
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
    writer.close



//    val regexes = Array(
//      "^.*there\\sis\\sno\\sleader.*(?<TYPE=>no_leader)$",
//      "^.*Initializing\\selection.*(?<TYPE=>starting_election)$"
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
  //  )
    val regexes = states.map(state => state.synopticRegex)
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
      val stateGraph = new DotGraph("temp/output_" + timesRun + ".dot")
      removeFirstCycle(stateGraph, metaTrace)
      None
    } else {
      None
    }
  }
  
  def removeFirstCycle(stateGraph: DotGraph, metaTrace: MetaEventTrace): Option[EventTrace] = {
    // Some quick pseudocode for finding a cylce in the state machine and 
    // creating an EventTrace that would remove it.

    // Need some way to skip ahead so we can try to remove other cycles if the 
    // first cycle found is actually needed to triger the violation.

    
    // eventTrace: the ordered list of events
    // seen: a set of state nodes already seen
    // eventStack: events seen so far
    // result: a new EventTrace with a cycle removed



    // first node of event trace
    var events = metaTrace.trace.getEvents()
    var curNode = stateGraph.labelToNodes.get("INITIAL").get.head
    var visited = Set(curNode)

    var i = 0
    for (i <- 0 to events.length) {
      var nextEvent = events(i)
      val messages = metaTrace.eventToLogOutput(nextEvent)
      for (j <- 0 to messages.length) {
        curNode = stateGraph.resolvePath(curNode, labelForLogMessage(messages(j)))
      }

      if (visited.contains(curNode)) {
        // cycle detected
        // TODO
        // save the node that starts/ends the cycle and the event that ended it
        // find the event that reaches that node first
        // output the events up to and included that event
        // output all events after the event that ended the cycle


      } else {
        visited += curNode
      }
    }

    //   if (seen.contains(getNode(cur))) {
    //     // cycle detected
    //     cycle = true
    //     while (getNode(popped) != getNode(cur)) {
    //       // remove all events in the cycle
    //       popped = eventStack.pop()
    //     }
    //     // reverse the order of the stack
    //     reverse(eventStack)

    //     while (!eventStack.isEmpty()) {
    //     // add the events before the cycle
    //       result.add(eventStack.pop())
    //     }

    //     while (cur != eventTrace.last)) {
    //       // add the events after the cycle
    //       cur = eventTrace.next
    //       result.add(cur)
    //     }
        
    //   } else {
    //     eventStack.push(cur)
    //     seen.add(getNode(cur))
    //     cur = eventTrace.next
    //   }
    // }

    // return result
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

class DotGraph(filename: String) {
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
    s.nextLine()
    while (s.hasNextLine) {
      val line = s.nextLine()
      if (line.startsWith("  ")) {
        val id = Integer.parseInt(line.split(" "){0})

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
}

class State(val regex: String, val label: String) {
  def synopticRegex: String = "^" + regex + "(?<TYPE=>" + label + ")$"
}
