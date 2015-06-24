package com.spingo.op_rabbit

import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.LongString
import com.rabbitmq.client.impl.LongStringHelper

/**
  Helper functions used internally to manipulate getting and setting custom headers
  */
object PropertyHelpers {
  private val RETRY_HEADER_NAME = "RETRY"

  def getRetryCount(properties: BasicProperties) =
    props.Header(RETRY_HEADER_NAME).unapply(properties).map(_.asString.toInt) getOrElse (0)

  def setRetryCount(properties: BasicProperties, count: Int) = {
    val p = props.Header(RETRY_HEADER_NAME, count)
    props.applyTo(Seq(p), properties.builder, Option(properties.getHeaders) getOrElse { new java.util.HashMap[String, Object] }).build
  }
}
