package com.spingo.op_rabbit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import shapeless._
import ops.hlist.Prepend
import scala.util.{Success,Try,Failure}
// import
package object dsl {

  trait Ackable {
    val handler: Handler
  }
  object Ackable {
    implicit def ackableFromFuture(f: Future[_]) = new Ackable {
      val handler: Handler = { (p, delivery) =>
        import scala.concurrent.ExecutionContext.Implicits.global
        p.completeWith(f.map(_ => Right(Unit)))
      }
    }

    implicit def ackableFromUnit(u: Unit) = new Ackable {
      val handler: Handler = { (p, delivery) =>
        p.success(Right(u))
      }
    }
  }

  trait Nackable {
    val handler: Handler
  }
  object Nackable {
    implicit def nackableFromRejection(rejection: Rejection) = new Nackable {
      val handler: Handler = { (p, _) => p.success(Left(rejection)) }
    }
    implicit def nackableFromString(u: String) = new Nackable {
      val handler: Handler = { (p, _) => p.success(Left(Rejection(u))) }
    }
    implicit def nackableFromUnit(u: Unit) = new Nackable {
      val handler: Handler = { (p, _) => p.success(Left(Rejection("General Handler Rejection"))) }
    }
  }
  case class Rejection(reason: String)

  type Result = Either[Rejection, Unit]
  type Handler = (Promise[Result], Consumer.Delivery) => Unit
  case class ChannelConfiguration(qos: Int)
  case class ChannelDirective(configurations: List[ChannelConfiguration]) {
    def apply(thunk: => Unit): Unit = {}
  }

  case class SubscriptionDirective(queue: String) {
    def apply(thunk: => Unit): Unit = {}
  }


  trait ConjunctionMagnet[L <: HList] {
    type Out
    def apply(underlying: Directive[L]): Out
  }


  object ConjunctionMagnet {
    implicit def fromDirective[L <: HList, R <: HList](other: Directive[R])(implicit p: Prepend[L, R]) =
      new ConjunctionMagnet[L] {
        type Out = Directive[p.Out]
        def apply(underlying: Directive[L]): Out =
          new Directive[p.Out] {
            def happly(f: p.Out ⇒ Handler) =
              underlying.happly { prefix ⇒
                other.happly { suffix ⇒
                  f(p(prefix, suffix))
                }
              }
          }
      }
  }

  abstract class Directive[L <: HList] { self =>
    def &(magnet: ConjunctionMagnet[L]): magnet.Out = magnet(this)
    def |[R >: L <: HList](that: Directive[R]): Directive[R] =
      new Directive[R] {
        def happly(f: R => Handler) = { (upstreamPromise, delivery) =>
          @volatile var doRecover = true
          val interimPromise = Promise[Result]
          val left = self.happly { list =>
            { (promise, delivery) =>
              // if we made it this far, then the directives succeeded; don't recover
              doRecover = false
              f(list)(promise, delivery)
            }
          }(interimPromise, delivery)
          import scala.concurrent.ExecutionContext.Implicits.global
          interimPromise.future.onComplete {
            case Success(Left(rejection)) if doRecover =>
              that.happly(f)(upstreamPromise, delivery)
            case _ =>
              upstreamPromise.completeWith(interimPromise.future)
          }
        }
      }
    def happly(f: L => Handler): Handler
  }
  object Directive {
    implicit def pimpApply[L <: HList](directive: Directive[L])(implicit hac: ApplyConverter[L]): hac.In ⇒ Handler = f ⇒ directive.happly(hac(f))
  }

  trait Directive1[T] extends Directive[T :: HNil]

  trait SubscriptionDSL {
    def channel(qos: Int): ChannelDirective = ChannelDirective(List(ChannelConfiguration(qos)))
    def queueSubscription(queue: String) = SubscriptionDirective(queue)
    def as[T](implicit um: RabbitUnmarshaller[T]) = um

    def provide[T](value: T) = hprovide(value :: HNil)
    def hprovide[T <: HList](value: T) = new Directive[T] {
      def happly(fn: T => Handler) =
        fn(value)
    }
    def ack(f: Ackable): Handler = f.handler
    def nack(f: Nackable): Handler = f.handler

    def payload[T](um: RabbitUnmarshaller[T]): Directive1[T] = new Directive1[T] {
      def happly(fn: ::[T, HNil] => Handler): Handler = { (promise, delivery) =>
        val data = um.unmarshall(delivery.body, Option(delivery.properties.getContentType), Option(delivery.properties.getContentEncoding))
        fn(data :: HNil)
      }
    }

    def extract[T](map: Consumer.Delivery => T) = new Directive1[T] {
      def happly(fn: ::[T, HNil] => Handler): Handler = { (promise, delivery) =>
        val data = map(delivery)
        fn(data :: HNil)
      }
    }
    def extractEither[T](map: Consumer.Delivery => Either[Rejection, T]) = new Directive1[T] {
      def happly(fn: ::[T, HNil] => Handler): Handler = { (promise, delivery) =>
        map(delivery) match {
          case Left(rejection) => nack(rejection)
          case Right(value) => fn(value :: HNil)
        }
      }
    }

    def optionalProperty[T](extractor: props.PropertyExtractor[T]) = extract { delivery =>
      extractor.unapply(delivery.properties)
    }
    def property[T](extractor: props.PropertyExtractor[T]) = extractEither { delivery =>
      extractor.unapply(delivery.properties) match {
        case Some(v) => Right(v)
        case None => Left(Rejection(s"Property ${extractor.name} was not provided"))
      }
    }
  }

}

object Test {
  props.Header("thing", null).asInstanceOf[props.Header]

  new dsl.SubscriptionDSL {
    channel(qos = 5) {
      queueSubscription(queue = "very-queue") {
        ((property(props.Header("thing")) | property(props.Header("neat"))) & optionalProperty(props.ReplyTo) & payload(as[String])) { (thingHeader, reply, string) =>
          thingHeader.asString
          import scala.concurrent.ExecutionContext.Implicits.global

          ack {
            Future {
              // do work
              1
            }
          }
        }

        // replyTo { address =>
        //   payload(as[String]) { string =>
        //     ack(Future.successful(1))
        //   }
        // }
      }
    }
  }
}
