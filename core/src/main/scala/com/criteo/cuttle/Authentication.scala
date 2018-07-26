package com.criteo.cuttle

import cats.Id
import cats.data.{Kleisli, OptionT}
import cats.effect._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.{AuthedRequest, AuthedService, Request, Response}
import org.http4s.server.AuthMiddleware

/**
  * The cuttle API is private for any write operation while it is publicly
  * open for any read only operation. It allows to make it easy to build tooling
  * that monitor any running cuttle scheduler while restricting access to potentially
  * dangerous operations.
  *
  * The UI access itself requires authentication.
  */
object Auth {
  /**
    * A connected [[User]].
    */
  case class User(userId: String)

  object User {
    implicit val encoder: Encoder[User] = deriveEncoder
    implicit val decoder: Decoder[User] = deriveDecoder
  }

  private val authGuestAuth: Kleisli[IO, Request[IO], Either[String, User]] = Kleisli {
    request => IO(Right(User("Guest")))
  }

  val didier = AuthedService[String, IO]({ case errReq => AuthMiddleware.defaultAuthFailure[IO](errReq.req) })

  AuthMiddleware(authUser = authGuestAuth, onFailure =  didier)

  val GuestAuth = new AuthMiddleware[IO, User] {
    override def apply(authedService: Kleisli[({ type lambda[K] = OptionT[IO, K] })#lambda, AuthedRequest[IO, User], Response[IO]]) =
      Kleisli[({ type lambda[K] = OptionT[IO, K] })#lambda, Request[IO], Response[IO]] {
        request =>
          authedService(AuthedRequest[IO, User](User("Guest"), req = request))
      }
  }
}
