package io.janstenpickle.trace4cats.http4s.server

import cats.Monad
import cats.effect.BracketThrow
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.http4s.common.{Http4sRequestFilter, Http4sSpanNamer, Request_}
import io.janstenpickle.trace4cats.inject.{ContextConstructor, EntryPoint, Trace}
import org.http4s._
import org.http4s.util.CaseInsensitiveString

trait ServerSyntax {
  implicit class TracedRoutes[F[_], G[_]](routes: HttpRoutes[G]) {
    def inject(
      entryPoint: EntryPoint[F],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Span[F]], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpRoutes[F] = {
      val context = Http4sContextConstructors.fromHeaders(spanNamer, requestFilter)(entryPoint.toContextConstructor)

      ServerTracer.injectRoutes(routes, context, dropHeadersWhen)
    }

    def traced(
      contextConstructor: ContextConstructor[F, Request_, Span[F]],
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Span[F]], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpRoutes[F] =
      ServerTracer.injectRoutes(routes, contextConstructor, dropHeadersWhen)

    def injectContext[Ctx](
      entryPoint: EntryPoint[F],
      makeContext: (Request_, Span[F]) => F[Ctx],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Ctx], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpRoutes[F] = {
      val context =
        Http4sContextConstructors.fromHeadersContext(makeContext, spanNamer, requestFilter)(
          entryPoint.toContextConstructor
        )

      ServerTracer.injectRoutes(routes, context, dropHeadersWhen)
    }

    def tracedContext[Ctx](
      contextConstructor: ContextConstructor[F, Request_, Ctx],
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Ctx], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpRoutes[F] =
      ServerTracer.injectRoutes(routes, contextConstructor, dropHeadersWhen)

  }

  implicit class TracedHttpApp[F[_], G[_]](app: HttpApp[G]) {
    def inject(
      entryPoint: EntryPoint[F],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Span[F]], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpApp[F] = {
      val context = Http4sContextConstructors.fromHeaders(spanNamer, requestFilter)(entryPoint.toContextConstructor)

      ServerTracer.injectApp(app, context, dropHeadersWhen)
    }

    def traced(
      contextConstructor: ContextConstructor[F, Request_, Span[F]],
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Span[F]], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpApp[F] =
      ServerTracer.injectApp(app, contextConstructor, dropHeadersWhen)

    def injectContext[Ctx](
      entryPoint: EntryPoint[F],
      makeContext: (Request_, Span[F]) => F[Ctx],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Ctx], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpApp[F] = {
      val context =
        Http4sContextConstructors.fromHeadersContext(makeContext, spanNamer, requestFilter)(
          entryPoint.toContextConstructor
        )

      ServerTracer.injectApp(app, context, dropHeadersWhen)
    }

    def tracedContext[Ctx](
      contextConstructor: ContextConstructor[F, Request_, Ctx],
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Ctx], F: BracketThrow[F], G: Monad[G], trace: Trace[G]): HttpApp[F] =
      ServerTracer.injectApp(app, contextConstructor, dropHeadersWhen)

  }
}
