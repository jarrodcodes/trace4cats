package io.janstenpickle.trace4cats.fs2.syntax

import cats.data.WriterT
import cats.effect.{Bracket, Concurrent, Timer}
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.functor._
import cats.{~>, Applicative, Defer, Functor}
import fs2.{Chunk, Stream}
import io.janstenpickle.trace4cats.fs2.{ContinuationSpan, MultiParentSpan, TracedStream}
import io.janstenpickle.trace4cats.inject.{EntryPoint, LiftTrace, Provide}
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanContext, SpanKind, TraceHeaders}
import io.janstenpickle.trace4cats.{Span, ToHeaders}

import scala.concurrent.duration.FiniteDuration

trait Fs2StreamSyntax {
  implicit class InjectEntryPoint[F[_]: Bracket[*[_], Throwable], A](stream: Stream[F, A]) {
    def inject(ep: EntryPoint[F], name: String): TracedStream[F, A] =
      inject(ep, _ => name, SpanKind.Internal)

    def inject(ep: EntryPoint[F], name: A => String): TracedStream[F, A] =
      inject(ep, name, SpanKind.Internal)

    def inject(ep: EntryPoint[F], name: String, kind: SpanKind): TracedStream[F, A] =
      inject(ep, _ => name, kind)

    def inject(ep: EntryPoint[F], name: A => String, kind: SpanKind): TracedStream[F, A] =
      WriterT(stream.evalMapChunk(a => ep.root(name(a), kind).use(s => (s -> a).pure)))

    def injectContinue(ep: EntryPoint[F], name: String)(f: A => TraceHeaders): TracedStream[F, A] =
      injectContinue(ep, name, SpanKind.Internal)(f)

    def injectContinue(ep: EntryPoint[F], name: String, kind: SpanKind)(f: A => TraceHeaders): TracedStream[F, A] =
      injectContinue(ep, _ => name, kind)(f)

    def injectContinue(ep: EntryPoint[F], name: A => String)(f: A => TraceHeaders): TracedStream[F, A] =
      injectContinue(ep, name, SpanKind.Internal)(f)

    def injectContinue(ep: EntryPoint[F], name: A => String, kind: SpanKind)(f: A => TraceHeaders): TracedStream[F, A] =
      WriterT(stream.evalMapChunk(a => ep.continueOrElseRoot(name(a), kind, f(a)).use(s => (s -> a).pure)))
  }

  implicit class TracedStreamOps[F[_], A](stream: TracedStream[F, A]) {

    private def eval[B](f: A => F[B])(implicit F: Functor[F]): (Span[F], A) => F[(Span[F], B)] = { case (span, a) =>
      span match {
        case s: ContinuationSpan[F] => s.run(f(a)).map(span -> _)
        case _ => f(a).map(span -> _)
      }
    }

    private def eval[B](name: String, kind: SpanKind, attributes: (String, AttributeValue)*)(f: A => F[B])(implicit
      F: Bracket[F, Throwable]
    ): (Span[F], A) => F[(Span[F], B)] = { case (span, a) =>
      span.child(name, kind).use { child =>
        child.putAll(attributes: _*) *> eval(f).apply(span, a)
      }
    }

    private def evalTrace[G[_], B](
      f: A => G[B]
    )(implicit F: Functor[F], provide: Provide[F, G]): (Span[F], A) => F[(Span[F], B)] = { case (span, a) =>
      provide(f(a))(span).map(span -> _)
    }

    def evalMapTrace[G[_], B](f: A => G[B])(implicit F: Functor[F], provide: Provide[F, G]): TracedStream[F, B] =
      WriterT(stream.run.evalMap(evalTrace(f).tupled))

    def evalMapChunkTrace[G[_], B](
      f: A => G[B]
    )(implicit F: Applicative[F], provide: Provide[F, G]): TracedStream[F, B] =
      WriterT(stream.run.evalMapChunk(evalTrace(f).tupled))

    def evalMap[B](f: A => F[B])(implicit F: Functor[F]): TracedStream[F, B] =
      WriterT(stream.run.evalMap(eval(f).tupled))

    def evalMapChunk[B](f: A => F[B])(implicit F: Applicative[F]): TracedStream[F, B] =
      WriterT(stream.run.evalMapChunk(eval(f).tupled))

    def evalMap[B](name: String, attributes: (String, AttributeValue)*)(f: A => F[B])(implicit
      F: Bracket[F, Throwable]
    ): TracedStream[F, B] =
      evalMap(name, SpanKind.Internal, attributes: _*)(f)

    def evalMap[B](name: String, kind: SpanKind, attributes: (String, AttributeValue)*)(f: A => F[B])(implicit
      F: Bracket[F, Throwable]
    ): TracedStream[F, B] =
      WriterT(stream.run.evalMap(eval(name, kind, attributes: _*)(f).tupled))

    def evalMapChunk[B](name: String, attributes: (String, AttributeValue)*)(f: A => F[B])(implicit
      F: Bracket[F, Throwable]
    ): TracedStream[F, B] =
      evalMapChunk(name, SpanKind.Internal, attributes: _*)(f)

    def evalMapChunk[B](name: String, kind: SpanKind, attributes: (String, AttributeValue)*)(f: A => F[B])(implicit
      F: Bracket[F, Throwable]
    ): TracedStream[F, B] =
      WriterT(stream.run.evalMapChunk(eval(name, kind, attributes: _*)(f).tupled))

    def traceMapChunk[B](name: String, attributes: (String, AttributeValue)*)(f: A => B)(implicit
      F: Bracket[F, Throwable]
    ): TracedStream[F, B] =
      traceMapChunk[B](name, SpanKind.Internal, attributes: _*)(f)

    def traceMapChunk[B](name: String, kind: SpanKind, attributes: (String, AttributeValue)*)(f: A => B)(implicit
      F: Bracket[F, Throwable]
    ): TracedStream[F, B] =
      WriterT(stream.run.evalMapChunk(eval(name, kind, attributes: _*)(a => Applicative[F].pure(f(a))).tupled))

    def endTrace: Stream[F, A] =
      stream.value

    def endTrace[G[_]: Applicative: Defer](implicit provide: Provide[G, F]): Stream[G, A] = translate(
      provide.noopFk
    ).value

    def endTrace[G[_]: Applicative: Defer](span: Span[G])(implicit provide: Provide[G, F]): Stream[G, A] = translate(
      provide.fk(span)
    ).value

    def through[B](f: TracedStream[F, A] => TracedStream[F, B]): TracedStream[F, B] = f(stream)

    def liftTrace[G[_]: Applicative: Defer](implicit
      F: Applicative[F],
      defer: Defer[F],
      provide: Provide[F, G],
      lift: LiftTrace[F, G]
    ): TracedStream[G, A] =
      WriterT(stream.run.translate(lift.fk).map { case (span, a) =>
        ContinuationSpan.fromSpan[F, G](span) -> a
      })

    def translate[G[_]: Applicative: Defer](fk: F ~> G): TracedStream[G, A] =
      WriterT(stream.run.translate(fk).map { case (span, a) =>
        span.mapK(fk) -> a
      })

    def traceHeaders: TracedStream[F, (TraceHeaders, A)] = traceHeaders(ToHeaders.all)

    def traceHeaders(toHeaders: ToHeaders): TracedStream[F, (TraceHeaders, A)] =
      WriterT(stream.run.map { case (span, a) =>
        (span, (toHeaders.fromContext(span.context), a))
      })

    def mapTraceHeaders[B](f: (TraceHeaders, A) => B): TracedStream[F, B] =
      mapTraceHeaders[B](ToHeaders.all)(f)

    def mapTraceHeaders[B](toHeaders: ToHeaders)(f: (TraceHeaders, A) => B): TracedStream[F, B] =
      WriterT(stream.run.map { case (span, a) =>
        span -> f(toHeaders.fromContext(span.context), a)
      })

    def evalMapTraceHeaders[B](f: (TraceHeaders, A) => F[B])(implicit F: Functor[F]): TracedStream[F, B] =
      evalMapTraceHeaders(ToHeaders.all)(f)

    def evalMapTraceHeaders[B](toHeaders: ToHeaders)(f: (TraceHeaders, A) => F[B])(implicit
      F: Functor[F]
    ): TracedStream[F, B] =
      WriterT(stream.run.evalMap { case (span, a) => f(toHeaders.fromContext(span.context), a).map(span -> _) })

    private def deChunkSpans(s: Stream[F, Chunk[(Span[F], A)]])(implicit F: Applicative[F]) = s.flatMap { spans =>
      spans.head match {
        case Some((span, a)) =>
          val (parents, as) =
            spans.drop(1).foldLeft((List.empty[SpanContext], scala.collection.mutable.Buffer[A](a))) {
              case ((parents, arr), (span, a)) =>
                (span.context :: parents, arr :+ a)
            }

          Stream.emit((MultiParentSpan[F](span, parents), Chunk.buffer(as)))
        case None => Stream.empty
      }
    }

    def groupWithin(n: Int, d: FiniteDuration)(implicit F: Concurrent[F], timer: Timer[F]): TracedStream[F, Chunk[A]] =
      WriterT(deChunkSpans(stream.run.groupWithin(n, d)))

    def chunkN(n: Int, allowFewer: Boolean = true)(implicit F: Applicative[F]): TracedStream[F, Chunk[A]] =
      WriterT(deChunkSpans(stream.run.chunkN(n, allowFewer)))

    def chunks(implicit F: Applicative[F]): TracedStream[F, Chunk[A]] =
      WriterT(deChunkSpans(stream.run.chunks))

  }

}
