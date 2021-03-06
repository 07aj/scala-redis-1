package com.redis.sentinel

import java.util.concurrent.atomic.AtomicBoolean

import com.redis._
import scala.concurrent.duration._

class SentinelMonitor (address: SentinelAddress, listener: SentinelListener, config: SentinelClusterConfig)
  extends RedisSubscriptionMaintainer
  with Log{

  val maxRetry: Int = config.maxSentinelMonitorRetry
  val retryInterval: Long = config.sentinelRetryInterval

  private[sentinel] var sentinel: SentinelClientPool = _
  private[sentinel] var sentinelSubscriber: SentinelClient = _
  private var heartBeater: Option[SentinelHeartBeater] = None

  private val switchMasterListener = new SubscriptionReceiver() {
    def onReceived: String => Unit = msg => {
      onSwitchMaster(msg)
    }
    def onSubscriptionFailure: () => Unit = () => {
      listener.subscriptionFailure
    }
  }

  private val newSentinelListener = new SubscriptionReceiver() {
    def onReceived: String => Unit = msg => {
      val tokens = msg.split(" ")
      if (tokens(0) == "sentinel" && tokens.size == 8)
        listener.addNewSentinelNode(SentinelAddress(tokens(1)))
      else
        error("invalid +sentinel message: %s", msg)
    }
    def onSubscriptionFailure: () => Unit = () => {
      listener.subscriptionFailure
    }
  }

  private val downListener = new SubscriptionReceiver() {
    def onReceived: String => Unit = msg => {
      val tokens = msg.split(" ")
      if (tokens(0) == "sentinel" && tokens.size == 8)
        listener.removeSentinelNode(SentinelAddress(tokens(1)))
      else
        warn("unsupported +sdown: %s", msg)
    }
    def onSubscriptionFailure: () => Unit = () => {
      listener.subscriptionFailure
    }
  }

  init

  private def init {
    if (config.sentinelSubscriptionEnabled) {
      sentinelSubscriber = new SentinelClient(address)
      this.subscribe("+switch-master", switchMasterListener)
      this.subscribe("+sentinel", newSentinelListener)
      this.subscribe("+sdown", downListener)
    }

    sentinel = new SentinelClientPool(address)
    if (config.heartBeatEnabled) {
      heartBeater = Some(new SentinelHeartBeater {
        val sentinelClient: SentinelClient = new SentinelClient(address)
        def heartBeatListener: SentinelListener = listener
        def heartBeatInterval: Int = config.heartBeatInterval
      })
      heartBeater.foreach(new Thread(_).start())
    }
  }

  protected def getRedisSub: SubCommand = sentinelSubscriber
  protected def reconnect: Boolean = {
    try {
      sentinelSubscriber.disconnect
      sentinelSubscriber.connect
    } catch {
      case e: Throwable =>
        error("failed to reconnect sentinel", e)
        false
    }
  }

  private def onSwitchMaster(msg: String) {
    val switchMasterMsgs = msg.split(" ")
    if (switchMasterMsgs.size > 3) {
      val (masterName, host, port): (String, String, Int) = try {
        (switchMasterMsgs(0), switchMasterMsgs(3), switchMasterMsgs(4).toInt)
      }
      catch {
        case e: Throwable =>
          error("Message with wrong format received on Sentinel. %s", e, msg)
          (null, null, -1)
      }
      if (masterName != null) {
        listener.onMasterChange(RedisNode(masterName, host, port))
      }
    } else {
      error("Invalid message received on Sentinel. %s", msg)
    }
  }

  def isHeartBeating: Boolean = {
    heartBeater.map(_.isSentinelBeating).getOrElse(false)
  }

  def stop {
    stopped = true
    heartBeater.foreach(_.stop)
    sentinel.close
    sentinelSubscriber.stopSubscribing
    sentinelSubscriber.disconnect
  }
}

trait SentinelHeartBeater extends Runnable with Log{
  private var running = false
  private val beatingSentinel = new AtomicBoolean(true) // initialised to true so we don't unnecessarily wake up ops
  val sentinelClient: SentinelClient
  def heartBeatListener: SentinelListener
  def heartBeatInterval: Int

  def isSentinelBeating: Boolean = beatingSentinel.get

  def stop {
    running = false
  }

  def run {
    running = true

    while (running) {
      Thread.sleep(heartBeatInterval)
      try {
        if (!sentinelClient.connected){
          sentinelClient.disconnect
          sentinelClient.connect
        }
        sentinelClient.masters match {
          case Some(list) =>
            ifDebug("heart beating on " + sentinelClient.host + ":" + sentinelClient.port)
            heartBeatListener.onMastersHeartBeat(list.filter(_.isDefined).map(_.get))
          case None =>
            ifDebug("heart beat failure")
            heartBeatListener.heartBeatFailure
        }
        beatingSentinel.set(true)
      }catch {
        case e: Throwable =>
          ifDebug("heart beat is failed. running status "+running)
          if (running){
            error("sentinel heart beat failure")
            heartBeatListener.heartBeatFailure
            beatingSentinel.set(false)
          }
      }
    }
    ifDebug("heart beat is stopped. ")
    beatingSentinel.set(false)
    sentinelClient.disconnect
  }
}
