package io.cvbio.io

import java.io.{Closeable, InputStream}
import java.util.concurrent.atomic.AtomicInteger

import scala.io.Source

/** Companion object for [[AsyncStreamSink]]. */
private[io] object AsyncStreamSink {
  private val n: AtomicInteger = new AtomicInteger(1)
  private def nextName: String = s"AsyncStreamSinkThread-${n.getAndIncrement}"
}

/** Non-blocking class that will read output from a stream and pass it to a sink. */
private[io] class AsyncStreamSink(in: InputStream, private val sink: String => Unit) extends Closeable {
  private val source = Source.fromInputStream(in).withClose(() => in.close())
  private val thread = new Thread(() => source.getLines().foreach(sink))
  this.thread.setName(AsyncStreamSink.nextName)
  this.thread.setDaemon(true)
  this.thread.start()

  /** Give the thread 500 seconds to wrap up what it's doing and then interrupt it. */
  def close(): Unit = close(500)

  /** Give the thread `millis` milliseconds to finish what it's doing, then interrupt it. */
  def close(millis: Long) : Unit = {
    this.thread.join(millis)
    this.thread.interrupt()
  }
}
