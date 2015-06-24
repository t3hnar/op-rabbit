package com.spingo.op_rabbit.props

import org.scalatest.{FunSpec, Matchers}
import com.spingo.op_rabbit.QueueMessage
import com.spingo.op_rabbit.DefaultMarshalling.utf8StringMarshaller
class packageSpec extends FunSpec with Matchers {
  case class Data(name: String, age: Int)


  it("thing") {
    val msg = QueueMessage(
      "very payload",
      queue = "destination.queue",
      properties = List(DeliveryMode.persistent, ReplyTo("respond.here.please"), ClusterId("hi")))

    msg.properties.getDeliveryMode should be (2)
    msg.properties.getReplyTo should be ("respond.here.please")
    msg.properties.getClusterId should be ("hi")



    // val msg = QueueMessage(
    //   Data("Tim", age = 5),
    //   queue = "destination.queue",
    //   properties = List(DeliveryMode.persistent, ReplyTo("my.queue"), ClusterId("hi")))
    // rabbitMq ! msg

    // await(msg.published)
    // // + no need to set up timeout
    // // - messages are stateful
    // // - must assign message, publish, then check promise

    // msg = QueueMessage(
    //   Data("Tim", age = 5),
    //   queue = "destination.queue",
    //   properties = List(DeliveryMode.persistent, ReplyTo("my.queue"), ClusterId("hi")),
    //   confirm = true)
    // implicit val timeout = akka.util.Timeout(5 seconds)
    // await(rabbitMq ? msg)
    // // + can reuse the same message object, unique future for each delivery
    // // - need to set up akka timeout




  }
}
