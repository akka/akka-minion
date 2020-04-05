package akka.minion

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.{headers, HttpRequest, HttpResponse, MediaRange, ResponseEntity, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.minion.App.Settings
import akka.stream.{OverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import com.github.blemale.scaffeine.Scaffeine
import spray.json.RootJsonFormat

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

trait GithubCaller {

  implicit def system: ActorSystem
  implicit lazy val ec: ExecutionContext = system.dispatcher
  def GitHubUrl: String
  def settings: Settings

  // Throttled global connection pool
  lazy val (queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])], clientFuture: Future[Done]) =
    Source
      .queue[(HttpRequest, Promise[HttpResponse])](512, OverflowStrategy.backpressure)
      .throttle(settings.apiCallPerHour, 1.hour, 300, ThrottleMode.shaping)
      .via(Http(system).cachedHostConnectionPoolHttps(GitHubUrl))
      .toMat(Sink.foreach {
        case (response, promise) =>
          promise.tryComplete(response)
      })(Keep.both)
      .run()

  private val responseCache = Scaffeine()
    .maximumSize(500)
    .build[Uri, (headers.EntityTag, Any)]()

  protected def throttledRequest(req: HttpRequest): Future[HttpResponse] = {
    val promise = Promise[HttpResponse]

    val request = req
      .addHeader(headers.Authorization(headers.GenericHttpCredentials("token", settings.token)))
      .addHeader(headers.Accept(MediaRange.custom("application/vnd.github.black-cat-preview+json")))

    queue.offer(request -> promise).flatMap(_ => promise.future)
  }
  private def throttledJson[T: RootJsonFormat](
      req: HttpRequest
  )(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] = {
    val etag = responseCache.getIfPresent(req.uri)
    throttledRequest(etag.fold(req) {
      case (tag, _) => req.addHeader(headers.`If-None-Match`(tag))
    }).flatMap {
      case response @ HttpResponse(StatusCodes.NotModified, _, _, _) =>
        response.discardEntityBytes()
        etag match {
          case Some((_, res)) => Future.successful(res.asInstanceOf[T])
          case None =>
            throw new Error("No changes from the cached version, but cache is empty.")
        }
      case response @ HttpResponse(StatusCodes.OK, _, entity, _) =>
        val resp = Unmarshal(entity).to[T]
        resp.foreach {
          case r =>
            response
              .header[headers.ETag]
              .fold(())(e => responseCache.put(req.uri, (e.etag, r)))
        }
        resp.transform(
          identity,
          ex => new Error(s"Failure while deserializing response from $GitHubUrl${req.uri}", ex)
        )
      case response =>
        Future.failed(
          new Error(
            s"Request to github service [${req.uri}] failed: got status code [${response.status}] and body [${Unmarshal(response.entity)
              .to[String]}]"
          )
        )
    }
  }

  protected def api[T: RootJsonFormat](repo: String, apiPath: String = "", base: String = "/repos/")(
      implicit um: Unmarshaller[ResponseEntity, T]
  ): Future[T] = {
    val req = HttpRequest(GET, uri = s"$base$repo$apiPath")
    throttledJson[T](req)
  }

}
