package zhttp.service

import io.netty.util.concurrent.{Future, GenericFutureListener}
import zhttp.core.JChannelHandlerContext
import zio.{Exit, Fiber, URIO, ZIO}

/**
 * Provides basic ZIO based utilities for any ZIO based program to execute in a channel's context. It will automatically
 * cancel the execution when the channel closes.
 */
final class UnsafeChannelExecutor[R](runtime: zio.Runtime[R]) {
  def unsafeExecute_(ctx: JChannelHandlerContext)(program: ZIO[R, Throwable, Any]): Unit = {
    unsafeExecute(ctx, program)({
      case Exit.Success(_)     => ()
      case Exit.Failure(cause) =>
        cause.failureOption match {
          case Some(error: Throwable) => ctx.fireExceptionCaught(error)
          case _                      => ()
        }
        ctx.close()
        ()
    })
  }

  def unsafeExecute[E, A](ctx: JChannelHandlerContext, program: ZIO[R, E, A])(
    cb: Exit[E, A] => Any,
  ): Unit = {
    val close = ctx.channel().closeFuture()

    var listener: GenericFutureListener[Future[Any]] = { _ => () }

    val cancel = runtime.unsafeRunAsyncCancelable(program.disconnect) { exit =>
      cb(exit)
      close.removeListener(listener)
    }

    listener = { _ => cancel(Fiber.Id.None); () }

    close.addListener(listener)

    ()
  }
}

object UnsafeChannelExecutor {
  def make[R]: URIO[R, UnsafeChannelExecutor[R]] = for {
    runtime <- ZIO.runtime[R]
  } yield new UnsafeChannelExecutor[R](runtime)
}
