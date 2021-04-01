package sw

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s.Port
import fs2._
import fs2.io.net.tls.TLSSocket
import fs2.io.net.{Network, Socket}

object TestSSLServer extends IOApp {

  /** Simplest thing we can do: launch a server with an insecure TLS context,
    * I was able to reproduce with a real KeyStore */
  private val setup: Resource[IO, Stream[IO, TLSSocket[IO]]] = for {
    tlsContext <- Resource.eval(
      Network[IO].tlsContext.fromKeyStoreResource(
        "keystore.jks",
        "password".toCharArray,
        "password".toCharArray
      )
    )
    addressAndConnections <-
      Network[IO].serverResource(port = Some(Port.fromInt(5555).get))
    (_, connections) = addressAndConnections
  } yield connections.flatMap((s: Socket[IO]) =>
    Stream.resource(tlsContext.server(s))
  )

  override def run(args: List[String]): IO[ExitCode] =
    Stream
      .resource(setup)
      .flatMap { server =>
        server.map { socket =>
          socket.reads.chunks
            .foreach(socket.write(_))
        /* In order to ensure that it's not a fault from this socket */
        //            .handleErrorWith(_ => Stream.empty)
        }.parJoinUnbounded
      }
      .compile
      .to(Chunk)
      .as(ExitCode.Success)
}
