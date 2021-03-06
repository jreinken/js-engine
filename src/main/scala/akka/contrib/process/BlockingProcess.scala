package akka.contrib.process

import akka.actor._
import akka.util.ByteString
import java.io._
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.blocking
import java.lang.{ProcessBuilder => JdkProcessBuilder}
import akka.contrib.process.BlockingProcess.Started
import akka.contrib.process.StreamEvents.{Done, Ack}

/**
 * Process encapsulates an operating system process and its ability to be communicated with
 * via stdio i.e. stdin, stdout and stderr. The sink for stdin and the sources for stdout
 * and stderr are communicated in a Started event upon the actor being established. The
 * receiving actor (passed in as a constructor arg) is then subsequently sent stdout and
 * stderr events. When there are no more stdout or stderr events then the process's exit
 * code is communicated to the receiver as an int value. The exit code will always be
 * the last event communicated by the process unless the process is a detached one.
 *
 * The actor is expected to be associated with a blocking dispatcher as various calls are made
 * to input and output streams which can block.
 */
class BlockingProcess(args: immutable.Seq[String], environment: Map[String, String], receiver: ActorRef, detached: Boolean)
  extends Actor {

  // This quoting functionality is as recommended per http://bugs.java.com/view_bug.do?bug_id=6511002
  // The JDK can't change due to its backward compatibility requirements, but we have no such constraint
  // here. Args should be able to be expressed consistently by the user of our API no matter whether
  // execution is on Windows or not.

  def needsQuoting(s: String): Boolean =
    if (s.isEmpty) true else s.exists(c => c == ' ' || c == '\t' || c == '\\' || c == '"')

  def winQuote(s: String): String = {
    if (!needsQuoting(s)) {
      s
    } else {
      "\"" + s.replaceAll("([\\\\]*)\"", "$1$1\\\\\"").replaceAll("([\\\\]*)\\z", "$1$1") + "\""
    }
  }

  val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  def prepareArgs(args: immutable.Seq[String]): immutable.Seq[String] =
    if (isWindows) args.map(winQuote) else args

  val pb = new JdkProcessBuilder(prepareArgs(args).asJava)
  pb.environment().putAll(environment.asJava)
  val p = pb.start()

  val stdinSink = context.actorOf(Sink.props(p.getOutputStream), "stdin")
  val stdoutSource = context.watch(context.actorOf(Source.props(p.getInputStream, receiver), "stdout"))
  val stderrSource = context.watch(context.actorOf(Source.props(p.getErrorStream, receiver), "stderr"))

  var openStreams = 2

  def receive = {
    case Terminated(`stdoutSource` | `stderrSource`) =>
      openStreams -= 1
      if (openStreams == 0 && !detached) {
        val exitValue = blocking {
          p.waitFor()
          p.exitValue()
        }
        receiver ! exitValue
        context.stop(self)
      }
  }

  override def postStop() {
    if (!detached) p.destroy()
  }

  override def preStart() {
    receiver ! Started(stdinSink, stdoutSource, stderrSource)
  }
}

object BlockingProcess {

  /**
   * Return the props required to create a process actor.
   * @param args The sequence of string arguments to pass to the process.
   * @param receiver The actor to receive output and error events.
   * @param detached Whether the process will be daemonic.
   * @param ioDispatcherId The name given to the dispatcher configuration that will be used to manage blocking IO.
   * @return a props object that can be used to create the process actor.
   */
  def props(
             args: immutable.Seq[String],
             environment: Map[String, String],
             receiver: ActorRef,
             detached: Boolean = false,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[BlockingProcess], args, environment, receiver, detached)
    .withDispatcher(ioDispatcherId)

  /**
   * Sent on startup to the receiver - specifies the actors used for managing input, output and
   * error respectively.
   */
  case class Started(stdinSink: ActorRef, stdoutSource: ActorRef, stderrSource: ActorRef)

}

/**
 * Declares the types of event that are involved with streaming.
 */
object StreamEvents {

  /**
   * Sent in response to an Output even.
   */
  case object Ack

  /**
   * Sent when no more Output events are expected.
   */
  case object Done

}

/**
 * A sink of data given an output stream. Flow control is implemented and for each ByteString event received an Ack
 * is sent in return. A Done event is expected when there is no more data to be written. On receiving a Done
 * event the associated output stream will be closed.
 */
class Sink(os: OutputStream) extends Actor {
  def receive = {
    case bytes: ByteString =>
      blocking {
        os.write(bytes.toArray)
      }
      sender ! Ack
    case Done => context.stop(self)
  }

  override def postStop() {
    os.close()
  }
}

object Sink {
  def props(
             os: OutputStream,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[Sink], os)
    .withDispatcher(ioDispatcherId)
}

/**
 * A source of data given an input stream. Flow control is implemented and for each ByteString event received by the receiver,
 * an Ack is expected in return. At the end of the source, a Done event will be sent to the receiver and its associated
 * input stream is closed.
 */
class Source(is: InputStream, receiver: ActorRef, pipeSize: Int) extends Actor {
  require(pipeSize > 0)
  val buffer = new Array[Byte](pipeSize)

  def receive = {
    case Ack =>
      val len = blocking {
        is.read(buffer)
      }
      if (len > -1) {
        receiver ! ByteString.fromArray(buffer, 0, len)
      } else {
        receiver ! Done
        context.stop(self)
      }
  }

  override def postStop() {
    is.close()
  }

  override def preStart() {
    self ! Ack // Start reading
  }
}

object Source {
  def props(
             is: InputStream,
             receiver: ActorRef,
             pipeSize: Int = 1024,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[Source], is, receiver, pipeSize)
    .withDispatcher(ioDispatcherId)
}